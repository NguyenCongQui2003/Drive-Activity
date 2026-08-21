package driverecovery;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.driveactivity.v2.DriveActivity;
import com.google.api.services.driveactivity.v2.model.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.Calendar;
import java.util.TimeZone;
import java.text.ParseException;

public class DriveRecoveryService {

    private final Drive driveService;
    private DriveActivity activityService; // non-final: có thể swap sang owner impersonation trong Mode 2
    private final java.util.concurrent.ConcurrentLinkedQueue<FolderReport> allReports = new java.util.concurrent.ConcurrentLinkedQueue<>();

    // Cache để lưu folder names
    private final Map<String, String> folderNameCache = new ConcurrentHashMap<>();
    // Fix #5: Cache subfolder IDs để tránh gọi Drive API lặp lại
    private final Map<String, Set<String>> subfolderIdCache = new ConcurrentHashMap<>();
    // Fix #6: Track folder IDs đã xử lý để tránh report trùng lặp từ recursive call
    private final Set<String> processedFolderIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Flag đánh dấu nếu bị timeout → tên file Excel sẽ có prefix "Timeout-"
    private volatile boolean timedOut = false;

    // ⭐ FIX 429: Semaphore đảm bảo chỉ 1 thread gọi Activity API tại một thời điểm.
    // Activity API quota là "per user per minute" → 3 threads cùng gọi = 3x quota
    // consumption.
    private final java.util.concurrent.Semaphore activityApiSemaphore = new java.util.concurrent.Semaphore(1);

    // ⭐ Cross-user recovery registry: lưu IDs của items đã được recover thành công.
    // STATIC → tồn tại xuyên suốt toàn bộ run, chia sẻ giữa tất cả
    // DriveRecoveryService instances.
    // Mục đích: ngăn vòng lặp chéo user — khi UserA đã recover X về folderA,
    // UserB sẽ KHÔNG được move X từ folderA sang folderB (undo recovery).
    private static final Set<String> globalRecoveredIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public DriveRecoveryService(Drive driveService, DriveActivity activityService) {
        this.driveService = driveService;
        this.activityService = activityService;
    }

    public void processUserDrive(String userEmail) throws IOException {
        System.out.println("✓ Đang xử lý Drive của: " + userEmail);

        System.out.println("\n📂 Đang quét tất cả folder trong My Drive...");
        List<FolderInfo> allFolders = getAllFoldersRecursive(userEmail);
        // ⭐ FIX: Đảo ngược → xử lý folder sâu nhất trước (deepest-first).
        // DELETE event không có parent info. Khi B và D cùng thấy DELETE event của C
        // (C là con của D, D là con của B), cần D xử lý trước để kéo C về D.
        // Sau khi D kéo C về, B chạy sau: C.parents=[D], D∈allDescendantIds(B)
        // → grandchild check block → B bỏ qua C. Đúng!
        Collections.reverse(allFolders);
        System.out.println("✓ Tìm thấy " + allFolders.size() + " folder (deepest-first)\n");

        // ⭐ FIX: threadCount=1 để đảm bảo thứ tự deepest-first được tuân thủ.
        // Parallel processing gây race condition: B và D chạy đồng thời → B có thể
        // thắng → C kẹt ở B thay vì D. Sequential đảm bảo đúng thứ tự.
        int threadCount = 1;
        System.out.println("🚀 Bắt đầu xử lý tuần tự deepest-first...\n");

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.atomic.AtomicInteger processedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger errorCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (FolderInfo folder : allFolders) {
            executor.submit(() -> {
                try {
                    int current = processedCount.incrementAndGet();

                    synchronized (System.out) {
                        System.out.println("\n[Folder " + current + "/" + allFolders.size() + "] " + folder.path);
                        System.out.println("  ID: " + folder.id);
                        System.out.println("  Thread: " + Thread.currentThread().getName());
                    }

                    FolderReport report = checkFolder(folder, userEmail);
                    allReports.add(report);

                    synchronized (System.out) {
                        System.out.println("  ✅ Hoàn thành: " + folder.path);
                    }

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    String errMsg = (e instanceof com.google.api.client.googleapis.json.GoogleJsonResponseException gje)
                            ? "HTTP " + gje.getStatusCode() + ": "
                                    + (gje.getDetails() != null ? gje.getDetails().getMessage() : gje.getMessage())
                            : e.getClass().getSimpleName() + ": " + e.getMessage();
                    synchronized (System.err) {
                        System.err.println("  ❌ Lỗi tại " + folder.path + ": " + errMsg);
                    }
                    ProgressTracker.getInstance().log("  ❌ Lỗi folder: " + errMsg, ProgressTracker.LogLevel.ERROR);

                    FolderReport errorReport = new FolderReport();
                    errorReport.folderPath = folder.path;
                    errorReport.folderId = folder.id;
                    errorReport.error = errMsg;
                    allReports.add(errorReport);
                }
            });
        }

        executor.shutdown();

        try {
            System.out.println("\n⏳ Đang đợi tất cả threads hoàn thành...");

            boolean finished = executor.awaitTermination(24, java.util.concurrent.TimeUnit.HOURS);

            if (!finished) {
                String timeoutMsg = String.format(
                        "⚠️  TIMEOUT! User [%s] chưa hoàn thành sau 24 giờ. Đã xử lý: %d/%d folder (%.1f%%)",
                        userEmail, processedCount.get(), allFolders.size(),
                        allFolders.size() > 0 ? processedCount.get() * 100.0 / allFolders.size() : 0);
                System.err.println(timeoutMsg);
                ProgressTracker.getInstance().log(timeoutMsg, ProgressTracker.LogLevel.ERROR);
                timedOut = true;
                executor.shutdownNow();
            }

            System.out.println("\n✅ HOÀN THÀNH XỬ LÝ SONG SONG!");
            System.out.println("📊 Thống kê:");
            System.out.println("  - Tổng folders: " + allFolders.size());
            System.out.println("  - Đã xử lý: " + processedCount.get());
            System.out.println("  - Lỗi: " + errorCount.get());
            System.out.println("  - Thành công: " + (processedCount.get() - errorCount.get()));

        } catch (InterruptedException e) {
            System.err.println("❌ Bị ngắt quãng: " + e.getMessage());
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * ⭐ MODE 2 — Xử lý 1 folder cụ thể theo ID.
     *
     * Flow: TaskRunner.runMode2() dùng admin hỏi folder ID → biết owner là ai + tên
     * folder
     * → impersonate owner để tạo driveService + activityService
     * → truyền folderName xuống đây (không fetch lại).
     *
     * @param folderId   ID folder cần xử lý
     * @param folderName Tên folder (admin đã fetch ở TaskRunner, không cần hỏi lại)
     * @param userEmail  Email owner (admin đã detect ở TaskRunner)
     */
    public String processSpecificFolder(String folderId, String folderName, String userEmail) throws IOException {
        System.out.println("✓ Mode 2 đang xử lý folder: " + folderId + " | impersonate: " + userEmail);

        // ── Tên folder đã được TaskRunner fetch qua admin → dùng thẳng, không fetch
        // lại ──
        // (Tránh lỗi 404 khi impersonate owner mà folder không nằm trong My Drive của
        // họ)
        System.out.println("\n📁 Folder gốc: " + folderName + " (" + folderId + ")");
        ProgressTracker.getInstance().log("📁 Folder: " + folderName + " | owner: " + userEmail,
                ProgressTracker.LogLevel.INFO);

        String rootPath = "/" + folderName;
        FolderInfo rootFolder = new FolderInfo();
        rootFolder.id = folderId;
        rootFolder.name = folderName;
        rootFolder.path = rootPath;

        // ── Lấy danh sách folders (Mode 1 dùng getAllFoldersRecursive từ root,
        // Mode 2 dùng [rootFolder] + getFoldersRecursiveHelper từ folderId) ──
        System.out.println("\n📂 Đang quét tất cả subfolder trong folder...");
        List<FolderInfo> allFolders = new ArrayList<>();
        allFolders.add(rootFolder);
        allFolders.addAll(getFoldersRecursiveHelper(folderId, rootPath, userEmail));
        System.out.println("✓ Tìm thấy " + allFolders.size() + " folder\n");

        // ── Xử lý song song (giống hệt processUserDrive) ──────────────────────
        int threadCount = Math.min(3, Math.max(1, allFolders.size() / 100 + 1));
        System.out.println("🚀 Bắt đầu xử lý SONG SONG với " + threadCount + " thread(s)...\n");

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.atomic.AtomicInteger processedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger errorCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (FolderInfo folder : allFolders) {
            executor.submit(() -> {
                try {
                    int current = processedCount.incrementAndGet();

                    synchronized (System.out) {
                        System.out.println("\n[Folder " + current + "/" + allFolders.size() + "] " + folder.path);
                        System.out.println("  ID: " + folder.id);
                        System.out.println("  Thread: " + Thread.currentThread().getName());
                    }

                    FolderReport report = checkFolder(folder, userEmail);
                    allReports.add(report);

                    synchronized (System.out) {
                        System.out.println("  ✅ Hoàn thành: " + folder.path);
                    }

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    String errMsg = (e instanceof com.google.api.client.googleapis.json.GoogleJsonResponseException gje)
                            ? "HTTP " + gje.getStatusCode() + ": "
                                    + (gje.getDetails() != null ? gje.getDetails().getMessage() : gje.getMessage())
                            : e.getClass().getSimpleName() + ": " + e.getMessage();
                    synchronized (System.err) {
                        System.err.println("  ❌ Lỗi tại " + folder.path + ": " + errMsg);
                    }
                    ProgressTracker.getInstance().log("  ❌ Lỗi folder: " + errMsg, ProgressTracker.LogLevel.ERROR);

                    FolderReport errorReport = new FolderReport();
                    errorReport.folderPath = folder.path;
                    errorReport.folderId = folder.id;
                    errorReport.error = errMsg;
                    allReports.add(errorReport);
                }
            });
        }

        executor.shutdown();

        try {
            System.out.println("\n⏳ Đang đợi tất cả threads hoàn thành...");

            boolean finished = executor.awaitTermination(24, java.util.concurrent.TimeUnit.HOURS);

            if (!finished) {
                String timeoutMsg = String.format(
                        "⚠️  TIMEOUT! Folder [%s] của user [%s] chưa hoàn thành sau 24 giờ. Đã xử lý: %d/%d folder (%.1f%%)",
                        folderId, userEmail, processedCount.get(), allFolders.size(),
                        allFolders.size() > 0 ? processedCount.get() * 100.0 / allFolders.size() : 0);
                System.err.println(timeoutMsg);
                ProgressTracker.getInstance().log(timeoutMsg, ProgressTracker.LogLevel.ERROR);
                timedOut = true;
                executor.shutdownNow();
            }

            System.out.println("\n✅ HOÀN THÀNH XỬ LÝ SONG SONG!");
            System.out.println("📊 Thống kê:");
            System.out.println("  - Tổng folders: " + allFolders.size());
            System.out.println("  - Đã xử lý: " + processedCount.get());
            System.out.println("  - Lỗi: " + errorCount.get());
            System.out.println("  - Thành công: " + (processedCount.get() - errorCount.get()));

        } catch (InterruptedException e) {
            System.err.println("❌ Bị ngắt quãng: " + e.getMessage());
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // ── Xuất báo cáo (Mode 1 gọi từ TaskRunner, Mode 2 gọi tại đây) ───────
        ProgressTracker.getInstance().log("\n📊 Đang tạo báo cáo Excel...", ProgressTracker.LogLevel.INFO);
        String reportPath = generateExcelReport(userEmail);
        ProgressTracker.getInstance().log("✅ Báo cáo: " + reportPath, ProgressTracker.LogLevel.SUCCESS);
        return reportPath;
    }

    /**
     * Xây dựng path đầy đủ từ root đến folder theo ID.
     */
    private String buildFolderPath(String folderId) {
        try {
            List<String> parts = new ArrayList<>();
            String currentId = folderId;
            int maxDepth = 20; // bảo vệ vòng lặp vô tận
            while (currentId != null && maxDepth-- > 0) {
                File f = driveService.files().get(currentId)
                        .setFields("name, parents")
                        .setSupportsAllDrives(true)
                        .execute();
                parts.add(0, f.getName());
                List<String> parents = f.getParents();
                if (parents == null || parents.isEmpty())
                    break;
                String nextId = parents.get(0);
                // Dừng khi lên đến My Drive root
                try {
                    File parentFile = driveService.files().get(nextId)
                            .setFields("name")
                            .execute();
                    if ("My Drive".equals(parentFile.getName()))
                        break;
                } catch (Exception ignored) {
                    break;
                }
                currentId = nextId;
            }
            return "/" + String.join("/", parts);
        } catch (Exception e) {
            return "/" + folderId;
        }
    }

    private List<FolderInfo> getAllFoldersRecursive(String userEmail) throws IOException {
        List<FolderInfo> result = new ArrayList<>();
        List<File> rootFolders = getFoldersInParent("root", userEmail);

        for (File folder : rootFolders) {
            FolderInfo info = new FolderInfo();
            info.id = folder.getId();
            info.name = folder.getName();
            info.path = "/" + folder.getName();
            result.add(info);

            result.addAll(getFoldersRecursiveHelper(folder.getId(), info.path, userEmail));
        }

        return result;
    }

    private List<FolderInfo> getFoldersRecursiveHelper(String parentId, String parentPath, String userEmail)
            throws IOException {
        List<FolderInfo> result = new ArrayList<>();
        List<File> childFolders = getFoldersInParent(parentId, userEmail);

        for (File folder : childFolders) {
            FolderInfo info = new FolderInfo();
            info.id = folder.getId();
            info.name = folder.getName();
            info.path = parentPath + "/" + folder.getName();
            result.add(info);

            result.addAll(getFoldersRecursiveHelper(folder.getId(), info.path, userEmail));
        }

        return result;
    }

    private List<File> getFoldersInParent(String parentId, String userEmail) throws IOException {
        List<File> folders = new ArrayList<>();
        String pageToken = null;

        do {
            String query = "'" + parentId
                    + "' in parents and mimeType='application/vnd.google-apps.folder' and trashed=false";
            FileList result = driveService.files().list()
                    .setQ(query)
                    .setFields("nextPageToken, files(id, name)")
                    .setPageSize(1000)
                    .setPageToken(pageToken)
                    .execute();

            if (result.getFiles() != null) {
                folders.addAll(result.getFiles());
            }
            pageToken = result.getNextPageToken();
        } while (pageToken != null);

        return folders;
    }

    private FolderReport checkFolder(FolderInfo folder, String userEmail) throws IOException {
        FolderReport report = new FolderReport();
        report.folderPath = folder.path;
        report.folderId = folder.id;
        report.files = new ArrayList<>();
        report.subFolders = new ArrayList<>();

        // ============================================================
        // BUOC 1: XU LY FOLDERS bi thieu (neu duoc bat)
        // ============================================================
        if (Config.getSearchFolders()) {
            ProgressTracker pt = ProgressTracker.getInstance();
            pt.log("  📁 [FOLDER] Đang kiểm tra subfolder trong: " + folder.path, ProgressTracker.LogLevel.INFO);
            List<FileHistory> foldersFromActivity = getDirectSubFoldersFromActivity(folder.id, userEmail);

            // ⭐ FIX: Lấy danh sách subfolder THỰC TẾ hiện có từ Drive API
            // Merge với activity-based list để không bỏ sót folder được tạo bằng CREATE
            // (CREATE event không có MOVE data → bị bỏ qua trong processActivityForFolders)
            Set<String> currentSubfolderIds = getDirectSubfolderIds(folder.id, userEmail);

            // ⭐ FIX: Lấy TẤT CẢ subfolder ID trong subtree (đệ quy, có cache)
            // Dùng để kiểm tra folder "thiếu" có thực sự thiếu hay chỉ đang nằm
            // sâu hơn trong cây (grandchild) — tránh move nhầm folder đang còn đó
            Set<String> allDescendantIds = getAllSubfolderIds(folder.id, userEmail);

            // Tập hợp tất cả folder IDs đã biết từ activity
            Set<String> activityFolderIds = foldersFromActivity.stream()
                    .map(fh -> fh.id)
                    .collect(java.util.stream.Collectors.toSet());

            // ⭐ FIX: Thêm vào list những folder HIỆN CÓ nhưng chưa xuất hiện trong activity
            // Đây là những folder được tạo trực tiếp (CREATE) mà không bao giờ bị MOVE
            List<FileHistory> mergedFolders = new ArrayList<>(foldersFromActivity);
            for (String existingId : currentSubfolderIds) {
                if (!activityFolderIds.contains(existingId)) {
                    // Folder này không có trong activity → fetch tên qua Drive API
                    FileHistory extraFh = new FileHistory();
                    extraFh.id = existingId;
                    extraFh.name = getFolderNameCached(existingId);
                    extraFh.everInFolder = true;
                    extraFh.currentlyInFolder = true;
                    extraFh.lastSeenTimestamp = null;
                    mergedFolders.add(extraFh);
                    pt.log("  📁 Thêm folder có trong Drive nhưng chưa có activity: " + extraFh.name,
                            ProgressTracker.LogLevel.DETAIL);
                }
            }

            if (!mergedFolders.isEmpty()) {
                int totalFolders = mergedFolders.size();
                int presentFolders = 0;
                int inSubtreeCount = 0;
                int missingFolderCount = 0;

                // ── In header bảng subfolder ──
                pt.log("  ┌─────────────────────────────────────────────────────────────",
                        ProgressTracker.LogLevel.INFO);
                pt.log("  │ SUBFOLDER SUMMARY  (activity: " + foldersFromActivity.size()
                        + ", drive hiện tại: " + currentSubfolderIds.size()
                        + ", tổng merged: " + totalFolders + ")", ProgressTracker.LogLevel.INFO);
                pt.log("  ├─────────┬────────────────────────────────────────────────────",
                        ProgressTracker.LogLevel.INFO);
                pt.log("  │  Trạng  │  Tên Subfolder", ProgressTracker.LogLevel.INFO);
                pt.log("  ├─────────┼────────────────────────────────────────────────────",
                        ProgressTracker.LogLevel.INFO);

                for (FileHistory fh : mergedFolders) {
                    SubFolderInfo sfInfo = new SubFolderInfo();
                    sfInfo.folderName = fh.name;
                    sfInfo.folderId = fh.id;
                    sfInfo.lastSeen = fh.lastSeenTimestamp != null ? fh.lastSeenTimestamp : "N/A";

                    if (currentSubfolderIds.contains(fh.id)) {
                        // ── CÓ: đang là direct child, bỏ qua ──
                        presentFolders++;
                        sfInfo.status = "Có";
                        sfInfo.action = "-";
                        sfInfo.movedFrom = "-";
                        pt.log("  │  ✅ Có  │  " + fh.name, ProgressTracker.LogLevel.INFO);

                    } else if (allDescendantIds.contains(fh.id)) {
                        // ── TRONG SUBTREE: folder đang nằm sâu hơn (grandchild) — KHÔNG move ──
                        // Lý do: từng là direct child của folder này, nhưng sau đó được move
                        // vào một subfolder con → vẫn đang trong cây, không bị mất
                        inSubtreeCount++;
                        sfInfo.status = "Trong subfolder con";
                        sfInfo.action = "Không cần move (đang là grandchild)";
                        sfInfo.movedFrom = "-";
                        pt.log("  │  📂 Subfolder con │  " + fh.name + "  (bỏ qua — đang là grandchild)",
                                ProgressTracker.LogLevel.INFO);

                    } else if (fh.deletedFromSubtree && !fh.everInFolder) {
                        // ── BỊ XÓA qua DELETE event ──
                        // Không biết chắc là direct child hay grandchild → verify trước.
                        // Logic:
                        // • Trong Trash → báo cáo "Trong Thùng rác", KHÔNG move
                        // • Không Trash → tồn tại nhưng bị move đi → move về đúng folder
                        // • 404 → đã xóa vĩnh viễn
                        missingFolderCount++;
                        sfInfo.status = "Bị xóa";
                        pt.log("  │  🗑️ DELETE event │  " + fh.name + "  →  đang verify...",
                                ProgressTracker.LogLevel.WARNING);
                        try {
                            com.google.api.services.drive.model.File deletedFolder = null;
                            boolean verifyFailed = false;
                            try {
                                deletedFolder = driveService.files().get(fh.id)
                                        .setFields("id, name, trashed, explicitlyTrashed, parents, owners, driveId")
                                        .setSupportsAllDrives(true)
                                        .execute();
                            } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException gje) {
                                verifyFailed = true;
                                if (gje.getStatusCode() == 404) {
                                    sfInfo.status = "Đã xóa vĩnh viễn";
                                    sfInfo.action = "Đã xóa vĩnh viễn khỏi Drive";
                                    sfInfo.movedFrom = "-";
                                    pt.log("  │           │    ↳ ❌ Đã xóa vĩnh viễn (404)",
                                            ProgressTracker.LogLevel.WARNING);
                                } else {
                                    sfInfo.action = "Lỗi verify: HTTP " + gje.getStatusCode();
                                    sfInfo.movedFrom = "-";
                                    pt.log("  │           │    ↳ ⚠️  Lỗi HTTP " + gje.getStatusCode(),
                                            ProgressTracker.LogLevel.WARNING);
                                }
                            }

                            if (!verifyFailed && deletedFolder != null) {
                                boolean inTrash = Boolean.TRUE.equals(deletedFolder.getTrashed())
                                        || Boolean.TRUE.equals(deletedFolder.getExplicitlyTrashed());

                                if (inTrash) {
                                    // ── TRONG TRASH → chỉ báo cáo, KHÔNG move ──
                                    String ownerInfo = (deletedFolder.getOwners() != null
                                            && !deletedFolder.getOwners().isEmpty())
                                                    ? deletedFolder.getOwners().get(0).getEmailAddress()
                                                    : "unknown";
                                    sfInfo.status = "Trong Thùng rác";
                                    sfInfo.action = "Đang trong Thùng rác — không tự động move";
                                    sfInfo.movedFrom = "Trash (owner: " + ownerInfo + ")";
                                    pt.log("  │           │    ↳ 🗑️  Folder đang trong TRASH của " + ownerInfo
                                            + " — bỏ qua, không move", ProgressTracker.LogLevel.WARNING);

                                } else {
                                    // ── KHÔNG TRONG TRASH → folder vẫn còn, move về đúng folder ──
                                    pt.log("  │           │    ↳ ✅ Folder vẫn tồn tại (không trong Trash) → đang move về...",
                                            ProgressTracker.LogLevel.INFO);
                                    MoveResult mr = findAndMoveFolderWithResult(fh, folder.id, folder.path, userEmail);
                                    sfInfo.status = mr.success ? "Đã move về" : "Thiếu";
                                    sfInfo.action = mr.success ? "Đã move" : "Không move được: " + mr.reason;
                                    sfInfo.movedFrom = mr.movedFrom != null ? mr.movedFrom : "-";

                                    if (mr.success) {
                                        pt.log("  │           │    ↳ ✅ Move thành công từ: " + sfInfo.movedFrom,
                                                ProgressTracker.LogLevel.SUCCESS);
                                    } else {
                                        pt.log("  │           │    ↳ ⚠️  " + mr.reason,
                                                ProgressTracker.LogLevel.WARNING);
                                    }

                                    // Đệ quy kiểm tra bên trong nếu move thành công
                                    if (mr.actuallyMoved && processedFolderIds.add(fh.id)) {
                                        FolderInfo restoredFolder = new FolderInfo();
                                        restoredFolder.id = fh.id;
                                        restoredFolder.name = fh.name;
                                        restoredFolder.path = folder.path + "/" + fh.name;
                                        try {
                                            pt.log("  🔄 Đệ quy kiểm tra folder vừa restore: " + restoredFolder.path,
                                                    ProgressTracker.LogLevel.INFO);
                                            FolderReport subReport = checkFolder(restoredFolder, userEmail);
                                            allReports.add(subReport);
                                            pt.log("  ✅ Hoàn thành kiểm tra sâu: " + restoredFolder.path,
                                                    ProgressTracker.LogLevel.SUCCESS);
                                        } catch (Exception ex) {
                                            pt.log("  ⚠️ Lỗi đệ quy checkFolder(" + fh.name + "): " + ex.getMessage(),
                                                    ProgressTracker.LogLevel.WARNING);
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            sfInfo.action = "Lỗi: " + e.getMessage();
                            sfInfo.movedFrom = "-";
                            pt.log("  │           │    ↳ ❌ Lỗi xử lý: " + e.getMessage(),
                                    ProgressTracker.LogLevel.ERROR);
                        }

                    } else {
                        // ── THIẾU: không ở direct child, không ở subtree → cần tìm & move về ──
                        missingFolderCount++;
                        sfInfo.status = "Thiếu";
                        pt.log("  │  ❌ Thiếu │  " + fh.name + "  →  đang tìm...", ProgressTracker.LogLevel.WARNING);
                        try {
                            MoveResult mr = findAndMoveFolderWithResult(fh, folder.id, folder.path, userEmail);
                            sfInfo.movedFrom = mr.movedFrom != null ? mr.movedFrom : "-";

                            if (mr.inTrash) {
                                // ── TRONG THÙNG RÁC → chỉ báo cáo, KHÔNG move ──
                                sfInfo.status = "Trong Thùng rác";
                                sfInfo.action = "Đang trong Thùng rác — không tự động move";
                                pt.log("  │           │    ↳ 🗑️  Folder trong TRASH — bỏ qua, không move",
                                        ProgressTracker.LogLevel.WARNING);

                            } else if (mr.success) {
                                // ── MOVE THÀNH CÔNG ──
                                sfInfo.action = "Đã move";
                                pt.log("  │           │    ↳ ✅ Move thành công từ: " + sfInfo.movedFrom,
                                        ProgressTracker.LogLevel.SUCCESS);

                                // Đệ quy kiểm tra bên trong folder vừa restore
                                if (mr.actuallyMoved && processedFolderIds.add(fh.id)) {
                                    FolderInfo restoredFolder = new FolderInfo();
                                    restoredFolder.id = fh.id;
                                    restoredFolder.name = fh.name;
                                    restoredFolder.path = folder.path + "/" + fh.name;
                                    try {
                                        pt.log("  🔄 Đệ quy kiểm tra folder vừa restore: " + restoredFolder.path,
                                                ProgressTracker.LogLevel.INFO);
                                        FolderReport subReport = checkFolder(restoredFolder, userEmail);
                                        allReports.add(subReport);
                                        pt.log("  ✅ Hoàn thành kiểm tra sâu: " + restoredFolder.path,
                                                ProgressTracker.LogLevel.SUCCESS);
                                    } catch (Exception ex) {
                                        pt.log("  ⚠️ Lỗi đệ quy checkFolder(" + fh.name + "): " + ex.getMessage(),
                                                ProgressTracker.LogLevel.WARNING);
                                    }
                                }

                            } else {
                                // ── KHÔNG TÌM THẤY ──
                                sfInfo.action = "Không tìm thấy: " + mr.reason;
                                pt.log("  │           │    ↳ ⚠️  " + mr.reason, ProgressTracker.LogLevel.WARNING);
                            }

                        } catch (Exception e) {
                            sfInfo.action = "Lỗi: " + e.getMessage();
                            sfInfo.movedFrom = "-";
                            pt.log("  │           │    ↳ ❌ Lỗi: " + e.getMessage(), ProgressTracker.LogLevel.ERROR);
                        }
                    }
                    report.subFolders.add(sfInfo);
                }

                // ── In footer bảng + tổng kết ──
                pt.log("  └─────────┴────────────────────────────────────────────────────",
                        ProgressTracker.LogLevel.INFO);
                pt.log(String.format("  📊 Folder tổng kết: %d tổng | ✅ %d có | 📂 %d trong subfolder con | ❌ %d thiếu",
                        totalFolders, presentFolders, inSubtreeCount, missingFolderCount),
                        missingFolderCount > 0 ? ProgressTracker.LogLevel.WARNING : ProgressTracker.LogLevel.SUCCESS);
            } else {
                pt.log("  📁 Không có subfolder nào trong: " + folder.path, ProgressTracker.LogLevel.INFO);
            }
        }

        // ============================================================
        // BUOC 2: XU LY FILES bi thieu (neu duoc bat)
        // ============================================================
        if (!Config.getSearchFiles()) {
            return report;
        }

        ProgressTracker ptf = ProgressTracker.getInstance();
        ptf.log("  📄 [FILE] Đang đọc Activity history cho files trong: " + folder.path, ProgressTracker.LogLevel.INFO);
        List<FileHistory> filesFromActivity = getFilesFromActivity(folder.id, userEmail);

        // ⭐ Luôn lấy danh sách file thực tế từ Drive API (giống folder)
        List<File> currentFiles = getCurrentFilesInFolder(folder.id, userEmail);
        Set<String> currentFileIds = currentFiles.stream()
                .map(File::getId)
                .collect(Collectors.toSet());

        // ⭐ MERGE: Thêm file đang có trong Drive nhưng chưa có activity
        // (file được upload/tạo trực tiếp mà không có MOVE event nào)
        Set<String> activityFileIds = filesFromActivity.stream()
                .map(fh -> fh.id)
                .collect(Collectors.toSet());

        List<FileHistory> mergedFiles = new ArrayList<>(filesFromActivity);
        for (File existingFile : currentFiles) {
            if (!activityFileIds.contains(existingFile.getId())) {
                FileHistory extraFh = new FileHistory();
                extraFh.id = existingFile.getId();
                extraFh.name = existingFile.getName();
                extraFh.everInFolder = true;
                extraFh.currentlyInFolder = true;
                extraFh.lastSeenTimestamp = null;
                mergedFiles.add(extraFh);
                ptf.log("  📄 Thêm file có trong Drive nhưng chưa có activity: " + extraFh.name,
                        ProgressTracker.LogLevel.DETAIL);
            }
        }

        if (mergedFiles.isEmpty()) {
            ptf.log("  📄 Không có file nào trong: " + folder.path, ProgressTracker.LogLevel.INFO);
            return report;
        }

        Set<String> subfolderIds = getAllSubfolderIds(folder.id, userEmail);
        Set<String> filesInSubfolders = getAllFilesInSubfolders(subfolderIds, userEmail);

        int totalFiles = mergedFiles.size();
        int presentFiles = 0;
        int inSubfolder = 0;
        int missingCount = 0;

        // ── In header bảng file ──
        ptf.log("  ┌─────────────────────────────────────────────────────────────", ProgressTracker.LogLevel.INFO);
        ptf.log("  │ FILE SUMMARY  (activity: " + filesFromActivity.size() + ", drive hiện tại: " + currentFiles.size()
                + ", tổng merged: " + totalFiles + ", trong subfolder: " + filesInSubfolders.size() + ")",
                ProgressTracker.LogLevel.INFO);
        ptf.log("  ├──────────────────┬──────────────────────────────────────────", ProgressTracker.LogLevel.INFO);
        ptf.log("  │  Trạng thái      │  Tên File", ProgressTracker.LogLevel.INFO);
        ptf.log("  ├──────────────────┼──────────────────────────────────────────", ProgressTracker.LogLevel.INFO);

        for (FileHistory fileHistory : mergedFiles) {
            FileInfo fileInfo = new FileInfo();
            fileInfo.fileName = fileHistory.name;
            fileInfo.fileId = fileHistory.id;
            fileInfo.lastSeen = fileHistory.lastSeenTimestamp != null ? fileHistory.lastSeenTimestamp : "N/A";

            // CASE 1: File đang có trong folder → bỏ qua (không cần gọi thêm API)
            if (currentFileIds.contains(fileHistory.id)) {
                presentFiles++;
                fileInfo.status = "Có";
                fileInfo.action = "-";
                fileInfo.movedFrom = "-";
                fileInfo.currentStatus = null; // File đang có → không cần query thêm
                ptf.log("  │  ✅ Có            │  " + fileHistory.name, ProgressTracker.LogLevel.INFO);
                report.files.add(fileInfo);
                continue;
            }

            // CASE 2: File đang trong subfolder → bỏ qua (không cần gọi thêm API)
            if (filesInSubfolders.contains(fileHistory.id)) {
                inSubfolder++;
                fileInfo.status = "Trong subfolder";
                fileInfo.action = "Không cần move";
                fileInfo.movedFrom = "-";
                fileInfo.currentStatus = null; // Đang trong subfolder → không cần query thêm
                ptf.log("  │  📂 Trong subfolder │  " + fileHistory.name + "  (bỏ qua)", ProgressTracker.LogLevel.INFO);
                report.files.add(fileInfo);
                continue;
            }

            // CASE 2.5: File bị DELETE qua DELETE event — chưa confirm là direct child
            // Logic: Trash → báo cáo; không Trash → move về; 404 → xóa vĩnh viễn
            if (fileHistory.deletedFromSubtree && !fileHistory.everInFolder) {
                missingCount++;
                fileInfo.status = "Bị xóa";
                ptf.log("  │  🗑️ DELETE event    │  " + fileHistory.name + "  →  đang verify...",
                        ProgressTracker.LogLevel.WARNING);
                try {
                    com.google.api.services.drive.model.File deletedFile = null;
                    boolean verifyFailed = false;
                    try {
                        deletedFile = driveService.files().get(fileHistory.id)
                                .setFields("id, name, trashed, explicitlyTrashed, parents, owners")
                                .setSupportsAllDrives(true)
                                .execute();
                    } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException gje) {
                        verifyFailed = true;
                        if (gje.getStatusCode() == 404) {
                            fileInfo.status = "Đã xóa vĩnh viễn";
                            fileInfo.action = "Đã xóa vĩnh viễn khỏi Drive";
                            fileInfo.movedFrom = "-";
                            fileInfo.currentStatus = new CurrentStatus("DELETED", "❌ PERMANENTLY DELETED", "-", false);
                            ptf.log("  │                  │    ↳ ❌ Đã xóa vĩnh viễn (404)",
                                    ProgressTracker.LogLevel.WARNING);
                        } else {
                            fileInfo.action = "Lỗi verify: HTTP " + gje.getStatusCode();
                            fileInfo.movedFrom = "-";
                            fileInfo.currentStatus = getCurrentFileStatus(fileHistory.id);
                            ptf.log("  │                  │    ↳ ⚠️  HTTP " + gje.getStatusCode(),
                                    ProgressTracker.LogLevel.WARNING);
                        }
                    }

                    if (!verifyFailed && deletedFile != null) {
                        boolean inTrash = Boolean.TRUE.equals(deletedFile.getTrashed())
                                || Boolean.TRUE.equals(deletedFile.getExplicitlyTrashed());

                        if (inTrash) {
                            // ── TRONG TRASH → chỉ báo cáo, KHÔNG move ──
                            String ownerInfo = (deletedFile.getOwners() != null && !deletedFile.getOwners().isEmpty())
                                    ? deletedFile.getOwners().get(0).getEmailAddress()
                                    : "unknown";
                            fileInfo.status = "Trong Thùng rác";
                            fileInfo.action = "Đang trong Thùng rác — không tự động move";
                            fileInfo.movedFrom = "Trash (owner: " + ownerInfo + ")";
                            fileInfo.currentStatus = new CurrentStatus("TRASHED", "🗑️ IN TRASH",
                                    "Trash (" + ownerInfo + ")", true);
                            ptf.log("  │                  │    ↳ 🗑️  File trong TRASH của " + ownerInfo
                                    + " — bỏ qua, không move", ProgressTracker.LogLevel.WARNING);
                        } else {
                            // ── KHÔNG TRONG TRASH → tồn tại, move về ──
                            ptf.log("  │                  │    ↳ ✅ File vẫn tồn tại (không trong Trash) → đang move về...",
                                    ProgressTracker.LogLevel.INFO);
                            MoveResult mr = findAndMoveFileWithResult(fileHistory, folder.id, folder.path, userEmail,
                                    subfolderIds);
                            fileInfo.movedFrom = mr.movedFrom != null ? mr.movedFrom : "-";

                            if (mr.inTrash) {
                                fileInfo.status = "Trong Thùng rác";
                                fileInfo.action = "Đang trong Thùng rác — không tự động move";
                                fileInfo.currentStatus = new CurrentStatus("TRASHED", "🗑️ IN TRASH",
                                        fileInfo.movedFrom, true);
                                ptf.log("  │                  │    ↳ 🗑️  File trong TRASH — bỏ qua",
                                        ProgressTracker.LogLevel.WARNING);
                            } else if (mr.success) {
                                fileInfo.status = "Đã move về";
                                fileInfo.action = "Đã move";
                                fileInfo.currentStatus = new CurrentStatus("MOVED", "✅ ĐÃ MOVE VỀ ĐÚNG CHỖ",
                                        folder.path, false);
                                ptf.log("  │                  │    ↳ ✅ Move thành công từ: " + fileInfo.movedFrom,
                                        ProgressTracker.LogLevel.SUCCESS);
                            } else {
                                fileInfo.status = "Thiếu";
                                fileInfo.action = "Không move được: " + mr.reason;
                                fileInfo.currentStatus = getCurrentFileStatus(fileHistory.id);
                                ptf.log("  │                  │    ↳ ⚠️  " + mr.reason,
                                        ProgressTracker.LogLevel.WARNING);
                            }
                        }
                    }
                } catch (Exception e) {
                    fileInfo.action = "Lỗi: " + e.getMessage();
                    fileInfo.movedFrom = "-";
                    fileInfo.currentStatus = getCurrentFileStatus(fileHistory.id);
                    ptf.log("  │                  │    ↳ ❌ Lỗi xử lý: " + e.getMessage(),
                            ProgressTracker.LogLevel.ERROR);
                }
                report.files.add(fileInfo);
                continue;
            }

            // CASE 3: File thiếu → cần tìm & move
            missingCount++;
            fileInfo.status = "Thiếu";
            ptf.log("  │  ❌ Thiếu         │  " + fileHistory.name + "  →  đang tìm...",
                    ProgressTracker.LogLevel.WARNING);

            // ⭐ FIX: File đã bị xóa vĩnh viễn (404 khi verify CREATE) →
            // Không cần tìm kiếm trong Drive, ghi thẳng vào báo cáo.
            if (fileHistory.permanentlyDeleted) {
                ptf.log("  │                  │    ↳ ❌ File đã bị xóa vĩnh viễn khỏi toàn bộ Drive — đang tìm owner...",
                        ProgressTracker.LogLevel.WARNING);
                String ownerEmail = findOwnerViaReportsApi(fileHistory.id, Config.getAdminEmail());
                String ownerInfo = (ownerEmail != null && !ownerEmail.isBlank())
                        ? "Owner: " + ownerEmail
                        : "Không xác định được owner";
                fileInfo.action = "Không tìm thấy trong tổ chức (đã xóa vĩnh viễn)";
                fileInfo.movedFrom = "-";
                fileInfo.currentStatus = new CurrentStatus(
                        "DELETED",
                        "❌ PERMANENTLY DELETED",
                        ownerInfo,
                        false);
                ptf.log("  │                  │         " + ownerInfo, ProgressTracker.LogLevel.DETAIL);
                report.files.add(fileInfo);
                continue;
            }

            try {
                MoveResult moveResult = findAndMoveFileWithResult(fileHistory, folder.id, folder.path, userEmail,
                        subfolderIds);

                if (moveResult.inTrash) {
                    // ── TRONG THÙNG RÁC → chỉ báo cáo, KHÔNG move ──
                    fileInfo.status = "Trong Thùng rác";
                    fileInfo.action = "Đang trong Thùng rác — không tự động move";
                    fileInfo.movedFrom = moveResult.movedFrom != null ? moveResult.movedFrom : "Trash";
                    fileInfo.currentStatus = new CurrentStatus("TRASHED", "🗑️ IN TRASH", fileInfo.movedFrom, true);
                    ptf.log("  │                  │    ↳ 🗑️  File trong TRASH — bỏ qua",
                            ProgressTracker.LogLevel.WARNING);

                } else if (moveResult.success) {
                    // ── MOVE THÀNH CÔNG ──
                    fileInfo.action = "Đã move";
                    fileInfo.currentStatus = new CurrentStatus("MOVED", "✅ ĐÃ MOVE VỀ ĐÚNG CHỖ",
                            folder.path, false);
                    ptf.log("  │                  │    ↳ ✅ Move thành công từ: " + moveResult.movedFrom,
                            ProgressTracker.LogLevel.SUCCESS);

                } else {
                    // ── KHÔNG TÌM THẤY / LỖI → query trạng thái hiện tại ──
                    fileInfo.action = "Không tìm thấy: " + moveResult.reason;
                    ptf.log("  │                  │    ↳ ⚠️  " + moveResult.reason
                            + " — đang kiểm tra trạng thái file...", ProgressTracker.LogLevel.WARNING);
                    fileInfo.currentStatus = getCurrentFileStatus(fileHistory.id);
                    ptf.log("  │                  │         Trạng thái: " + fileInfo.currentStatus.status
                            + " | Vị trí: " + fileInfo.currentStatus.location, ProgressTracker.LogLevel.DETAIL);
                }

                fileInfo.movedFrom = moveResult.movedFrom != null ? moveResult.movedFrom : "-";
            } catch (Exception e) {
                fileInfo.action = "Lỗi: " + e.getMessage();
                fileInfo.movedFrom = "-";
                fileInfo.currentStatus = getCurrentFileStatus(fileHistory.id);
                ptf.log("  │                  │    ↳ ❌ Lỗi xử lý: " + e.getMessage(), ProgressTracker.LogLevel.ERROR);
            }
            report.files.add(fileInfo);
        }

        // ── In footer bảng + tổng kết ──
        ptf.log("  └──────────────────┴──────────────────────────────────────────", ProgressTracker.LogLevel.INFO);
        ptf.log(String.format("  📊 File tổng kết: %d tổng | ✅ %d có | 📂 %d trong subfolder | ❌ %d thiếu",
                totalFiles, presentFiles, inSubfolder, missingCount),
                missingCount > 0 ? ProgressTracker.LogLevel.WARNING : ProgressTracker.LogLevel.SUCCESS);
        return report;

    }

    /**
     * ⭐ NEW: Kiểm tra trạng thái hiện tại của file
     */
    private CurrentStatus getCurrentFileStatus(String fileId) {
        try {
            File file = driveService.files().get(fileId)
                    .setFields("id, name, trashed, explicitlyTrashed, parents, owners, mimeType")
                    .setSupportsAllDrives(true)
                    .execute();

            // Kiểm tra trashed
            Boolean trashed = file.getTrashed();
            Boolean explicitlyTrashed = file.getExplicitlyTrashed();

            if ((trashed != null && trashed) || (explicitlyTrashed != null && explicitlyTrashed)) {
                return new CurrentStatus("TRASHED", "🗑️ IN TRASH", "Trash", true);
            }

            // File còn tồn tại
            String location = "Unknown";
            if (file.getOwners() != null && !file.getOwners().isEmpty()) {
                location = file.getOwners().get(0).getEmailAddress();
            }

            if (file.getParents() != null && !file.getParents().isEmpty()) {
                try {
                    String parentId = file.getParents().get(0);
                    String parentName = getFolderNameCached(parentId);
                    location = parentName + " (" + location + ")";
                } catch (Exception e) {
                    // Ignore
                }
            }

            return new CurrentStatus("EXISTS", "✅ EXISTS", location, false);

        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            int statusCode = e.getStatusCode();
            if (statusCode == 404) {
                return new CurrentStatus("DELETED", "❌ PERMANENTLY DELETED", "N/A", false);
            } else if (statusCode == 403) {
                return new CurrentStatus("NO_ACCESS", "🔒 NO ACCESS / DELETED", "N/A", false);
            } else {
                return new CurrentStatus("ERROR", "⚠️ ERROR " + statusCode, "N/A", false);
            }
        } catch (Exception e) {
            return new CurrentStatus("ERROR", "⚠️ ERROR: " + e.getMessage(), "N/A", false);
        }
    }

    /**
     * ⭐ NEW: Get folder name với cache
     */
    private String getFolderNameCached(String folderId) {
        if (folderNameCache.containsKey(folderId)) {
            return folderNameCache.get(folderId);
        }

        try {
            File folder = driveService.files().get(folderId)
                    .setFields("name")
                    .setSupportsAllDrives(true)
                    .execute();

            String folderName = folder.getName();
            folderNameCache.put(folderId, folderName);
            return folderName;
        } catch (Exception e) {
            return folderId;
        }
    }

    private MoveResult findAndMoveFileWithResult(FileHistory file, String targetFolderId, String targetFolderPath,
            String userEmail, Set<String> subfolderIds) {
        MoveResult result = new MoveResult();
        result.success = false;
        result.reason = "";
        result.movedFrom = "";

        ProgressTracker pt = ProgressTracker.getInstance();

        // ── Vòng 1: Tìm trong Drive của user hiện tại ─────────────────────────
        File fileLocation = findFileById(file.id);
        if (fileLocation != null) {
            pt.log("    ✓ Tìm thấy file trong Drive của " + userEmail, ProgressTracker.LogLevel.INFO);
            return handleFoundFile(file.id, fileLocation, userEmail, targetFolderId, targetFolderPath, subfolderIds,
                    result, null);
        }

        // ── Vòng 2: Reports API → tìm owner qua audit log toàn tổ chức ───────
        // Giống Admin Console: tra cứu file ID trong Drive log events để biết owner là
        // ai
        String adminEmail = Config.getAdminEmail();
        pt.log("    🔍 Vòng 1 không thấy → hỏi Reports API tìm owner của file ID: " + file.id,
                ProgressTracker.LogLevel.DETAIL);
        String ownerEmail = findOwnerViaReportsApi(file.id, adminEmail);
        if (ownerEmail != null && !ownerEmail.isBlank()) {
            pt.log("    📋 Reports API → owner: " + ownerEmail, ProgressTracker.LogLevel.INFO);
            if (!ownerEmail.equals(userEmail)) {
                try {
                    Drive ownerDrive = createDriveServiceForUserWithRetry(ownerEmail);
                    fileLocation = ownerDrive.files().get(file.id)
                            .setFields("id, name, parents, trashed, mimeType, owners, driveId")
                            .setSupportsAllDrives(true)
                            .execute();
                    pt.log("    ✅ Tìm thấy qua owner (" + ownerEmail + "): " + fileLocation.getName(),
                            ProgressTracker.LogLevel.SUCCESS);
                    return handleFoundFile(file.id, fileLocation, userEmail, targetFolderId, targetFolderPath,
                            subfolderIds, result, ownerEmail);
                } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                    pt.log("    ⚠️  Owner " + ownerEmail + " không truy cập được (" + e.getStatusCode() + ")",
                            ProgressTracker.LogLevel.WARNING);
                } catch (Exception e) {
                    pt.log("    ⚠️  Lỗi khi truy cập Drive của owner " + ownerEmail + ": " + e.getMessage(),
                            ProgressTracker.LogLevel.WARNING);
                    // ── Vòng 2b: invalid_grant / user không tồn tại trong domain ──
                    // Ví dụ: quydt@sappedu.enterprise.io.vn → tìm quydt@* trong tổ chức
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    boolean isInvalidUser = msg.contains("invalid_grant") || msg.contains("Invalid email")
                            || msg.contains("User ID") || msg.contains("400");
                    if (isInvalidUser) {
                        String username = ownerEmail.contains("@")
                                ? ownerEmail.substring(0, ownerEmail.indexOf('@'))
                                : "";
                        if (!username.isBlank()) {
                            pt.log("    🔄 Vòng 2b: tìm user có username '" + username + "' trong tổ chức...",
                                    ProgressTracker.LogLevel.DETAIL);
                            List<String> sameUsernameList = Config.getAllUsersForSearch().stream()
                                    .filter(u -> u != null && u.startsWith(username + "@")
                                            && !u.equalsIgnoreCase(ownerEmail))
                                    .collect(java.util.stream.Collectors.toList());
                            if (sameUsernameList.isEmpty()) {
                                pt.log("    ℹ️  Không tìm thấy user nào có username '" + username + "' trong tổ chức",
                                        ProgressTracker.LogLevel.DETAIL);
                            }
                            for (String altEmail : sameUsernameList) {
                                pt.log("    🔄 Vòng 2b: thử " + altEmail, ProgressTracker.LogLevel.DETAIL);
                                try {
                                    Drive altDrive = createDriveServiceForUserWithRetry(altEmail);
                                    fileLocation = altDrive.files().get(file.id)
                                            .setFields("id, name, parents, trashed, mimeType, owners, driveId")
                                            .setSupportsAllDrives(true)
                                            .execute();
                                    pt.log("    ✅ Tìm thấy qua " + altEmail + ": " + fileLocation.getName(),
                                            ProgressTracker.LogLevel.SUCCESS);
                                    return handleFoundFile(file.id, fileLocation, userEmail, targetFolderId,
                                            targetFolderPath, subfolderIds, result, altEmail);
                                } catch (Exception altEx) {
                                    pt.log("    ⬝ " + altEmail + " không có file này", ProgressTracker.LogLevel.DETAIL);
                                }
                            }
                        }
                    }
                }
            } else {
                pt.log("    ℹ️  Owner trùng với user hiện tại → file đã bị xóa khỏi Drive",
                        ProgressTracker.LogLevel.DETAIL);
            }
        } else {
            pt.log("    ⚠️  Reports API không có log cho file này → không xác định được owner",
                    ProgressTracker.LogLevel.WARNING);
        }

        // ── Vòng 3: Quét toàn bộ allUsersForSearch (giống findAndMoveFolderWithResult)
        // ──
        List<String> allUsers = Config.getAllUsersForSearch();
        if (!allUsers.isEmpty()) {
            pt.log("    🔍 Vòng 3: Quét toàn bộ " + allUsers.size() + " users trong tổ chức...",
                    ProgressTracker.LogLevel.DETAIL);
            for (String otherUserEmail : allUsers) {
                if (otherUserEmail.equals(userEmail))
                    continue;
                try {
                    Drive userDriveService = createDriveServiceForUserWithRetry(otherUserEmail);
                    try {
                        File candidate = userDriveService.files().get(file.id)
                                .setFields("id, name, parents, trashed, mimeType, owners, driveId")
                                .setSupportsAllDrives(true)
                                .execute();
                        if (candidate != null) {
                            pt.log("    ✓ Vòng 3 tìm thấy trong Drive của: " + otherUserEmail,
                                    ProgressTracker.LogLevel.INFO);
                            return handleFoundFile(file.id, candidate, userEmail, targetFolderId, targetFolderPath,
                                    subfolderIds, result, otherUserEmail);
                        }
                    } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException gje) {
                        int sc = gje.getStatusCode();
                        if (sc == 404 || sc == 403) {
                            // Không có → tiếp tục
                        } else {
                            pt.log("    ⚠️  Vòng 3 [" + otherUserEmail + "] HTTP " + sc,
                                    ProgressTracker.LogLevel.DETAIL);
                        }
                    }
                } catch (Exception e) {
                    // invalid_grant hoặc lỗi khác → bỏ qua, tiếp tục user tiếp theo
                    String em = e.getMessage() != null ? e.getMessage() : "";
                    if (!em.contains("invalid_grant") && !em.contains("Invalid email")) {
                        pt.log("    ⚠️  Vòng 3 [" + otherUserEmail + "] lỗi: " + e.getClass().getSimpleName(),
                                ProgressTracker.LogLevel.DETAIL);
                    }
                }
            }
            pt.log("    ❌ Vòng 3: Đã quét " + allUsers.size() + " users — không tìm thấy file",
                    ProgressTracker.LogLevel.WARNING);
        }

        result.reason = "Không tìm thấy file sau 3 vòng tìm kiếm"
                + (ownerEmail != null ? " (Reports API owner: " + ownerEmail + ")" : " (Reports API: không có log)");
        result.movedFrom = "-";
        return result;
    }

    private MoveResult handleFoundFile(String fileId, File fileLocation, String userEmail,
            String targetFolderId, String targetFolderPath, Set<String> subfolderIds, MoveResult result,
            String finderEmail) {

        ProgressTracker pt = ProgressTracker.getInstance();

        // ── Cross-user registry: đã recover ở user khác → bỏ qua ─────────────
        // Ngăn vòng lặp: UserA recover file X về folderA → UserB thấy X "Thiếu"
        // trong folderB → định move X từ folderA sang folderB (undo recovery của A).
        if (globalRecoveredIds.contains(fileId)) {
            result.success = true;
            result.reason = "Đã recover ở user khác (cross-user registry) — bỏ qua";
            result.movedFrom = "-";
            pt.log("    ⏭️  File ID đã có trong cross-user registry → không move lại", ProgressTracker.LogLevel.DETAIL);
            return result;
        }

        // ── Trong Trash → KHÔNG move, chỉ báo cáo ─────────────────────────────
        if (fileLocation.getTrashed() != null && fileLocation.getTrashed()) {
            String ownerInfo = (fileLocation.getOwners() != null && !fileLocation.getOwners().isEmpty())
                    ? fileLocation.getOwners().get(0).getEmailAddress()
                    : userEmail;
            result.inTrash = true; // ← signal cho caller
            result.reason = "File đang trong TRASH của " + ownerInfo;
            result.movedFrom = "Trash (" + ownerInfo + ")";
            pt.log("    🗑️  File trong TRASH của: " + ownerInfo + " — bỏ qua, không move",
                    ProgressTracker.LogLevel.WARNING);
            return result;
        }

        // ── Đang trong subfolder hoặc đúng folder rồi → bỏ qua ─────────────────
        if (fileLocation.getParents() != null) {
            if (fileLocation.getParents().stream().anyMatch(p -> subfolderIds.contains(p))) {
                result.reason = "Trong subfolder";
                result.movedFrom = "Subfolder";
                pt.log("    ⏭️  File đang trong SUBFOLDER, bỏ qua", ProgressTracker.LogLevel.DETAIL);
                return result;
            }
            if (fileLocation.getParents().contains(targetFolderId)) {
                result.success = true;
                result.reason = "Đã trong folder";
                result.movedFrom = targetFolderPath;
                pt.log("    ✓ File đã nằm trong target folder", ProgressTracker.LogLevel.DETAIL);
                return result;
            }
        }

        // ── Sibling check: file đang trong folder là SIBLING của target → bỏ qua ──
        // Tình huống: file F trong folder C, C là sibling của target B (cùng parent A).
        // Activity API trả về stale history → code tìm F, thấy F trong C → định move
        // sang B.
        // Nếu C và B cùng parent A → F thuộc về C hợp lệ, KHÔNG move.
        //
        // A ─┬─ B (targetFolder) ← đang xử lý
        // └─ C (fileLocation.parent) ← F đang ở đây
        // └─ F (file)
        //
        if (fileLocation.getParents() != null) {
            try {
                File targetFolderMeta = driveService.files().get(targetFolderId)
                        .setFields("parents")
                        .setSupportsAllDrives(true)
                        .execute();
                if (targetFolderMeta.getParents() != null) {
                    for (String fileParentId : fileLocation.getParents()) {
                        if (fileParentId.equals(targetFolderId))
                            continue; // đã check ở trên
                        if (subfolderIds.contains(fileParentId))
                            continue; // đã check ở trên
                        // Lấy parent của folder chứa file → so sánh với parent của target
                        try {
                            File fileParentMeta = driveService.files().get(fileParentId)
                                    .setFields("parents")
                                    .setSupportsAllDrives(true)
                                    .execute();
                            if (fileParentMeta.getParents() != null &&
                                    fileParentMeta.getParents().stream()
                                            .anyMatch(p -> targetFolderMeta.getParents().contains(p))) {
                                result.reason = "File trong SIBLING folder của target — không move";
                                result.movedFrom = "-";
                                pt.log("    ⏭️  File trong sibling folder → bỏ qua, không move vào target",
                                        ProgressTracker.LogLevel.DETAIL);
                                return result;
                            }
                        } catch (Exception ignored) {
                            // Không lấy được parent folder → bỏ qua check này, tiếp tục move
                        }
                    }
                }
            } catch (Exception ex) {
                pt.log("    ⚠️  Không check được sibling file: " + ex.getMessage(), ProgressTracker.LogLevel.DETAIL);
            }
        }

        String ownerEmail = (fileLocation.getOwners() != null && !fileLocation.getOwners().isEmpty())
                ? fileLocation.getOwners().get(0).getEmailAddress()
                : userEmail;

        // ── Lấy parents (re-fetch nếu null do impersonation limit) ──────────────
        List<String> resolvedParents = fileLocation.getParents();
        // Dùng driveId để phát hiện file trong Shared Drive (KHÔNG dùng "0A" prefix vì
        // My Drive root cũng bắt đầu bằng "0A" → nhầm lẫn)
        boolean isInSharedDrive = fileLocation.getDriveId() != null
                && !fileLocation.getDriveId().isBlank();
        if (isInSharedDrive) {
            pt.log("    ℹ️  File đang trong Shared Drive: " + fileLocation.getDriveId()
                    + " — sẽ thử move, cần quyền Organizer", ProgressTracker.LogLevel.DETAIL);
        }
        if (resolvedParents == null || resolvedParents.isEmpty()) {
            pt.log("    ⚠️  Parents null → re-fetch bằng owner: " + ownerEmail, ProgressTracker.LogLevel.DETAIL);
            try {
                Drive ownerDrive = createDriveServiceForUserWithRetry(ownerEmail);
                File refetched = ownerDrive.files().get(fileId)
                        .setFields("id, name, parents, driveId")
                        .setSupportsAllDrives(true)
                        .execute();
                resolvedParents = refetched.getParents();
                pt.log("    ✓ Re-fetch OK, parents: " + resolvedParents, ProgressTracker.LogLevel.DETAIL);
            } catch (Exception ex) {
                pt.log("    ⚠️  Re-fetch thất bại: " + ex.getMessage(), ProgressTracker.LogLevel.WARNING);
            }
        }

        String parentName = getParentFolderName(fileLocation);
        result.movedFrom = "📁 " + parentName + " | Drive của: " + ownerEmail;
        pt.log("    📂 File tại: '" + parentName + "' (" + ownerEmail + ") → Move về: " + targetFolderPath,
                ProgressTracker.LogLevel.INFO);

        // ── Thứ tự thử: finder → owner → admin → target user ───────────────────
        java.util.LinkedHashMap<String, String> candidates = new java.util.LinkedHashMap<>();
        String adminEmail = Config.getAdminEmail();
        // 1. Finder trước (đã tìm thấy file → có quyền truy cập source)
        if (finderEmail != null && !finderEmail.isBlank())
            candidates.put(finderEmail, "finder");
        // 2. Owner (chủ sở hữu file nguồn)
        if (ownerEmail != null && !candidates.containsKey(ownerEmail))
            candidates.put(ownerEmail, "owner");
        // 3. Admin
        if (adminEmail != null && !adminEmail.isBlank() && !candidates.containsKey(adminEmail))
            candidates.put(adminEmail, "admin");
        // 4. Target user (scanned user — chủ targetFolder)
        if (userEmail != null && !candidates.containsKey(userEmail))
            candidates.put(userEmail, "target user");

        String lastReason = "Không có candidate nào";
        for (java.util.Map.Entry<String, String> entry : candidates.entrySet()) {
            String candidateEmail = entry.getKey();
            String role = entry.getValue();
            try {
                Drive candidateDrive = createDriveServiceForUserWithRetry(candidateEmail);
                MoveResult mr = moveFileToFolder(fileId, resolvedParents, targetFolderId, candidateDrive);
                if (mr.success) {
                    result.success = true;
                    result.actuallyMoved = true;
                    result.reason = "Success (via " + role + ": " + candidateEmail + ")";
                    // ⭐ Đăng ký vào cross-user registry: ngăn user khác move FILE này lại
                    globalRecoveredIds.add(fileId);
                    pt.log("    ✅ Move FILE OK via " + role + " (" + candidateEmail + "): " + targetFolderPath,
                            ProgressTracker.LogLevel.SUCCESS);
                    return result;
                }
                lastReason = mr.reason;
                pt.log("    ⚠️  " + role + " (" + candidateEmail + ") thất bại: " + mr.reason,
                        ProgressTracker.LogLevel.DETAIL);
            } catch (Exception e) {
                lastReason = e.getMessage();
                pt.log("    ⚠️  " + role + " (" + candidateEmail + ") exception: " + e.getMessage(),
                        ProgressTracker.LogLevel.DETAIL);
            }
        }

        // ── Fallback cuối: grant write tạm thời cho owner/finder → họ move → revoke ──
        // Cần khi: owner của source không thấy targetFolder (404 khi thử trực tiếp),
        // nhưng userEmail (chủ targetFolder) có thể grant permission cho họ.
        String grantTarget = (finderEmail != null && !finderEmail.isBlank()) ? finderEmail : ownerEmail;
        if (grantTarget != null && !grantTarget.equals(userEmail)) {
            pt.log("    🔑 Thử grant write tạm thời cho " + grantTarget + " trên targetFolder...",
                    ProgressTracker.LogLevel.DETAIL);
            MoveResult tempResult = tryMoveWithTemporaryShare(
                    fileId, resolvedParents, targetFolderId, grantTarget, userEmail);
            if (tempResult.success) {
                result.success = true;
                result.actuallyMoved = true;
                result.reason = tempResult.reason;
                pt.log("    ✅ Move FILE OK via temporary share → " + targetFolderPath,
                        ProgressTracker.LogLevel.SUCCESS);
                return result;
            }
            lastReason = tempResult.reason;
        }

        result.reason = "Move thất bại: " + lastReason;
        return result;
    }

    // ============================================
    // FOLDER RECOVERY METHODS
    // ============================================

    /**
     * Find a missing subfolder and move it back to the target parent folder.
     * NEVER creates a new folder - if not found, reports "Not Found".
     */
    private MoveResult findAndMoveFolderWithResult(FileHistory folderHistory, String targetFolderId,
            String targetFolderPath, String userEmail) {
        MoveResult result = new MoveResult();
        result.success = false;
        result.reason = "";
        result.movedFrom = "";

        ProgressTracker pt = ProgressTracker.getInstance();
        // Safety guard: không move folder vào chính nó
        if (folderHistory.id.equals(targetFolderId)) {
            result.success = true;
            result.reason = "Folder đã ở đúng vị trí (trùng ID với target)";
            result.movedFrom = targetFolderPath;
            pt.log("    ⏭️  Bỏ qua: folder trùng ID với target", ProgressTracker.LogLevel.DETAIL);
            return result;
        }

        // ── Vòng 1: Tìm trong Drive của user hiện tại ─────────────────────────
        File foundFolder = findFolderById(folderHistory.id, driveService);
        if (foundFolder != null) {
            pt.log("    ✓ Tìm thấy folder trong Drive của " + userEmail, ProgressTracker.LogLevel.INFO);
            return handleFoundFolder(folderHistory.id, foundFolder, userEmail, targetFolderId, targetFolderPath, result,
                    null);
        }

        // ── Vòng 2: Reports API → tìm owner qua audit log toàn tổ chức ───────
        String adminEmail = Config.getAdminEmail();
        pt.log("    🔍 Vòng 1 không thấy → hỏi Reports API tìm owner của folder ID: " + folderHistory.id,
                ProgressTracker.LogLevel.DETAIL);
        String ownerEmail = findOwnerViaReportsApi(folderHistory.id, adminEmail);
        if (ownerEmail != null && !ownerEmail.isBlank()) {
            pt.log("    📋 Reports API → owner: " + ownerEmail, ProgressTracker.LogLevel.INFO);
            if (!ownerEmail.equals(userEmail)) {
                try {
                    Drive ownerDrive = createDriveServiceForUserWithRetry(ownerEmail);
                    foundFolder = ownerDrive.files().get(folderHistory.id)
                            .setFields("id, name, parents, trashed, mimeType, owners, driveId")
                            .setSupportsAllDrives(true)
                            .execute();
                    pt.log("    ✅ Tìm thấy qua owner (" + ownerEmail + "): " + foundFolder.getName(),
                            ProgressTracker.LogLevel.SUCCESS);
                    return handleFoundFolder(folderHistory.id, foundFolder, userEmail, targetFolderId, targetFolderPath,
                            result, ownerEmail);
                } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                    pt.log("    ⚠️  Owner " + ownerEmail + " không truy cập được (" + e.getStatusCode() + ")",
                            ProgressTracker.LogLevel.WARNING);
                } catch (Exception e) {
                    pt.log("    ⚠️  Lỗi khi truy cập Drive của owner " + ownerEmail + ": " + e.getMessage(),
                            ProgressTracker.LogLevel.WARNING);
                    // ── Vòng 2b: invalid_grant / user không tồn tại trong domain ──
                    // Tìm trong allUsersForSearch user nào có cùng username
                    // Ví dụ: tramhb@sappedu.enterprise.io.vn → tìm tramhb@* trong tổ chức →
                    // tramhb@sapp.edu.vn
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    boolean isInvalidUser = msg.contains("invalid_grant") || msg.contains("Invalid email")
                            || msg.contains("User ID") || msg.contains("400");
                    if (isInvalidUser) {
                        String username = ownerEmail.contains("@")
                                ? ownerEmail.substring(0, ownerEmail.indexOf('@'))
                                : "";
                        if (!username.isBlank()) {
                            pt.log("    🔄 Vòng 2b: tìm user có username '" + username + "' trong tổ chức...",
                                    ProgressTracker.LogLevel.DETAIL);
                            List<String> sameUsernameList = Config.getAllUsersForSearch().stream()
                                    .filter(u -> u != null && u.startsWith(username + "@")
                                            && !u.equalsIgnoreCase(ownerEmail))
                                    .collect(java.util.stream.Collectors.toList());
                            if (sameUsernameList.isEmpty()) {
                                pt.log("    ℹ️  Không tìm thấy user nào có username '" + username + "' trong tổ chức",
                                        ProgressTracker.LogLevel.DETAIL);
                            }
                            for (String altEmail : sameUsernameList) {
                                pt.log("    🔄 Vòng 2b: thử " + altEmail, ProgressTracker.LogLevel.DETAIL);
                                try {
                                    Drive altDrive = createDriveServiceForUserWithRetry(altEmail);
                                    foundFolder = altDrive.files().get(folderHistory.id)
                                            .setFields("id, name, parents, trashed, mimeType, owners, driveId")
                                            .setSupportsAllDrives(true)
                                            .execute();
                                    pt.log("    ✅ Tìm thấy qua " + altEmail + ": " + foundFolder.getName(),
                                            ProgressTracker.LogLevel.SUCCESS);
                                    return handleFoundFolder(folderHistory.id, foundFolder, userEmail, targetFolderId,
                                            targetFolderPath, result, altEmail);
                                } catch (Exception altEx) {
                                    pt.log("    ⬝ " + altEmail + " không có folder này",
                                            ProgressTracker.LogLevel.DETAIL);
                                }
                            }
                        }
                    }
                }
            } else {
                pt.log("    ℹ️  Owner trùng với user hiện tại → folder đã bị xóa khỏi Drive",
                        ProgressTracker.LogLevel.DETAIL);
            }
        } else {
            pt.log("    ⚠️  Reports API không có log cho folder này → không xác định được owner",
                    ProgressTracker.LogLevel.WARNING);
        }

        // ── Vòng 3: Quét toàn bộ allUsersForSearch (giống code cũ) ───────────
        List<String> allUsers = Config.getAllUsersForSearch();
        if (!allUsers.isEmpty()) {
            pt.log("    🔍 Vòng 3: Quét toàn bộ " + allUsers.size() + " users trong tổ chức...",
                    ProgressTracker.LogLevel.DETAIL);
            for (String otherUserEmail : allUsers) {
                if (otherUserEmail.equals(userEmail))
                    continue;
                try {
                    Drive userDriveService = createDriveServiceForUserWithRetry(otherUserEmail);
                    try {
                        File candidate = userDriveService.files().get(folderHistory.id)
                                .setFields("id, name, parents, trashed, mimeType, owners, driveId")
                                .setSupportsAllDrives(true)
                                .execute();
                        if (candidate != null) {
                            pt.log("    ✓ Vòng 3 tìm thấy trong Drive của: " + otherUserEmail,
                                    ProgressTracker.LogLevel.INFO);
                            return handleFoundFolder(folderHistory.id, candidate, userEmail, targetFolderId,
                                    targetFolderPath, result, otherUserEmail);
                        }
                    } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException gje) {
                        int sc = gje.getStatusCode();
                        if (sc == 404 || sc == 403) {
                            // Không có → tiếp tục
                        } else {
                            pt.log("    ⚠️  Vòng 3 [" + otherUserEmail + "] HTTP " + sc,
                                    ProgressTracker.LogLevel.DETAIL);
                        }
                    }
                } catch (Exception e) {
                    // invalid_grant hoặc lỗi khác → bỏ qua, tiếp tục user tiếp theo
                    String em = e.getMessage() != null ? e.getMessage() : "";
                    if (!em.contains("invalid_grant") && !em.contains("Invalid email")) {
                        pt.log("    ⚠️  Vòng 3 [" + otherUserEmail + "] lỗi: " + e.getClass().getSimpleName(),
                                ProgressTracker.LogLevel.DETAIL);
                    }
                }
            }
            pt.log("    ❌ Vòng 3: Đã quét " + allUsers.size() + " users — không tìm thấy folder",
                    ProgressTracker.LogLevel.WARNING);
        }

        result.reason = "Không tìm thấy folder sau 3 vòng tìm kiếm"
                + (ownerEmail != null ? " (Reports API owner: " + ownerEmail + ")" : " (Reports API: không có log)");
        result.movedFrom = "-";
        return result;
    }

    /**
     * Tạo danh sách email thay thế bằng cách đổi domain của ownerEmail
     * sang tất cả domain của các user trong allUsersForSearch.
     * Không còn dùng trực tiếp — logic này đã được thay bằng username-prefix search
     * trong vòng 2b.
     * Giữ lại để tương thích nếu cần dùng lại.
     */
    private List<String> buildAlternateDomainEmails(String ownerEmail, List<String> allUsers) {
        List<String> result = new ArrayList<>();
        if (ownerEmail == null || !ownerEmail.contains("@"))
            return result;
        String username = ownerEmail.substring(0, ownerEmail.indexOf('@'));
        Set<String> knownDomains = new LinkedHashSet<>();
        for (String u : allUsers) {
            if (u != null && u.contains("@")) {
                String domain = u.substring(u.indexOf('@') + 1);
                if (!domain.isEmpty())
                    knownDomains.add(domain);
            }
        }
        String originalDomain = ownerEmail.substring(ownerEmail.indexOf('@') + 1);
        for (String domain : knownDomains) {
            if (!domain.equals(originalDomain)) {
                result.add(username + "@" + domain);
            }
        }
        return result;
    }

    private MoveResult handleFoundFolder(String folderId, File foundFolder, String userEmail,
            String targetFolderId, String targetFolderPath, MoveResult result, String finderEmail) {

        ProgressTracker pt = ProgressTracker.getInstance();

        // ── Cross-user registry: đã recover ở user khác → bỏ qua ─────────────
        // Ngăn vòng lặp: UserA recover X về folderA → UserB thấy X "Thiếu" trong
        // folderB → định move X từ folderA sang folderB (undo recovery của A).
        if (globalRecoveredIds.contains(folderId)) {
            result.success = true;
            result.reason = "Đã recover ở user khác (cross-user registry) — bỏ qua";
            result.movedFrom = "-";
            pt.log("    ⏭️  Folder ID đã có trong cross-user registry → không move lại",
                    ProgressTracker.LogLevel.DETAIL);
            return result;
        }

        // ── Trong Trash → KHÔNG move, chỉ báo cáo ─────────────────────────────
        if (foundFolder.getTrashed() != null && foundFolder.getTrashed()) {
            String ownerInfo = (foundFolder.getOwners() != null && !foundFolder.getOwners().isEmpty())
                    ? foundFolder.getOwners().get(0).getEmailAddress()
                    : userEmail;
            result.inTrash = true; // ← signal cho caller
            result.reason = "Folder đang trong TRASH của " + ownerInfo;
            result.movedFrom = "Trash (" + ownerInfo + ")";
            pt.log("    🗑️  Folder trong TRASH của: " + ownerInfo + " — bỏ qua, không move",
                    ProgressTracker.LogLevel.WARNING);
            return result;
        }

        // ── Đã đúng chỗ → bỏ qua ──────────────────────────────────────────────
        if (foundFolder.getParents() != null && foundFolder.getParents().contains(targetFolderId)) {
            result.success = true;
            result.reason = "Đã trong folder";
            result.movedFrom = targetFolderPath;
            pt.log("    ✓ Folder đã nằm đúng chỗ", ProgressTracker.LogLevel.DETAIL);
            return result;
        }

        // ── Đang là grandchild (nằm sâu trong subtree của target) → bỏ qua ────
        // Tương tự handleFoundFile: kiểm tra parent có phải subfolder của target không.
        // Tình huống: folder X từng là direct child của B, sau đó được move vào C
        // (C là subfolder của B). Activity cũ vẫn báo X thiếu trong B, nhưng thực ra
        // X đang ở đúng chỗ — chỉ sâu hơn 1 cấp. KHÔNG move X từ C lên thẳng B.
        if (foundFolder.getParents() != null) {
            try {
                Set<String> descendantIds = getAllSubfolderIds(targetFolderId, userEmail);
                if (foundFolder.getParents().stream().anyMatch(descendantIds::contains)) {
                    result.success = true;
                    result.reason = "Đang trong subfolder con của target (grandchild) — không cần move";
                    result.movedFrom = targetFolderPath + " (subfolder con)";
                    pt.log("    ⏭️  Folder đang là grandchild của target → bỏ qua, không move",
                            ProgressTracker.LogLevel.DETAIL);
                    return result;
                }
            } catch (Exception ex) {
                pt.log("    ⚠️  Không kiểm tra được subtree: " + ex.getMessage(), ProgressTracker.LogLevel.DETAIL);
                // Nếu không check được → tiếp tục logic move bình thường (tránh bỏ sót)
            }
        }

        // ── Sibling check: foundFolder và targetFolder cùng parent → KHÔNG move ──
        // Root cause: setAncestorName() trả về TOÀN BỘ history, kể cả khi folder C
        // chỉ đi qua B tạm thời (transit). Code tìm C đang ở A, B cũng ở A → C là
        // SIBLING của B chứ không phải bị mất khỏi B. KHÔNG được move C vào B.
        //
        // Ví dụ:
        // A ─┬─ B (targetFolder) ← đang xử lý
        // └─ C (foundFolder) ← Activity cũ báo C từng ở trong B (sai)
        //
        // C.parents = [A], targetFolder.parents = [A] → cùng parent A → siblings → bỏ
        // qua.
        if (foundFolder.getParents() != null) {
            try {
                File targetFolderMeta = driveService.files().get(targetFolderId)
                        .setFields("parents")
                        .setSupportsAllDrives(true)
                        .execute();
                if (targetFolderMeta.getParents() != null) {
                    boolean areSiblings = foundFolder.getParents().stream()
                            .anyMatch(p -> targetFolderMeta.getParents().contains(p));
                    if (areSiblings) {
                        result.success = true;
                        result.reason = "Folder là SIBLING của target (cùng parent) — không move vào target";
                        result.movedFrom = "-";
                        pt.log("    ⏭️  Folder là SIBLING của target → bỏ qua, không move C vào B",
                                ProgressTracker.LogLevel.DETAIL);
                        return result;
                    }
                }
            } catch (Exception ex) {
                pt.log("    ⚠️  Không kiểm tra được sibling relationship: " + ex.getMessage(),
                        ProgressTracker.LogLevel.DETAIL);
                // Không check được → tiếp tục (tránh bỏ sót file thực sự cần move)
            }
        }

        String ownerEmail = (foundFolder.getOwners() != null && !foundFolder.getOwners().isEmpty())
                ? foundFolder.getOwners().get(0).getEmailAddress()
                : userEmail;

        // ── Lấy parents (re-fetch nếu null do impersonation limit) ──────────────
        List<String> resolvedParents = foundFolder.getParents();
        // Dùng driveId để phát hiện folder trong Shared Drive (KHÔNG dùng "0A" prefix)
        boolean isInSharedDrive = foundFolder.getDriveId() != null
                && !foundFolder.getDriveId().isBlank();
        if (isInSharedDrive) {
            pt.log("    ℹ️  Folder đang trong Shared Drive: " + foundFolder.getDriveId()
                    + " — sẽ thử move, cần quyền Organizer", ProgressTracker.LogLevel.DETAIL);
        }
        if (resolvedParents == null || resolvedParents.isEmpty()) {
            pt.log("    ⚠️  Parents null → re-fetch bằng owner: " + ownerEmail, ProgressTracker.LogLevel.DETAIL);
            try {
                Drive ownerDrive = createDriveServiceForUserWithRetry(ownerEmail);
                File refetched = ownerDrive.files().get(folderId)
                        .setFields("id, name, parents, driveId")
                        .setSupportsAllDrives(true)
                        .execute();
                resolvedParents = refetched.getParents();
                pt.log("    ✓ Re-fetch OK, parents: " + resolvedParents, ProgressTracker.LogLevel.DETAIL);
            } catch (Exception ex) {
                pt.log("    ⚠️  Re-fetch thất bại: " + ex.getMessage(), ProgressTracker.LogLevel.WARNING);
            }
        }

        String parentName = getParentFolderName(foundFolder);
        result.movedFrom = "📁 " + parentName + " | Drive của: " + ownerEmail;
        pt.log("    📂 Folder tại: '" + parentName + "' (" + ownerEmail + ") → Move về: " + targetFolderPath,
                ProgressTracker.LogLevel.INFO);

        // ── Thứ tự thử: finder → owner → admin → target user ───────────────────
        java.util.LinkedHashMap<String, String> candidates = new java.util.LinkedHashMap<>();
        String adminEmail = Config.getAdminEmail();
        // 1. Finder trước (đã tìm thấy folder → có quyền truy cập source)
        if (finderEmail != null && !finderEmail.isBlank())
            candidates.put(finderEmail, "finder");
        // 2. Owner (chủ sở hữu folder nguồn)
        if (ownerEmail != null && !candidates.containsKey(ownerEmail))
            candidates.put(ownerEmail, "owner");
        // 3. Admin
        if (adminEmail != null && !adminEmail.isBlank() && !candidates.containsKey(adminEmail))
            candidates.put(adminEmail, "admin");
        // 4. Target user (scanned user — chủ targetFolder)
        if (userEmail != null && !candidates.containsKey(userEmail))
            candidates.put(userEmail, "target user");

        String lastReason = "Không có candidate nào";
        for (java.util.Map.Entry<String, String> entry : candidates.entrySet()) {
            String candidateEmail = entry.getKey();
            String role = entry.getValue();
            try {
                Drive candidateDrive = createDriveServiceForUserWithRetry(candidateEmail);
                MoveResult mr = moveFileToFolder(folderId, resolvedParents, targetFolderId, candidateDrive);
                if (mr.success) {
                    result.success = true;
                    result.actuallyMoved = true;
                    result.reason = "Success (via " + role + ": " + candidateEmail + ")";
                    pt.log("    ✅ Move FOLDER OK via " + role + " (" + candidateEmail + "): " + targetFolderPath,
                            ProgressTracker.LogLevel.SUCCESS);
                    // ⭐ Đăng ký vào cross-user registry: ngăn user khác move lại
                    globalRecoveredIds.add(folderId);
                    return result;
                }
                lastReason = mr.reason;
                pt.log("    ⚠️  " + role + " (" + candidateEmail + ") thất bại: " + mr.reason,
                        ProgressTracker.LogLevel.DETAIL);
            } catch (Exception e) {
                lastReason = e.getMessage();
                pt.log("    ⚠️  " + role + " (" + candidateEmail + ") exception: " + e.getMessage(),
                        ProgressTracker.LogLevel.DETAIL);
            }
        }

        // ── Fallback cuối: grant write tạm thời cho owner/finder → họ move → revoke ──
        String grantTarget = (finderEmail != null && !finderEmail.isBlank()) ? finderEmail : ownerEmail;
        if (grantTarget != null && !grantTarget.equals(userEmail)) {
            pt.log("    🔑 Thử grant write tạm thời cho " + grantTarget + " trên targetFolder...",
                    ProgressTracker.LogLevel.DETAIL);
            MoveResult tempResult = tryMoveWithTemporaryShare(
                    folderId, resolvedParents, targetFolderId, grantTarget, userEmail);
            if (tempResult.success) {
                result.success = true;
                result.actuallyMoved = true;
                result.reason = tempResult.reason;
                pt.log("    ✅ Move FOLDER OK via temporary share → " + targetFolderPath,
                        ProgressTracker.LogLevel.SUCCESS);
                return result;
            }
            lastReason = tempResult.reason;
        }

        result.reason = "Move thất bại: " + lastReason;
        return result;
    }

    /**
     * ⭐ FALLBACK: Grant write permission tạm thời lên targetFolder cho
     * grantToEmail,
     * để grantToEmail (owner của source) có thể addParents vào targetFolder,
     * sau đó revoke permission.
     *
     * Dùng khi: finder/owner không thấy targetFolder (404 khi thử trực tiếp),
     * nhưng userEmail (chủ targetFolder) có thể grant permission.
     */
    private MoveResult tryMoveWithTemporaryShare(
            String fileId, List<String> resolvedParents,
            String targetFolderId, String grantToEmail, String targetFolderOwnerEmail) {

        MoveResult result = new MoveResult();
        result.success = false;
        ProgressTracker pt = ProgressTracker.getInstance();
        String permissionId = null;

        try {
            // BƯỚC 1: targetFolderOwner grant WRITER cho grantToEmail trên targetFolder
            Drive ownerDrive = createDriveServiceForUserWithRetry(targetFolderOwnerEmail);
            com.google.api.services.drive.model.Permission perm = new com.google.api.services.drive.model.Permission();
            perm.setType("user");
            perm.setRole("writer");
            perm.setEmailAddress(grantToEmail);

            com.google.api.services.drive.model.Permission created = ownerDrive.permissions()
                    .create(targetFolderId, perm)
                    .setSendNotificationEmail(false)
                    .setSupportsAllDrives(true)
                    .setFields("id")
                    .execute();
            permissionId = created.getId();
            pt.log("    🔑 Đã grant write tạm thời cho " + grantToEmail, ProgressTracker.LogLevel.DETAIL);

            // BƯỚC 2: grantToEmail move file/folder về targetFolder
            Drive grantDrive = createDriveServiceForUserWithRetry(grantToEmail);
            MoveResult mr = moveFileToFolder(fileId, resolvedParents, targetFolderId, grantDrive);
            if (mr.success) {
                result.success = true;
                result.actuallyMoved = true;
                result.reason = "Success (temporary share via " + grantToEmail + ")";
            } else {
                result.reason = "Temporary share move failed: " + mr.reason;
                pt.log("    ❌ Move qua temporary share thất bại: " + mr.reason, ProgressTracker.LogLevel.DETAIL);
            }

        } catch (Exception e) {
            result.reason = "Temporary share exception: " + e.getMessage();
            pt.log("    ❌ Lỗi temporary share: " + e.getMessage(), ProgressTracker.LogLevel.DETAIL);
        } finally {
            // BƯỚC 3: Luôn thu hồi permission (dù move thành công hay không)
            if (permissionId != null) {
                try {
                    Drive ownerDrive = createDriveServiceForUserWithRetry(targetFolderOwnerEmail);
                    ownerDrive.permissions().delete(targetFolderId, permissionId)
                            .setSupportsAllDrives(true)
                            .execute();
                    pt.log("    🔑 Đã thu hồi quyền tạm thời của " + grantToEmail, ProgressTracker.LogLevel.DETAIL);
                } catch (Exception ex) {
                    pt.log("    ⚠️  Không thu hồi được quyền tạm thời: " + ex.getMessage(),
                            ProgressTracker.LogLevel.WARNING);
                }
            }
        }

        return result;
    }

    private File findFileById(String fileId) {
        try {
            return driveService.files().get(fileId)
                    .setFields("id, name, parents, trashed, mimeType, owners, driveId")
                    .setSupportsAllDrives(true)
                    .execute();
        } catch (Exception e) {
            return null;
        }
    }

    private File findFolderById(String folderId, Drive driveService) {
        try {
            return driveService.files().get(folderId)
                    .setFields("id, name, parents, trashed, mimeType, owners, driveId")
                    .setSupportsAllDrives(true)
                    .execute();
        } catch (Exception e) {
            return null;
        }
    }

    private String getParentFolderName(File file) {
        if (file.getParents() == null || file.getParents().isEmpty())
            return "My Drive";
        String parentId = file.getParents().get(0);
        try {
            File parentFile = driveService.files().get(parentId)
                    .setFields("id, name")
                    .setSupportsAllDrives(true)
                    .execute();
            return parentFile.getName();
        } catch (Exception e) {
            return parentId;
        }
    }

    /**
     * Get IDs of immediate (direct) subfolders only - no recursion.
     */
    private Set<String> getDirectSubfolderIds(String parentId, String userEmail) throws IOException {
        Set<String> result = new HashSet<>();
        String pageToken = null;
        do {
            String query = "'" + parentId + "'"
                    + " in parents and mimeType='application/vnd.google-apps.folder' and trashed=false";
            FileList fl = driveService.files().list()
                    .setQ(query)
                    .setFields("nextPageToken, files(id)")
                    .setPageSize(1000)
                    .setPageToken(pageToken)
                    .execute();
            if (fl.getFiles() != null)
                fl.getFiles().forEach(f -> result.add(f.getId()));
            pageToken = fl.getNextPageToken();
        } while (pageToken != null);
        return result;
    }

    /**
     * Read activity for a folder and return history of DIRECT SUBFOLDERS
     * (DriveFolder targets only) - i.e., folders that were ever direct children.
     */
    private List<FileHistory> getDirectSubFoldersFromActivity(String folderId, String userEmail) throws IOException {
        Map<String, FileHistory> map = new HashMap<>();

        // ⭐ FIX: Gom toàn bộ activities từ tất cả pages TRƯỚC, rồi mới sort và process.
        // Lý do: Activity API trả về newest-first. Nếu process từng page riêng lẻ,
        // event
        // REMOVE (mới hơn, page 1) sẽ bị override bởi event ADD (cũ hơn, page 2) →
        // folder bị đánh nhầm là currentlyInFolder=true khi thực tế đã bị remove.
        // Giải pháp: Sort toàn bộ oldest-first (giống getFilesFromActivity) → đúng thứ
        // tự thời gian.
        List<com.google.api.services.driveactivity.v2.model.DriveActivity> allActivities = new ArrayList<>();
        String pageToken = null;
        do {
            com.google.api.services.driveactivity.v2.model.QueryDriveActivityRequest req = new com.google.api.services.driveactivity.v2.model.QueryDriveActivityRequest();
            req.setAncestorName("items/" + folderId);
            req.setPageSize(100);
            if (pageToken != null)
                req.setPageToken(pageToken);
            String folderFilter = buildActivityFilter();
            if (folderFilter != null && !folderFilter.isEmpty()) {
                req.setFilter(folderFilter);
            }

            // ⭐ FIX 429: dùng helper có semaphore + retry
            com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse resp = executeActivityQueryWithRetry(
                    req);

            if (resp.getActivities() != null) {
                allActivities.addAll(resp.getActivities());
            }
            pageToken = resp.getNextPageToken();
        } while (pageToken != null);

        // Sort oldest-first → event cũ hơn (ADD) được process trước, event mới hơn
        // (REMOVE) sau
        // → final state phản ánh đúng sự kiện gần nhất (same logic as
        // getFilesFromActivity)
        allActivities.sort((a, b) -> {
            String ta = a.getTimestamp() != null ? a.getTimestamp() : "";
            String tb = b.getTimestamp() != null ? b.getTimestamp() : "";
            return ta.compareTo(tb);
        });

        for (com.google.api.services.driveactivity.v2.model.DriveActivity activity : allActivities) {
            processActivityForFolders(activity, folderId, map);
        }

        return map.values().stream()
                // Include: folder từng ở đây (everInFolder), HOẶC folder bị DELETE khỏi subtree
                // (deletedFromSubtree=true → có thể là direct child bị xóa vào Trash)
                .filter(fh -> fh.everInFolder || fh.deletedFromSubtree)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * ⭐ FIX 429: Gọi Activity API với:
     * 1. Semaphore (1 permit) → chỉ 1 thread gọi tại một lúc (quota là per-user
     * per-minute)
     * 2. Exponential backoff retry tối đa 5 lần khi gặp RATE_LIMIT_EXCEEDED (429)
     */
    private com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse executeActivityQueryWithRetry(
            com.google.api.services.driveactivity.v2.model.QueryDriveActivityRequest req)
            throws IOException {

        int maxRetries = 5;
        long baseDelayMs = 2000; // 2 giây backoff cơ bản

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            // Acquire semaphore → chỉ 1 thread vào được
            try {
                activityApiSemaphore.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for Activity API slot", ie);
            }

            try {
                com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse resp = activityService
                        .activity().query(req).execute();
                // Thêm delay nhỏ giữa các call để tránh burst (300ms)
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return resp;

            } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                if (e.getStatusCode() == 429) {
                    long waitMs = baseDelayMs * (1L << attempt); // 2s, 4s, 8s, 16s, 32s
                    ProgressTracker.getInstance().log(
                            "  ⏳ Activity API 429 (attempt " + (attempt + 1) + "/" + maxRetries + ")" +
                                    " — chờ " + (waitMs / 1000) + "s rồi retry...",
                            ProgressTracker.LogLevel.WARNING);
                    if (attempt < maxRetries) {
                        try {
                            Thread.sleep(waitMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    } else {
                        // 429 hết retry → không crash, bỏ qua activity cho folder này
                        ProgressTracker.getInstance().log(
                                "  ⚠️  Activity API 429 hết retry — bỏ qua activity",
                                ProgressTracker.LogLevel.DETAIL);
                        return new com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse();
                    }
                } else {
                    // 403/404/other → KHÔNG crash, bỏ qua activity cho folder này
                    String detail = (e.getDetails() != null && e.getDetails().getMessage() != null)
                            ? e.getDetails().getMessage()
                            : e.getMessage();
                    ProgressTracker.getInstance().log(
                            "  ⚠️  Activity API HTTP " + e.getStatusCode() + " (" + detail + ") — bỏ qua",
                            ProgressTracker.LogLevel.DETAIL);
                    return new com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse();
                }
            } catch (Exception e) {
                // Network/IO/other lỗi → không crash, bỏ qua activity cho folder này
                ProgressTracker.getInstance().log(
                        "  ⚠️  Activity API lỗi: " + e.getClass().getSimpleName() + " — bỏ qua",
                        ProgressTracker.LogLevel.DETAIL);
                return new com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse();
            } finally {
                activityApiSemaphore.release(); // Luôn release dù thành công hay fail
            }
        }

        // Fallback (không bao giờ tới đây)
        return new com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse();
    }

    /**
     * ⭐ MỚI: Dùng Admin SDK Reports API (giống Admin Console → Drive log events)
     * để tìm owner của file/folder theo ID trong toàn bộ tổ chức.
     *
     * Thay thế vòng loop N users cũ → chỉ cần 1 API call để biết owner là ai.
     *
     * Reports API query: doc_id == fileId → trả về audit log events có chứa field
     * "owner"
     *
     * @return email của owner, hoặc null nếu không có log nào
     */
    private String findOwnerViaReportsApi(String fileId, String adminEmail) {
        ProgressTracker pt = ProgressTracker.getInstance();
        if (adminEmail == null || adminEmail.isBlank()) {
            pt.log("    ⚠️  Không có adminEmail → không thể dùng Reports API", ProgressTracker.LogLevel.DETAIL);
            return null;
        }
        try {
            // Tạo credentials impersonate admin với scope Reports API
            com.google.auth.oauth2.GoogleCredentials adminCreds;
            if (Config.isUseJsonFile()) {
                adminCreds = com.google.auth.oauth2.ServiceAccountCredentials
                        .fromStream(new java.io.FileInputStream(Config.getServiceAccountFile()))
                        .createScoped(java.util.List.of(
                                "https://www.googleapis.com/auth/admin.reports.audit.readonly"))
                        .createDelegated(adminEmail);
            } else {
                adminCreds = com.google.auth.oauth2.ServiceAccountCredentials
                        .fromStream(new java.io.ByteArrayInputStream(
                                createServiceAccountJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .createScoped(java.util.List.of(
                                "https://www.googleapis.com/auth/admin.reports.audit.readonly"))
                        .createDelegated(adminEmail);
            }

            // Khởi tạo Reports service
            com.google.api.services.reports.Reports reportsService = new com.google.api.services.reports.Reports.Builder(
                    com.google.api.client.googleapis.javanet.GoogleNetHttpTransport.newTrustedTransport(),
                    com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
                    new com.google.auth.http.HttpCredentialsAdapter(adminCreds))
                    .setApplicationName("Drive Recovery Tool v2.0")
                    .build();

            // Query audit log: tìm tất cả events có doc_id == fileId
            com.google.api.services.reports.model.Activities activities = reportsService.activities()
                    .list("all", "drive")
                    .setFilters("doc_id==" + fileId)
                    .setMaxResults(10)
                    .execute();

            if (activities.getItems() == null || activities.getItems().isEmpty()) {
                return null; // Không có log nào cho file/folder này
            }

            // Duyệt qua events, tìm field "owner" trong parameters
            for (com.google.api.services.reports.model.Activity activity : activities.getItems()) {
                if (activity.getEvents() == null)
                    continue;
                for (com.google.api.services.reports.model.Activity.Events event : activity.getEvents()) {
                    if (event.getParameters() == null)
                        continue;
                    for (com.google.api.services.reports.model.Activity.Events.Parameters param : event
                            .getParameters()) {
                        if ("owner".equals(param.getName()) && param.getValue() != null
                                && !param.getValue().isBlank()) {
                            return param.getValue(); // ← owner email tìm thấy!
                        }
                    }
                }
            }

            // Nếu không thấy field "owner" → thử lấy từ actor (người thực hiện action đầu
            // tiên)
            com.google.api.services.reports.model.Activity first = activities.getItems().get(0);
            if (first.getActor() != null && first.getActor().getEmail() != null) {
                pt.log("    ℹ️  Không có field 'owner' trong params → dùng actor: " + first.getActor().getEmail(),
                        ProgressTracker.LogLevel.DETAIL);
                return first.getActor().getEmail();
            }

        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 403) {
                pt.log("    ⚠️  Reports API 403 — Service Account chưa được grant scope admin.reports.audit.readonly",
                        ProgressTracker.LogLevel.WARNING);
            } else {
                pt.log("    ⚠️  Reports API lỗi HTTP " + e.getStatusCode() + ": " + e.getMessage(),
                        ProgressTracker.LogLevel.WARNING);
            }
        } catch (Exception e) {
            pt.log("    ⚠️  Reports API exception: " + e.getMessage(), ProgressTracker.LogLevel.WARNING);
        }
        return null;
    }

    private Drive createDriveServiceForUserWithRetry(String userEmail) throws Exception {

        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                return createDriveServiceForUser(userEmail);
            } catch (Exception e) {
                if (e.getMessage().contains("rate") || e.getMessage().contains("429")) {
                    retryCount++;
                    if (retryCount >= maxRetries) {
                        throw e;
                    }
                    try {
                        Thread.sleep(1000 * retryCount);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Exception("Interrupted during retry", ie);
                    }
                } else {
                    throw e;
                }
            }
        }

        throw new Exception("Max retries exceeded");
    }

    /**
     * Tạo DriveActivity service impersonating một user cụ thể.
     * Dùng trong Mode 2 để switch sang owner của folder.
     */
    private DriveActivity createActivityServiceForUser(String userEmail) throws Exception {
        com.google.auth.oauth2.GoogleCredentials credentials;

        if (Config.isUseJsonFile()) {
            credentials = com.google.auth.oauth2.ServiceAccountCredentials
                    .fromStream(new java.io.FileInputStream(Config.getServiceAccountFile()))
                    .createScoped(java.util.Arrays.asList(
                            "https://www.googleapis.com/auth/drive",
                            "https://www.googleapis.com/auth/drive.activity.readonly"))
                    .createDelegated(userEmail);
        } else {
            credentials = com.google.auth.oauth2.ServiceAccountCredentials
                    .fromStream(new java.io.ByteArrayInputStream(
                            createServiceAccountJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .createScoped(java.util.Arrays.asList(
                            "https://www.googleapis.com/auth/drive",
                            "https://www.googleapis.com/auth/drive.activity.readonly"))
                    .createDelegated(userEmail);
        }

        return new DriveActivity.Builder(
                com.google.api.client.googleapis.javanet.GoogleNetHttpTransport.newTrustedTransport(),
                com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
                new com.google.auth.http.HttpCredentialsAdapter(credentials))
                .setApplicationName("Drive Recovery Tool v2.0")
                .build();
    }

    private Drive createDriveServiceForUser(String userEmail) throws Exception {
        com.google.auth.oauth2.GoogleCredentials credentials;

        if (Config.isUseJsonFile()) { // ← dùng getter (mutable), không phải static field
            credentials = com.google.auth.oauth2.ServiceAccountCredentials
                    .fromStream(new java.io.FileInputStream(Config.getServiceAccountFile()))
                    .createScoped(java.util.Arrays.asList(
                            "https://www.googleapis.com/auth/drive",
                            "https://www.googleapis.com/auth/drive.activity.readonly"))
                    .createDelegated(userEmail);
        } else {
            credentials = com.google.auth.oauth2.ServiceAccountCredentials
                    .fromStream(new java.io.ByteArrayInputStream(
                            createServiceAccountJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .createScoped(java.util.Arrays.asList(
                            "https://www.googleapis.com/auth/drive",
                            "https://www.googleapis.com/auth/drive.activity.readonly"))
                    .createDelegated(userEmail);
        }

        return new Drive.Builder(
                com.google.api.client.googleapis.javanet.GoogleNetHttpTransport.newTrustedTransport(),
                com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
                new com.google.auth.http.HttpCredentialsAdapter(credentials))
                .setApplicationName("Drive Recovery Tool v2.0")
                .build();
    }

    private String createServiceAccountJson() {
        String privateKeyId = (Config.getPrivateKeyId() != null && !Config.getPrivateKeyId().isEmpty())
                ? Config.getPrivateKeyId()
                : "0";
        String clientId = (Config.getClientId() != null && !Config.getClientId().isEmpty())
                ? Config.getClientId()
                : "0";

        return String.format(
                "{\n" +
                        "  \"type\": \"service_account\",\n" +
                        "  \"project_id\": \"%s\",\n" +
                        "  \"private_key_id\": \"%s\",\n" +
                        "  \"private_key\": \"%s\",\n" +
                        "  \"client_email\": \"%s\",\n" +
                        "  \"client_id\": \"%s\",\n" +
                        "  \"auth_uri\": \"https://accounts.google.com/o/oauth2/auth\",\n" +
                        "  \"token_uri\": \"https://oauth2.googleapis.com/token\",\n" +
                        "  \"auth_provider_x509_cert_url\": \"https://www.googleapis.com/oauth2/v1/certs\"\n" +
                        "}",
                Config.getProjectId(), // ← getter
                privateKeyId,
                Config.getPrivateKey().replace("\n", "\\n"), // ← getter
                Config.getServiceAccountEmail(), // ← getter
                clientId);
    }

    private List<FileHistory> getFilesFromActivity(String folderId, String userEmail) throws IOException {
        Map<String, FileHistory> fileHistoryMap = new HashMap<>();
        String pageToken = null;

        System.out.println("  🔍 Đang query Activity API...");

        // ✨ LOG: Hiển thị cấu hình filter
        if (Config.getActivityDays() > 0) {
            System.out.println("  ⏰ Filter: Đọc activity từ " + Config.getActivityDays() + " ngày trước");
        }

        if (Config.getActivityEndDate() != null && !Config.getActivityEndDate().isEmpty()) {
            System.out.println("  ✂️  Filter: Cắt đọc tại " + Config.getActivityEndDate());
        }

        do {
            QueryDriveActivityRequest request = new QueryDriveActivityRequest();
            request.setAncestorName("items/" + folderId);
            request.setPageSize(100);

            // 🆕 THÊM FILTER
            String filter = buildActivityFilter();
            if (filter != null && !filter.isEmpty()) {
                request.setFilter(filter);
                System.out.println("  🔍 Filter string: " + filter);
            }

            if (pageToken != null) {
                request.setPageToken(pageToken);
            }

            // ⭐ FIX 429: dùng helper có semaphore + retry
            QueryDriveActivityResponse response = executeActivityQueryWithRetry(request);

            if (response.getActivities() != null) {
                List<com.google.api.services.driveactivity.v2.model.DriveActivity> activities = new ArrayList<>(
                        response.getActivities());

                activities.sort((a, b) -> {
                    String timeA = a.getTimestamp() != null ? a.getTimestamp() : "";
                    String timeB = b.getTimestamp() != null ? b.getTimestamp() : "";
                    return timeA.compareTo(timeB);
                });

                // ✨ LOG: Hiển thị khoảng thời gian
                if (!activities.isEmpty() && pageToken == null) { // Chỉ log lần đầu
                    logActivityTimeRange(activities, folderId);
                }

                System.out.println("  🔍 Xử lý " + activities.size() + " activities...");

                for (com.google.api.services.driveactivity.v2.model.DriveActivity activity : activities) {
                    processActivity(activity, folderId, fileHistoryMap);
                }
            }

            pageToken = response.getNextPageToken();
        } while (pageToken != null);

        List<FileHistory> result = fileHistoryMap.values().stream()
                // Include: file từng ở đây (everInFolder), HOẶC file bị DELETE khỏi subtree
                // (deletedFromSubtree=true → có thể là direct child bị xóa vào Trash)
                .filter(fh -> fh.everInFolder || fh.deletedFromSubtree)
                .collect(Collectors.toList());

        System.out.println("  🔍 Có " + result.size() + " file từng thuộc TRỰC TIẾP folder này");

        return result;
    }

    /**
     * 🆕 Build filter string cho Activity API
     */
    private String buildActivityFilter() {
        List<String> filterParts = new ArrayList<>();

        // 1. Filter START time (nếu có ACTIVITY_DAYS)
        if (Config.getActivityDays() > 0) {
            try {
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DATE, -Config.getActivityDays());

                SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
                isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

                String startTime = isoFormat.format(cal.getTime());
                filterParts.add("time >= \"" + startTime + "\"");
            } catch (Exception e) {
                System.err.println("⚠️  Lỗi parse ACTIVITY_DAYS: " + e.getMessage());
            }
        }

        // 2. ✨ Filter END time (nếu có ACTIVITY_END_DATE)
        if (Config.getActivityEndDate() != null && !Config.getActivityEndDate().isEmpty()) {
            try {
                // Parse ngày người dùng nhập (format: yyyy-MM-dd)
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
                dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date endDate = dateFormat.parse(Config.getActivityEndDate());

                // Set time đến cuối ngày (23:59:59.999)
                Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                cal.setTime(endDate);
                cal.set(Calendar.HOUR_OF_DAY, 23);
                cal.set(Calendar.MINUTE, 59);
                cal.set(Calendar.SECOND, 59);
                cal.set(Calendar.MILLISECOND, 999);

                SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
                isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

                String endTime = isoFormat.format(cal.getTime());
                filterParts.add("time <= \"" + endTime + "\"");

                System.out.println("  ✂️  Chỉ đọc activity đến: " + endTime);
            } catch (Exception e) {
                System.err.println("⚠️  Lỗi parse ACTIVITY_END_DATE: " + e.getMessage());
            }
        }

        // Ghép filter
        if (filterParts.isEmpty()) {
            return null;
        }

        return String.join(" AND ", filterParts);
    }

    /**
     * Log khoảng thời gian activity (không gọi API thêm — dùng data đã có)
     */
    private void logActivityTimeRange(List<com.google.api.services.driveactivity.v2.model.DriveActivity> activities,
            String folderId) {
        if (activities.isEmpty())
            return;

        try {
            SimpleDateFormat displayFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            displayFormat.setTimeZone(TimeZone.getTimeZone("GMT+7"));
            // Parser 1: có milliseconds — "2026-05-02T23:59:19.123Z"
            SimpleDateFormat isoParserMs = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
            isoParserMs.setTimeZone(TimeZone.getTimeZone("UTC"));
            // Parser 2: không có milliseconds — "2026-05-02T23:59:19Z"
            SimpleDateFormat isoParserNoMs = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
            isoParserNoMs.setTimeZone(TimeZone.getTimeZone("UTC"));

            String earliestTime = "N/A";
            if (activities.get(0).getTimestamp() != null) {
                earliestTime = displayFormat
                        .format(parseIsoTimestamp(activities.get(0).getTimestamp(), isoParserMs, isoParserNoMs));
            }

            String latestTime = "N/A";
            if (activities.get(activities.size() - 1).getTimestamp() != null) {
                latestTime = displayFormat.format(
                        parseIsoTimestamp(activities.get(activities.size() - 1).getTimestamp(), isoParserMs,
                                isoParserNoMs));
            }

            System.out.println("  📅 Khoảng activity đã đọc:");
            System.out.println("     📍 Củ nhất: " + earliestTime);
            if (Config.getActivityEndDate() != null && !Config.getActivityEndDate().isEmpty()) {
                System.out.println("     ✂️  Cắt tại: " + Config.getActivityEndDate() + " 23:59:59");
            } else {
                System.out.println("     📍 Mới nhất: " + latestTime);
            }
        } catch (Exception e) {
            System.err.println("⚠️  Lỗi khi log time range: " + e.getMessage());
        }
    }

    private void processActivity(com.google.api.services.driveactivity.v2.model.DriveActivity activity,
            String folderId,
            Map<String, FileHistory> fileHistoryMap) {
        if (activity.getTargets() == null) {
            return;
        }

        String timestamp = activity.getTimestamp();

        // ⭐ Lấy TẤT CẢ actions (bao gồm cả primaryActionDetail)
        List<ActionDetail> allActions = new ArrayList<>();

        if (activity.getPrimaryActionDetail() != null) {
            allActions.add(activity.getPrimaryActionDetail());
        }

        if (activity.getActions() != null) {
            for (Action action : activity.getActions()) {
                if (action.getDetail() != null) {
                    allActions.add(action.getDetail());
                }
            }
        }

        for (Target target : activity.getTargets()) {
            if (target.getDriveItem() == null) {
                continue;
            }

            // ⭐ FIX BUG: Bỏ qua nếu target là Folder (chỉ xử lý file ở đây)
            // BUG CŨ: chỉ check getDriveFolder() != null → folder bị MOVE không có
            // driveFolder
            // field → bị xử lý nhầm như file
            // FIX: check EITHER driveFolder != null OR mimeType là folder
            boolean isFolderTarget = target.getDriveItem().getDriveFolder() != null
                    || "application/vnd.google-apps.folder".equals(target.getDriveItem().getMimeType());
            if (isFolderTarget) {
                continue;
            }

            // ⭐ FIX: getDriveFile() == null với PDF/binary file upload — KHÔNG bỏ qua!
            // getDriveFile() chỉ non-null với Google Workspace files (Docs, Sheets...)
            // Uploaded files (PDF, docx, image...) có getDriveFile() == null nhưng vẫn là
            // file hợp lệ

            String fileId = extractFileId(target.getDriveItem().getName());
            String fileName = target.getDriveItem().getTitle();

            if (fileId == null)
                continue;

            boolean addedToFolder = false;
            boolean removedFromFolder = false;
            boolean deletedFlag = false; // ⭐ FIX: track DELETE event separately

            for (ActionDetail detail : allActions) {
                // ⭐ CREATE — xử lý giống folder: verify parent bằng Drive API
                // ancestorName trả về cả subtree → phải check parents thực tế
                // Bắt: file upload trực tiếp vào folder (không qua MOVE)
                if (detail.getCreate() != null) {
                    try {
                        com.google.api.services.drive.model.File fileMeta = driveService.files().get(fileId)
                                .setFields("parents, trashed")
                                .setSupportsAllDrives(true)
                                .execute();
                        if (fileMeta.getParents() != null
                                && fileMeta.getParents().contains(folderId)
                                && (fileMeta.getTrashed() == null || !fileMeta.getTrashed())) {
                            addedToFolder = true;
                        }
                    } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException gje) {
                        if (gje.getStatusCode() == 404) {
                            // File bị xóa vĩnh viễn → không verify được parents.
                            // ⚠️ QUAN TRỌNG: ancestorName query trả về TOÀN BỘ subtree.
                            // File 404 này có thể từng tạo trong D (cháu của B), không phải
                            // trực tiếp trong B. Không thể verify → KHÔNG set addedToFolder=true.
                            // Chỉ mark permanentlyDeleted để báo cáo, không move.
                            if (!fileHistoryMap.containsKey(fileId)) {
                                FileHistory newFh = new FileHistory();
                                newFh.id = fileId;
                                newFh.name = fileName;
                                newFh.everInFolder = true;
                                newFh.currentlyInFolder = false; // 404 → không thể move
                                newFh.permanentlyDeleted = true;
                                newFh.lastSeenTimestamp = timestamp;
                                fileHistoryMap.put(fileId, newFh);
                            } else {
                                fileHistoryMap.get(fileId).permanentlyDeleted = true;
                                fileHistoryMap.get(fileId).currentlyInFolder = false;
                            }
                        }
                        // HTTP khác (403, 500...) → bỏ qua CREATE này, không đủ info
                    } catch (Exception ignored) {
                        // Network/IO lỗi → bỏ qua, không crash
                    }
                }

                // ⭐ MOVE
                if (detail.getMove() != null) {
                    Move move = detail.getMove();

                    if (move.getAddedParents() != null) {
                        for (TargetReference parent : move.getAddedParents()) {
                            String parentId = extractFileId(parent.getDriveItem().getName());
                            if (folderId.equals(parentId)) {
                                addedToFolder = true;
                            }
                        }
                    }

                    if (move.getRemovedParents() != null) {
                        for (TargetReference parent : move.getRemovedParents()) {
                            String parentId = extractFileId(parent.getDriveItem().getName());
                            if (folderId.equals(parentId)) {
                                removedFromFolder = true;
                            }
                        }
                    }
                }
                // ⭐ FIX: Detect DELETE event cho file
                // Khi file bị xóa (DELETE): không có addedParents/removedParents
                // → addedToFolder và removedFromFolder đều false → bị skip ở line bên dưới
                // Fix: detect DELETE và mark deletedFromSubtree=true để checkFolder biết
                if (detail.getDelete() != null) {
                    deletedFlag = true; // ⭐ FIX: chỉ set flag, xử lý SAU guard
                }
            }

            // ⭐ FIX: Include deletedFlag trong guard — DELETE-only items vẫn được xử lý.
            // Deepest folder (D) xử lý trước nhờ Collections.reverse() → kéo C về D đúng.
            // Shallower folder (B) chạy sau: C.parents=[D], D∈subtree(B) → grandchild → skip.
            if (!addedToFolder && !removedFromFolder && !deletedFlag) {
                continue;
            }

            // ⭐ FIX: Handle DELETE SAU guard — deepest folder thắng, shallower bị block
            if (deletedFlag) {
                if (!fileHistoryMap.containsKey(fileId)) {
                    FileHistory newFh = new FileHistory();
                    newFh.id = fileId;
                    newFh.name = fileName;
                    newFh.everInFolder = false;
                    newFh.currentlyInFolder = false;
                    newFh.deletedFromSubtree = true;
                    newFh.lastSeenTimestamp = timestamp;
                    fileHistoryMap.put(fileId, newFh);
                } else {
                    fileHistoryMap.get(fileId).currentlyInFolder = false;
                    fileHistoryMap.get(fileId).deletedFromSubtree = true;
                }
            }

            if (!fileHistoryMap.containsKey(fileId)) {
                FileHistory newFh = new FileHistory();
                newFh.id = fileId;
                newFh.name = fileName;
                newFh.everInFolder = false;
                newFh.currentlyInFolder = false;
                newFh.lastSeenTimestamp = null;
                fileHistoryMap.put(fileId, newFh);
            }

            FileHistory fh = fileHistoryMap.get(fileId);

            if (addedToFolder) {
                fh.everInFolder = true;
                fh.currentlyInFolder = true;
                fh.name = fileName;
                fh.lastSeenTimestamp = timestamp;
            }

            if (removedFromFolder) {
                // KEY FIX: nếu file bị REMOVE khỏi folder này → nó chắc chắn đã TỮNG ở trong
                // folder
                // (cả trường hợp: auto-removed, bị admin xóa, folder bị un-share)
                fh.everInFolder = true;
                fh.name = fileName;
                if (fh.lastSeenTimestamp == null) {
                    fh.lastSeenTimestamp = timestamp;
                }
                fh.currentlyInFolder = false;
            }

            boolean hasDelete = allActions.stream().anyMatch(a -> a.getDelete() != null);
            if (hasDelete) {
                fh.currentlyInFolder = false;
            }
        }
    }

    /**
     * Xử lý một activity để xây dựng lịch sử FOLDER trực tiếp trong folderId.
     * Chỉ quan tâm target là DriveFolder (bỏ qua DriveFile).
     */
    private void processActivityForFolders(
            com.google.api.services.driveactivity.v2.model.DriveActivity activity,
            String folderId,
            Map<String, FileHistory> map) {

        if (activity.getTargets() == null)
            return;

        String timestamp = activity.getTimestamp();

        // Thu thập tất cả actions
        List<ActionDetail> allActions = new ArrayList<>();
        if (activity.getPrimaryActionDetail() != null) {
            allActions.add(activity.getPrimaryActionDetail());
        }
        if (activity.getActions() != null) {
            for (Action action : activity.getActions()) {
                if (action.getDetail() != null) {
                    allActions.add(action.getDetail());
                }
            }
        }

        for (Target target : activity.getTargets()) {
            if (target.getDriveItem() == null)
                continue;

            // ⭐ FIX BUG: Chỉ xử lý FOLDER target
            // BUG CŨ: chỉ check getDriveFolder() != null → bỏ sót folder bị MOVE
            // vì khi MOVE, Activity API KHÔNG set driveFolder field trong target,
            // chỉ set mimeType = "application/vnd.google-apps.folder"
            // FIX: check EITHER driveFolder != null OR mimeType là folder
            boolean isFolderTarget = target.getDriveItem().getDriveFolder() != null
                    || "application/vnd.google-apps.folder".equals(target.getDriveItem().getMimeType());
            if (!isFolderTarget)
                continue;

            String foldItemId = extractFileId(target.getDriveItem().getName());
            String foldItemName = target.getDriveItem().getTitle();
            if (foldItemId == null)
                continue;

            // ⭐ FIX: Bỏ qua Shared Drive root — 2 cách detect:
            // 1. ID bắt đầu bằng "0A" (Shared Drive root format)
            // 2. DriveFolder.type = "SHARED_DRIVE_ROOT" (từ Activity API)
            if (foldItemId.startsWith("0A"))
                continue;
            if (target.getDriveItem().getDriveFolder() != null) {
                String folderType = target.getDriveItem().getDriveFolder().getType();
                if ("SHARED_DRIVE_ROOT".equals(folderType))
                    continue;
            }

            boolean addedToFolder = false;
            boolean removedFromFolder = false;
            boolean createdInFolder = false;
            boolean deletedFlag = false; // ⭐ FIX: track DELETE event separately

            for (ActionDetail detail : allActions) {
                // ⭐ FIX: Detect CREATE event cho folder trực tiếp trong folderId
                // ancestorName query trả về cả subtree → phải verify parent thực sự là folderId
                // Dùng Drive API để check parents (chấp nhận 1 API call cho CREATE event)
                if (detail.getCreate() != null) {
                    try {
                        com.google.api.services.drive.model.File folderMeta = driveService.files().get(foldItemId)
                                .setFields("parents, trashed")
                                .setSupportsAllDrives(true)
                                .execute();
                        if (folderMeta.getParents() != null
                                && folderMeta.getParents().contains(folderId)
                                && (folderMeta.getTrashed() == null || !folderMeta.getTrashed())) {
                            createdInFolder = true;
                        }
                    } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException gje) {
                        if (gje.getStatusCode() == 404) {
                            // Folder bị xóa vĩnh viễn → không verify được parents.
                            // ⚠️ QUAN TRỌNG: ancestorName query trả về TOÀN BỘ subtree.
                            // Folder 404 này có thể từng là direct child CỦA BẤT KỲ FOLDER NÀO
                            // trong subtree — KHÔNG CHỈ folderId. Đánh dấu everInFolder=true
                            // nhưng currentlyInFolder=false để báo cáo (không move) vì
                            // không thể xác nhận nó thực sự là direct child của folderId.
                            if (!map.containsKey(foldItemId)) {
                                FileHistory newFh = new FileHistory();
                                newFh.id = foldItemId;
                                newFh.name = foldItemName;
                                newFh.everInFolder = true;
                                newFh.currentlyInFolder = false; // 404 → không thể move
                                newFh.permanentlyDeleted = true;
                                newFh.lastSeenTimestamp = timestamp;
                                map.put(foldItemId, newFh);
                            } else {
                                map.get(foldItemId).permanentlyDeleted = true;
                                map.get(foldItemId).currentlyInFolder = false;
                            }
                        }
                        // HTTP khác (403, 500...) → bỏ qua CREATE này, không đủ info
                    } catch (Exception ignored) {
                        // Network/IO lỗi → bỏ qua, không crash
                    }
                }

                if (detail.getMove() != null) {
                    Move move = detail.getMove();
                    if (move.getAddedParents() != null) {
                        for (TargetReference parent : move.getAddedParents()) {
                            String parentId = extractFileId(parent.getDriveItem().getName());
                            if (folderId.equals(parentId)) {
                                addedToFolder = true;
                            }
                        }
                    }
                    if (move.getRemovedParents() != null) {
                        for (TargetReference parent : move.getRemovedParents()) {
                            String parentId = extractFileId(parent.getDriveItem().getName());
                            if (folderId.equals(parentId)) {
                                removedFromFolder = true;
                            }
                        }
                    }
                }
                // ⭐ FIX: Detect DELETE event
                // Khi folder B bị xóa khỏi folder A (owner là user khác delete):
                // - Activity API trả về event DELETE cho folder B
                // - KHÔNG có addedParents/removedParents (vì không phải MOVE)
                // - Sau khi xóa, không thể gọi Drive API verify parents nữa
                // → dùng dấu hiệu: query là ancestorName=folderId, nếu có DELETE
                // thì folder này từng nằm trong subtree của folderId
                // → cần mark để báo cáo (permanentlyDeleted hoặc in trash)
                if (detail.getDelete() != null) {
                    deletedFlag = true; // ⭐ FIX: chỉ set flag, xử lý SAU guard
                }
            }

            // ⭐ FIX: Include deletedFlag trong guard — DELETE-only items vẫn được xử lý.
            // Deepest folder (D) xử lý trước nhờ Collections.reverse() → kéo C về D đúng.
            // Shallower folder (B) chạy sau: C.parents=[D], D∈allDescendantIds(B) → skip.
            if (!addedToFolder && !removedFromFolder && !createdInFolder && !deletedFlag)
                continue;

            // ⭐ FIX: Handle DELETE SAU guard — deepest folder thắng, shallower bị block
            if (deletedFlag) {
                if (!map.containsKey(foldItemId)) {
                    FileHistory newFh = new FileHistory();
                    newFh.id = foldItemId;
                    newFh.name = foldItemName;
                    newFh.everInFolder = false;
                    newFh.currentlyInFolder = false;
                    newFh.deletedFromSubtree = true;
                    newFh.lastSeenTimestamp = timestamp;
                    map.put(foldItemId, newFh);
                } else {
                    map.get(foldItemId).currentlyInFolder = false;
                    map.get(foldItemId).deletedFromSubtree = true;
                }
            }

            if (!map.containsKey(foldItemId)) {
                FileHistory newFh = new FileHistory();
                newFh.id = foldItemId;
                newFh.name = foldItemName;
                newFh.everInFolder = false;
                newFh.currentlyInFolder = false;
                newFh.lastSeenTimestamp = null;
                map.put(foldItemId, newFh);
            }

            FileHistory fh = map.get(foldItemId);

            if (createdInFolder) {
                // Folder được tạo và hiện vẫn đang trong folderId (đã verify ở trên)
                fh.everInFolder = true;
                fh.currentlyInFolder = true;
                fh.name = foldItemName;
                if (fh.lastSeenTimestamp == null) {
                    fh.lastSeenTimestamp = timestamp;
                }
            }

            if (addedToFolder) {
                fh.everInFolder = true;
                fh.currentlyInFolder = true;
                fh.name = foldItemName;
                fh.lastSeenTimestamp = timestamp;
            }
            if (removedFromFolder) {
                fh.everInFolder = true;
                fh.name = foldItemName;
                if (fh.lastSeenTimestamp == null) {
                    fh.lastSeenTimestamp = timestamp;
                }
                fh.currentlyInFolder = false;
            }

            boolean hasDelete = allActions.stream().anyMatch(a -> a.getDelete() != null);
            if (hasDelete) {
                fh.currentlyInFolder = false;
            }
        }
    }

    private String extractFileId(String name) {
        if (name == null)
            return null;
        String[] parts = name.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : null;
    }

    /**
     * ✅ FIXED: Move file VÀ VERIFY kết quả (giống Apps Script)
     */
    private MoveResult moveFileToFolder(String fileId, List<String> currentParents,
            String targetFolderId, Drive driveService) {
        MoveResult result = new MoveResult();
        result.success = false;
        ProgressTracker pt = ProgressTracker.getInstance();

        // ⭐ FIX: Nếu parents null (folder/file đang ở root My Drive — impersonation
        // không thấy được)
        // → dùng "root" làm removeParents (keyword của Drive API = My Drive root)
        List<String> effectiveParents = currentParents;
        if (effectiveParents == null || effectiveParents.isEmpty()) {
            pt.log("    ⚠️  Parents null → thử dùng 'root' làm removeParents (item ở My Drive root)",
                    ProgressTracker.LogLevel.DETAIL);
            effectiveParents = java.util.Collections.singletonList("root");
        }

        if (fileId.equals(targetFolderId)) {
            result.reason = "Bỏ qua: file/folder trùng ID với target";
            pt.log("    ⏭️  Bỏ qua: không thể move vào chính nó", ProgressTracker.LogLevel.DETAIL);
            return result;
        }

        if (effectiveParents.contains(targetFolderId)) {
            result.success = true;
            result.reason = "Đã trong target folder";
            pt.log("    ✓ Đã ở trong target folder", ProgressTracker.LogLevel.DETAIL);
            return result;
        }

        try {
            String removeParents = String.join(",", effectiveParents);

            driveService.files().update(fileId, null)
                    .setAddParents(targetFolderId)
                    .setRemoveParents(removeParents)
                    .setSupportsAllDrives(true)
                    .setFields("id, parents")
                    .execute();

            // ⭐ Verify: list file trong target folder
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean verified = verifyInTargetFolder(fileId, targetFolderId, driveService);
            if (verified) {
                result.success = true;
                result.reason = "Success (full move)";
                return result;
            } else {
                result.success = true;
                result.reason = "Success (update API OK — verify skipped do impersonation limit)";
                pt.log("    ⚠️  Verify không confirm được nhưng update API OK → coi là thành công",
                        ProgressTracker.LogLevel.DETAIL);
                return result;
            }

        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException fullMoveEx) {
            // ── Lần thử 1 fail → thử Fallback: chỉ addParents, bỏ removeParents ──
            // Trường hợp: caller có quyền với file + targetFolder nhưng không có quyền
            // xóa parent cũ (vd: My Drive root của user khác → 404 trên removeParents).
            // File sẽ xuất hiện ở cả 2 nơi nhưng ít nhất về được target folder.
            String errorMsg = fullMoveEx.getDetails() != null
                    ? fullMoveEx.getDetails().getMessage()
                    : fullMoveEx.getMessage();
            pt.log("    ❌ Move FAILED (" + fullMoveEx.getStatusCode() + "): " + errorMsg,
                    ProgressTracker.LogLevel.ERROR);

            if (fullMoveEx.getStatusCode() == 404 || fullMoveEx.getStatusCode() == 403) {
                pt.log("    🔄 Thử fallback: addParents-only (không removeParents)...",
                        ProgressTracker.LogLevel.DETAIL);
                try {
                    driveService.files().update(fileId, null)
                            .setAddParents(targetFolderId)
                            // Không setRemoveParents → file xuất hiện ở cả 2 nơi
                            .setSupportsAllDrives(true)
                            .setFields("id, parents")
                            .execute();

                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    boolean verified = verifyInTargetFolder(fileId, targetFolderId, driveService);
                    if (verified) {
                        result.success = true;
                        result.reason = "Success (addParents-only — file pinned to target, still in source)";
                        pt.log("    ✅ Fallback OK: file đã xuất hiện ở target folder (vẫn còn ở nguồn)",
                                ProgressTracker.LogLevel.SUCCESS);
                        return result;
                    } else {
                        result.success = true;
                        result.reason = "Success (addParents-only API OK — verify skipped)";
                        pt.log("    ✅ Fallback API OK (verify skipped)", ProgressTracker.LogLevel.SUCCESS);
                        return result;
                    }
                } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException fallbackEx) {
                    String fbMsg = fallbackEx.getDetails() != null
                            ? fallbackEx.getDetails().getMessage()
                            : fallbackEx.getMessage();
                    pt.log("    ❌ Fallback cũng FAILED (" + fallbackEx.getStatusCode() + "): " + fbMsg,
                            ProgressTracker.LogLevel.ERROR);
                    result.reason = "HTTP " + fullMoveEx.getStatusCode() + ": " + errorMsg
                            + " | Fallback: HTTP " + fallbackEx.getStatusCode() + ": " + fbMsg;
                    return result;
                } catch (Exception fallbackEx) {
                    pt.log("    ❌ Fallback exception: " + fallbackEx.getMessage(), ProgressTracker.LogLevel.ERROR);
                    result.reason = errorMsg + " | Fallback: " + fallbackEx.getMessage();
                    return result;
                }
            }

            result.reason = "HTTP " + fullMoveEx.getStatusCode() + ": " + errorMsg;
            return result;

        } catch (Exception e) {
            pt.log("    ❌ Move exception: " + e.getMessage(), ProgressTracker.LogLevel.ERROR);
            result.reason = e.getMessage();
            return result;
        }
    }

    /**
     * Verify file/folder xuất hiện trong target folder.
     * Thử qua candidateDrive trước, fallback sang this.driveService.
     */
    private boolean verifyInTargetFolder(String fileId, String targetFolderId, Drive candidateDrive) {
        try {
            File verifyFile = candidateDrive.files().get(fileId)
                    .setFields("id, parents")
                    .setSupportsAllDrives(true)
                    .execute();
            if (verifyFile.getParents() != null && verifyFile.getParents().contains(targetFolderId)) {
                return true;
            }
        } catch (Exception ignored) {
        }

        // Fallback: list file trong target folder bằng main driveService
        try {
            String q = "'" + targetFolderId + "' in parents and trashed=false";
            FileList fl = this.driveService.files().list()
                    .setQ(q)
                    .setFields("files(id)")
                    .setPageSize(1000)
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .execute();
            return fl.getFiles() != null && fl.getFiles().stream().anyMatch(f -> fileId.equals(f.getId()));
        } catch (Exception ignored) {
        }
        return false;
    }

    private List<File> getCurrentFilesInFolder(String folderId, String userEmail) throws IOException {
        List<File> files = new ArrayList<>();
        String pageToken = null;

        do {
            String query = "'" + folderId + "' in parents and trashed=false";
            FileList result = driveService.files().list()
                    .setQ(query)
                    .setFields("nextPageToken, files(id, name, mimeType)")
                    .setPageSize(1000)
                    .setPageToken(pageToken)
                    .execute();

            if (result.getFiles() != null) {
                files.addAll(result.getFiles().stream()
                        .filter(f -> !"application/vnd.google-apps.folder".equals(f.getMimeType()))
                        .collect(Collectors.toList()));
            }

            pageToken = result.getNextPageToken();

        } while (pageToken != null);

        return files;
    }

    private Set<String> getAllSubfolderIds(String parentId, String userEmail) throws IOException {
        Set<String> visited = new HashSet<>();
        return getAllSubfolderIds(parentId, userEmail, visited);
    }

    private Set<String> getAllSubfolderIds(String parentId, String userEmail, Set<String> visited) throws IOException {
        // Cycle detection: nếu folder này đã được xử lý → dừng để tránh vòng lặp vô tận
        if (!visited.add(parentId)) {
            return new HashSet<>();
        }
        // Cache: tránh gọi Drive API lặp lại cho cùng một folder
        if (subfolderIdCache.containsKey(parentId)) {
            return subfolderIdCache.get(parentId);
        }
        Set<String> result = new HashSet<>();
        List<File> folders = getFoldersInParent(parentId, userEmail);
        for (File folder : folders) {
            result.add(folder.getId());
            result.addAll(getAllSubfolderIds(folder.getId(), userEmail, visited));
        }
        subfolderIdCache.put(parentId, result);
        return result;
    }

    private Set<String> getAllFilesInSubfolders(Set<String> subfolderIds, String userEmail) throws IOException {
        Set<String> result = new HashSet<>();
        for (String folderId : subfolderIds) {
            List<File> files = getCurrentFilesInFolder(folderId, userEmail);
            result.addAll(files.stream().map(File::getId).collect(Collectors.toList()));
        }
        return result;
    }

    public String generateExcelReport(String currentUserEmail) throws IOException {
        String userEmails = currentUserEmail.split("@")[0];
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String prefix = timedOut ? "Timeout-" + Config.getOutputFilePrefix() : Config.getOutputFilePrefix();
        String fileName = prefix + "-" + userEmails + "-" + timestamp + ".xlsx";

        java.io.File outputDir = new java.io.File(Config.getOutputDirectory());
        if (!outputDir.exists())
            outputDir.mkdirs();
        String fullPath = Config.getOutputDirectory() + fileName;

        Workbook workbook = new XSSFWorkbook();
        List<FolderReport> reportsList = new ArrayList<>(allReports);

        // Sheet 1: Tổng quan
        createEnhancedSummarySheet(workbook.createSheet("Tong quan"), workbook, reportsList);

        // Sheet 2: Thiếu - Tổng hợp (mới - quan trọng nhất)
        createMissingSummarySheet(workbook.createSheet("Thieu - Tong hop"), workbook, reportsList);

        // Sheet 3: Folder bị thiếu
        if (Config.getSearchFolders())
            createMissingFoldersSheet(workbook.createSheet("Folder bi thieu"), workbook, reportsList);

        // Sheet 4: File bị thiếu & kết quả move
        createFilesStatusSheet(workbook.createSheet("File bi thieu"), workbook, reportsList);

        // Sheet 5: File đã xóa vĩnh viễn
        createDeletedFilesSheet(workbook.createSheet("File da xoa"), workbook, reportsList);

        // Sheet 6+: Chi tiết từng folder (tên tab = tên folder)
        for (FolderReport report : reportsList) {
            if (report.files == null || report.files.isEmpty())
                continue;
            // Lấy tên folder cuối cùng trong path, giới hạn 28 ký tự cho tên tab
            String folderName = report.folderPath;
            if (folderName.contains("/"))
                folderName = folderName.substring(folderName.lastIndexOf('/') + 1);
            // ⭐ FIX: Sanitize tên tab Excel (loại bỏ ký tự không hợp lệ: [ ] \ / * ? :)
            folderName = sanitizeSheetName(folderName);
            if (folderName.length() > 28)
                folderName = folderName.substring(0, 28);
            // Đảm bảo tên tab không bị trùng
            String tabName = folderName;
            int dup = 2;
            while (workbook.getSheet(tabName) != null)
                tabName = folderName + "_" + (dup++);
            createDetailSheet(workbook.createSheet(tabName), report, workbook);
        }

        try (FileOutputStream out = new FileOutputStream(fullPath)) {
            workbook.write(out);
        }
        workbook.close();
        System.out.println("✅ Đã tạo Excel: " + fullPath);
        return new java.io.File(fullPath).getAbsolutePath();
    }

    /**
     * ⭐ Sanitize tên sheet Excel: loại bỏ các ký tự không hợp lệ theo Apache POI
     * Invalid chars: [ ] \ / * ? :
     */
    private String sanitizeSheetName(String name) {
        if (name == null || name.isEmpty())
            return "Sheet";
        return name.replaceAll("[\\[\\]\\\\/\\*\\?:]", "").trim();
    }

    /**
     * Sheet tổng hợp THIẾU: liệt kê rõ folder/file nào thiếu, đang ở đâu, đã move
     * chưa
     */
    private void createMissingSummarySheet(Sheet sheet, Workbook workbook, List<FolderReport> reports) {
        // Styles
        CellStyle hdr = mkHeaderStyle(workbook, IndexedColors.DARK_BLUE);
        CellStyle green = mkBgStyle(workbook, IndexedColors.LIGHT_GREEN);
        CellStyle red = mkBgStyle(workbook, IndexedColors.ROSE);
        CellStyle yellow = mkBgStyle(workbook, IndexedColors.LIGHT_YELLOW);
        CellStyle bold = workbook.createCellStyle();
        Font bf = workbook.createFont();
        bf.setBold(true);
        bf.setFontHeightInPoints((short) 11);
        bold.setFont(bf);

        int r = 0;
        // Tiêu đề
        Row title = sheet.createRow(r++);
        Cell tc = title.createCell(0);
        tc.setCellValue("THIẾU - TỔNG HỢP");
        tc.setCellStyle(bold);
        r++;

        // ── SECTION 1: FOLDER BỊ THIẾU ──
        if (Config.getSearchFolders()) {
            Row sec = sheet.createRow(r++);
            Cell sc = sec.createCell(0);
            sc.setCellValue("▶ FOLDER BỊ THIẾU");
            sc.setCellStyle(bold);

            Row fh = sheet.createRow(r++);
            String[] fHeaders = { "Parent Folder", "Tên Subfolder", "Folder ID", "Trạng thái", "Kết quả Move",
                    "Đang nằm ở", "Lần cuối thấy" };
            for (int i = 0; i < fHeaders.length; i++) {
                Cell c = fh.createCell(i);
                c.setCellValue(fHeaders[i]);
                c.setCellStyle(hdr);
            }

            boolean anyFolder = false;
            for (FolderReport rep : reports) {
                if (rep.subFolders == null)
                    continue;
                for (SubFolderInfo sf : rep.subFolders) {
                    if ("Có".equals(sf.status))
                        continue; // chỉ ghi thiếu
                    anyFolder = true;
                    Row row = sheet.createRow(r++);
                    row.createCell(0).setCellValue(rep.folderPath);
                    row.createCell(1).setCellValue(sf.folderName);
                    row.createCell(2).setCellValue(sf.folderId);
                    Cell stCell = row.createCell(3);
                    stCell.setCellValue("THIẾU");
                    stCell.setCellStyle(red);
                    Cell actCell = row.createCell(4);
                    if (sf.action != null && sf.action.startsWith("Đã move")) {
                        actCell.setCellValue("✅ Đã move thành công");
                        actCell.setCellStyle(green);
                    } else {
                        actCell.setCellValue(sf.action != null ? sf.action : "Không tìm thấy");
                        actCell.setCellStyle(red);
                    }
                    row.createCell(5).setCellValue(sf.movedFrom != null ? sf.movedFrom : "-");
                    row.createCell(6).setCellValue(sf.lastSeen != null ? sf.lastSeen : "N/A");
                }
            }
            if (!anyFolder) {
                Row nr = sheet.createRow(r++);
                nr.createCell(0).setCellValue("✅ Không có folder nào bị thiếu");
            }
            r++;
        }

        // ── SECTION 2: FILE BỊ THIẾU ──
        Row sec2 = sheet.createRow(r++);
        Cell sc2 = sec2.createCell(0);
        sc2.setCellValue("▶ FILE BỊ THIẾU");
        sc2.setCellStyle(bold);

        Row fh2 = sheet.createRow(r++);
        String[] fHeaders2 = { "Folder chứa", "Tên File", "File ID", "Trạng thái", "Kết quả Move",
                "Đang nằm ở (My Drive của ai)", "Lần cuối thấy" };
        for (int i = 0; i < fHeaders2.length; i++) {
            Cell c = fh2.createCell(i);
            c.setCellValue(fHeaders2[i]);
            c.setCellStyle(hdr);
        }

        boolean anyFile = false;
        for (FolderReport rep : reports) {
            if (rep.files == null)
                continue;
            for (FileInfo fi : rep.files) {
                if (!"Thieu".equals(fi.status) && !"✗ Thiếu".equals(fi.status))
                    continue;
                anyFile = true;
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(rep.folderPath);
                row.createCell(1).setCellValue(fi.fileName);
                row.createCell(2).setCellValue(fi.fileId);
                Cell stCell = row.createCell(3);
                stCell.setCellValue("THIẾU");
                stCell.setCellStyle(red);
                Cell actCell = row.createCell(4);
                if (fi.action != null && (fi.action.startsWith("Đã move") || fi.action.startsWith("✅"))) {
                    actCell.setCellValue("✅ Đã move thành công");
                    actCell.setCellStyle(green);
                } else if (fi.action != null && fi.action.contains("TRASH")) {
                    actCell.setCellValue("🗑 Trong Trash");
                    actCell.setCellStyle(yellow);
                } else {
                    actCell.setCellValue(fi.action != null ? fi.action : "Không tìm thấy");
                    actCell.setCellStyle(red);
                }
                row.createCell(5).setCellValue(fi.movedFrom != null ? fi.movedFrom : "-");
                row.createCell(6).setCellValue(fi.lastSeen != null ? fi.lastSeen : "N/A");
            }
        }
        if (!anyFile) {
            Row nr = sheet.createRow(r++);
            nr.createCell(0).setCellValue("✅ Không có file nào bị thiếu");
        }

        // Auto size
        for (int i = 0; i < 7; i++)
            sheet.autoSizeColumn(i);
        sheet.setColumnWidth(0, 8000);
        sheet.setColumnWidth(1, 8000);
        sheet.setColumnWidth(4, 7000);
        sheet.setColumnWidth(5, 9000);
    }

    private CellStyle mkHeaderStyle(Workbook wb, IndexedColors bg) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(f);
        s.setFillForegroundColor(bg.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        return s;
    }

    private CellStyle mkBgStyle(Workbook wb, IndexedColors bg) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(bg.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    /**
     * ⭐ NEW: Enhanced Summary Sheet với Current Status Statistics
     */
    private void createEnhancedSummarySheet(Sheet sheet, Workbook workbook, List<FolderReport> reports) {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        // Title
        Row titleRow = sheet.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("📊 DRIVE RECOVERY - ENHANCED REPORT");
        CellStyle titleStyle = workbook.createCellStyle();
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleStyle.setFont(titleFont);
        titleCell.setCellStyle(titleStyle);

        CellStyle greenStyle = workbook.createCellStyle();
        greenStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        greenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        CellStyle redStyle = workbook.createCellStyle();
        redStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        redStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Real statistics (currentStatus bị skip để tiết kiệm API — dùng action/status
        // thay thế)
        long totalFilesAll = 0, filesOKAll = 0, filesMissingAll = 0, filesMovedAll = 0, filesNotFoundAll = 0;
        long totalFoldersAll = 0, foldersOKAll = 0, foldersMissingAll = 0, foldersMovedAll = 0;
        for (FolderReport report : reports) {
            if (report.files != null) {
                totalFilesAll += report.files.size();
                filesOKAll += report.files.stream()
                        .filter(f -> "Có".equals(f.status) || "Trong subfolder".equals(f.status)).count();
                filesMissingAll += report.files.stream().filter(f -> "Thiếu".equals(f.status)).count();
                filesMovedAll += report.files.stream().filter(f -> f.action != null && f.action.startsWith("Đã move"))
                        .count();
                filesNotFoundAll += report.files.stream()
                        .filter(f -> f.action != null && f.action.startsWith("Lỗi: Không tìm thấy")).count();
            }
            if (report.subFolders != null) {
                totalFoldersAll += report.subFolders.size();
                foldersOKAll += report.subFolders.stream().filter(sf -> "Có".equals(sf.status)).count();
                foldersMissingAll += report.subFolders.stream().filter(sf -> "Thiếu".equals(sf.status)).count();
                foldersMovedAll += report.subFolders.stream()
                        .filter(sf -> sf.action != null && sf.action.startsWith("Đã move")).count();
            }
        }

        sheet.createRow(2).createCell(0).setCellValue("📈 Thống kê tổng hợp:");

        int row = 3;
        // FILE stats
        Row r1 = sheet.createRow(row++);
        r1.createCell(0).setCellValue("📄 Tổng số file trong activity:");
        r1.createCell(1).setCellValue(totalFilesAll);
        Row r2 = sheet.createRow(row++);
        r2.createCell(0).setCellValue("✅ File đang có (OK):");
        r2.createCell(1).setCellValue(filesOKAll);
        r2.getCell(0).setCellStyle(greenStyle);
        Row r3 = sheet.createRow(row++);
        r3.createCell(0).setCellValue("❌ File bị thiếu:");
        r3.createCell(1).setCellValue(filesMissingAll);
        r3.getCell(0).setCellStyle(filesMissingAll > 0 ? redStyle : greenStyle);
        Row r4 = sheet.createRow(row++);
        r4.createCell(0).setCellValue("🔄 File đã move về thành công:");
        r4.createCell(1).setCellValue(filesMovedAll);
        r4.getCell(0).setCellStyle(greenStyle);
        Row r5 = sheet.createRow(row++);
        r5.createCell(0).setCellValue("🔍 File không tìm thấy:");
        r5.createCell(1).setCellValue(filesNotFoundAll);
        r5.getCell(0).setCellStyle(filesNotFoundAll > 0 ? redStyle : greenStyle);
        row++;
        // FOLDER stats
        if (Config.getSearchFolders()) {
            Row rf1 = sheet.createRow(row++);
            rf1.createCell(0).setCellValue("📁 Tổng số subfolder trong activity:");
            rf1.createCell(1).setCellValue(totalFoldersAll);
            Row rf2 = sheet.createRow(row++);
            rf2.createCell(0).setCellValue("✅ Folder đang có (OK):");
            rf2.createCell(1).setCellValue(foldersOKAll);
            rf2.getCell(0).setCellStyle(greenStyle);
            Row rf3 = sheet.createRow(row++);
            rf3.createCell(0).setCellValue("❌ Folder bị thiếu:");
            rf3.createCell(1).setCellValue(foldersMissingAll);
            rf3.getCell(0).setCellStyle(foldersMissingAll > 0 ? redStyle : greenStyle);
            Row rf4 = sheet.createRow(row++);
            rf4.createCell(0).setCellValue("🔄 Folder đã move về thành công:");
            rf4.createCell(1).setCellValue(foldersMovedAll);
            rf4.getCell(0).setCellStyle(greenStyle);
            row++;
        }

        // Folder Summary table
        row++;
        Row headerRow = sheet.createRow(row++);
        String[] headers = { "Folder Path", "Folder ID", "Total Files", "Files OK", "Files Missing",
                "Files Recovered" };
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        for (FolderReport report : reports) {
            Row dataRow = sheet.createRow(row++);
            dataRow.createCell(0).setCellValue(report.folderPath);
            dataRow.createCell(1).setCellValue(report.folderId);

            if (report.error != null) {
                dataRow.createCell(2).setCellValue("ERROR");
                dataRow.createCell(3).setCellValue(report.error);
            } else if (report.files != null) {
                // Fix #3: Sử dụng đúng string value được set trong checkFolder(),
                // không phải display string ("Co", "Trong subfolder", "Thieu", "Da move")
                long totalFiles = report.files.size();
                long filesOK = report.files.stream()
                        .filter(f -> "Có".equals(f.status) || "Trong subfolder".equals(f.status))
                        .count();
                long filesMissing = report.files.stream()
                        .filter(f -> "Thieu".equals(f.status))
                        .count();
                long filesRecovered = report.files.stream()
                        .filter(f -> f.action != null && f.action.startsWith("Đã move"))
                        .count();

                dataRow.createCell(2).setCellValue(totalFiles);
                dataRow.createCell(3).setCellValue(filesOK);
                dataRow.createCell(4).setCellValue(filesMissing);
                dataRow.createCell(5).setCellValue(filesRecovered);
            }
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(0, 8000);
        sheet.setColumnWidth(1, 8000);
    }

    /**
     * ⭐ NEW: Files + Current Status Sheet
     */
    private void createFilesStatusSheet(Sheet sheet, Workbook workbook, List<FolderReport> reports) {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        Row headerRow = sheet.createRow(0);
        String[] headers = { "Folder Path", "File Name", "File ID", "🔍 CURRENT STATUS", "📍 Current Location",
                "🗑️ Trashed?", "Last Action" };
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        CellStyle greenBg = workbook.createCellStyle();
        greenBg.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        greenBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle yellowBg = workbook.createCellStyle();
        yellowBg.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        yellowBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle redBg = workbook.createCellStyle();
        redBg.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        redBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        int rowNum = 1;
        for (FolderReport report : reports) {
            if (report.files != null) {
                for (FileInfo file : report.files) {
                    Row row = sheet.createRow(rowNum++);
                    row.createCell(0).setCellValue(report.folderPath);
                    row.createCell(1).setCellValue(file.fileName);
                    row.createCell(2).setCellValue(file.fileId);

                    CurrentStatus status = file.currentStatus;
                    if (status != null) {
                        Cell statusCell = row.createCell(3);
                        statusCell.setCellValue(status.status);

                        if ("EXISTS".equals(status.statusCode)) {
                            statusCell.setCellStyle(greenBg);
                        } else if ("TRASHED".equals(status.statusCode)) {
                            statusCell.setCellStyle(yellowBg);
                        } else if ("DELETED".equals(status.statusCode) || "NO_ACCESS".equals(status.statusCode)) {
                            statusCell.setCellStyle(redBg);
                        }

                        row.createCell(4).setCellValue(status.location);
                        row.createCell(5).setCellValue(status.trashed ? "✓ YES" : "✗ NO");
                    } else {
                        row.createCell(3).setCellValue("N/A");
                        row.createCell(4).setCellValue("N/A");
                        row.createCell(5).setCellValue("N/A");
                    }

                    row.createCell(6).setCellValue(file.action);
                }
            }
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(0, 6000);
        sheet.setColumnWidth(1, 8000);
        sheet.setColumnWidth(2, 6000);
        sheet.setColumnWidth(3, 6000);
        sheet.setColumnWidth(4, 8000);
    }

    /**
     * ⭐ NEW: Deleted Files Only Sheet
     */
    private void createDeletedFilesSheet(Sheet sheet, Workbook workbook, List<FolderReport> reports) {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.RED.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        Row headerRow = sheet.createRow(0);
        String[] headers = { "Folder Path", "File Name", "File ID", "❌ Status", "📍 Location", "Last Action" };
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        CellStyle redBg = workbook.createCellStyle();
        redBg.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        redBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle yellowBg = workbook.createCellStyle();
        yellowBg.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        yellowBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        int rowNum = 1;
        boolean hasDeletedFiles = false;

        for (FolderReport report : reports) {
            if (report.files != null) {
                for (FileInfo file : report.files) {
                    // ⭐ FIX: currentStatus luôn null (skip để tiết kiệm API)
                    // → Dùng action/movedFrom để phát hiện file không khôi phục được
                    boolean isNotFound = "Thiếu".equals(file.status)
                            && file.action != null
                            && (file.action.contains("Không tìm thấy") || file.action.startsWith("Lỗi:"));
                    boolean isInTrash = file.action != null && file.action.contains("TRASH");

                    if (!isNotFound && !isInTrash)
                        continue;

                    hasDeletedFiles = true;
                    Row row = sheet.createRow(rowNum++);
                    row.createCell(0).setCellValue(report.folderPath);
                    row.createCell(1).setCellValue(file.fileName);
                    row.createCell(2).setCellValue(file.fileId);

                    Cell statusCell = row.createCell(3);
                    if (isInTrash) {
                        statusCell.setCellValue("🗑️ Trong Trash");
                        statusCell.setCellStyle(yellowBg);
                    } else {
                        statusCell.setCellValue("❌ Không tìm thấy");
                        statusCell.setCellStyle(redBg);
                    }

                    row.createCell(4).setCellValue(file.movedFrom != null ? file.movedFrom : "-");
                    row.createCell(5).setCellValue(file.action);
                }
            }
        }

        if (!hasDeletedFiles) {
            Row noDataRow = sheet.createRow(1);
            Cell cell = noDataRow.createCell(0);
            cell.setCellValue("✅ No deleted files found!");
            CellStyle greenStyle = workbook.createCellStyle();
            Font greenFont = workbook.createFont();
            greenFont.setBold(true);
            greenFont.setColor(IndexedColors.GREEN.getIndex());
            greenStyle.setFont(greenFont);
            cell.setCellStyle(greenStyle);
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(0, 6000);
        sheet.setColumnWidth(1, 8000);
        sheet.setColumnWidth(2, 6000);
    }

    /**
     * Excel sheet listing all SubFolder results (present/missing/moved).
     */
    private void createMissingFoldersSheet(Sheet sheet, Workbook workbook, List<FolderReport> reports) {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        CellStyle greenBg = workbook.createCellStyle();
        greenBg.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        greenBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle redBg = workbook.createCellStyle();
        redBg.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        redBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle yellowBg = workbook.createCellStyle();
        yellowBg.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        yellowBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        Row headerRow = sheet.createRow(0);
        String[] headers = { "Parent Folder Path", "Subfolder Name", "Subfolder ID", "Status", "Action", "Moved From",
                "Last Seen" };
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowNum = 1;
        boolean hasAny = false;
        for (FolderReport report : reports) {
            if (report.subFolders == null || report.subFolders.isEmpty())
                continue;
            for (SubFolderInfo sf : report.subFolders) {
                hasAny = true;
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(report.folderPath);
                row.createCell(1).setCellValue(sf.folderName);
                row.createCell(2).setCellValue(sf.folderId);

                Cell statusCell = row.createCell(3);
                statusCell.setCellValue(sf.status);
                if ("Có".equals(sf.status)) {
                    statusCell.setCellStyle(greenBg);
                } else {
                    statusCell.setCellStyle(redBg);
                }

                Cell actionCell = row.createCell(4);
                actionCell.setCellValue(sf.action != null ? sf.action : "-");
                if (sf.action != null && sf.action.startsWith("Đã move")) {
                    actionCell.setCellStyle(greenBg);
                } else if (sf.action != null && sf.action.startsWith("Không tìm thấy")) {
                    actionCell.setCellStyle(redBg);
                }

                row.createCell(5).setCellValue(sf.movedFrom != null ? sf.movedFrom : "-");
                row.createCell(6).setCellValue(sf.lastSeen != null ? sf.lastSeen : "N/A");
            }
        }

        if (!hasAny) {
            Row noData = sheet.createRow(1);
            noData.createCell(0)
                    .setCellValue("No folder activity found (searchFolders may be disabled or no folder events)");
        }

        for (int i = 0; i < headers.length; i++)
            sheet.autoSizeColumn(i);
        sheet.setColumnWidth(0, 7000);
        sheet.setColumnWidth(1, 7000);
        sheet.setColumnWidth(2, 6000);
    }

    private void createDetailSheet(Sheet sheet, FolderReport report, Workbook workbook) {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        CellStyle titleStyle = workbook.createCellStyle();
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 12);
        titleStyle.setFont(titleFont);

        Row titleRow = sheet.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("FOLDER: " + report.folderPath);
        titleCell.setCellStyle(titleStyle);

        Row idRow = sheet.createRow(1);
        idRow.createCell(0).setCellValue("Folder ID: " + report.folderId);

        Row headerRow = sheet.createRow(3);
        String[] headers = { "File Name", "File ID", "Status", "Action", "Moved From", "Last Seen", "Current Status" };
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        if (report.files != null) {
            int rowNum = 4;
            for (FileInfo file : report.files) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(file.fileName);
                row.createCell(1).setCellValue(file.fileId);
                row.createCell(2).setCellValue(file.status);
                row.createCell(3).setCellValue(file.action);
                row.createCell(4).setCellValue(file.movedFrom != null ? file.movedFrom : "-");
                row.createCell(5).setCellValue(file.lastSeen != null ? file.lastSeen : "N/A");

                // ⭐ Current Status
                if (file.currentStatus != null) {
                    row.createCell(6).setCellValue(file.currentStatus.status);
                } else {
                    row.createCell(6).setCellValue("N/A");
                }
            }
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(0, 8000);
        sheet.setColumnWidth(1, 8000);
        sheet.setColumnWidth(4, 6000);
        sheet.setColumnWidth(5, 5000);
        sheet.setColumnWidth(6, 6000);
    }

    // ============================================
    // INNER CLASSES
    // ============================================

    // static class MoveResult {
    // boolean success;
    // String reason;
    // String movedFrom;
    // }

    static class MoveResult {
        boolean success; // true = operation ok (move success hoặc đã đúng chỗ)
        boolean actuallyMoved; // true = folder/file đã được di chuyển thật sự (dùng để quyết định đệ quy)
        boolean inTrash; // true = item đang trong Thùng rác → KHÔNG move, chỉ báo cáo
        String reason;
        String movedFrom;

        MoveResult() {
            this.success = false;
            this.actuallyMoved = false;
            this.inTrash = false;
            this.reason = "";
            this.movedFrom = "";
        }
    }

    static class FolderInfo {
        String id;
        String name;
        String path;
    }

    static class FileHistory {
        String id;
        String name;
        boolean everInFolder;
        boolean currentlyInFolder;
        String lastSeenTimestamp;
        /**
         * true = file/folder đã bị xóa vĩnh viễn (404 khi verify CREATE event).
         * Dùng để bỏ qua vòng tìm kiếm và ghi thẳng vào báo cáo là "Đã xóa vĩnh viễn".
         */
        boolean permanentlyDeleted;
        /**
         * true = folder bị DELETE (xóa vào Trash hoặc xóa vĩnh viễn) bởi event DELETE.
         * Không biết chắc là direct child hay grandchild của parent folder.
         * Cần verify qua Drive API: nếu đang trong Trash → restore được, không tìm thấy
         * → xóa vĩnh viễn.
         */
        boolean deletedFromSubtree;
    }

    static class FolderReport {
        String folderPath;
        String folderId;
        List<FileInfo> files;
        List<SubFolderInfo> subFolders;
        String error;
    }

    static class FileInfo {
        String fileName;
        String fileId;
        String status;
        String action;
        String movedFrom;
        String lastSeen;
        CurrentStatus currentStatus; // ⭐ NEW
    }

    /**
     * ⭐ NEW: Current Status class
     */
    static class CurrentStatus {
        String statusCode; // EXISTS, TRASHED, DELETED, NO_ACCESS, ERROR
        String status; // Display text
        String location; // Current location
        boolean trashed; // Is in trash?

        CurrentStatus(String statusCode, String status, String location, boolean trashed) {
            this.statusCode = statusCode;
            this.status = status;
            this.location = location;
            this.trashed = trashed;
        }
    }

    static class SubFolderInfo {
        String folderName;
        String folderId;
        String status; // "' Có" / "' Thiếu"
        String action; // "' Đã move" / "' Không tìm thấy" / "-"
        String movedFrom;
        String lastSeen;
    }

    /**
     * Parse ISO timestamp với fallback: thử có milliseconds trước, rồi không có
     * milliseconds.
     * Activity API đôi khi trả về "2026-05-02T23:59:19Z" (không có .SSS).
     */
    private java.util.Date parseIsoTimestamp(String ts,
            java.text.SimpleDateFormat parserWithMs,
            java.text.SimpleDateFormat parserNoMs) throws java.text.ParseException {
        try {
            return parserWithMs.parse(ts);
        } catch (java.text.ParseException e) {
            return parserNoMs.parse(ts);
        }
    }
}
