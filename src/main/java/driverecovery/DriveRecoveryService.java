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
    // ⭐ FIX LANG: Cache My Drive root ID — dùng ID thay vì tên "My Drive" để không
    // bị lỗi khi tài khoản dùng ngôn ngữ khác (vd: tiếng Việt → "Drive của tôi").
    // Lazy-init, thread-safe qua volatile + double-checked locking.
    private volatile String cachedMyDriveRootId = null;

    // ⭐ FIX 429: Semaphore đảm bảo chỉ 1 thread gọi Activity API tại một thời điểm.
    // Activity API quota là "per user per minute" → 3 threads cùng gọi = 3x quota
    // consumption.
    private final java.util.concurrent.Semaphore activityApiSemaphore = new java.util.concurrent.Semaphore(1);

    // Fix #10: Cache HTTP transport — GoogleNetHttpTransport.newTrustedTransport() là expensive
    // (đọc TrustStore từ disk). Dùng chung 1 instance xuyên suốt vòng đời service.
    private static volatile com.google.api.client.http.HttpTransport cachedHttpTransport = null;
    private static final Object transportLock = new Object();

    private static com.google.api.client.http.HttpTransport getHttpTransport() {
        if (cachedHttpTransport == null) {
            synchronized (transportLock) {
                if (cachedHttpTransport == null) {
                    try {
                        cachedHttpTransport = com.google.api.client.googleapis.javanet.GoogleNetHttpTransport.newTrustedTransport();
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to create HTTP transport", e);
                    }
                }
            }
        }
        return cachedHttpTransport;
    }

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
            System.out.println("\n⏳ Đang đợi tất cả threads hoàn thành (không giới hạn thời gian)...");

            executor.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS);

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
        // ⭐ FIX BUG 5: Đảo ngược → xử lý folder sâu nhất trước (deepest-first), giống
        // Mode 1.
        // Tránh race condition khi folder cha và con cùng có item cần recover.
        Collections.reverse(allFolders);
        System.out.println("✓ Tìm thấy " + allFolders.size() + " folder (deepest-first)\n");

        // ── Xử lý tuần tự deepest-first (giống Mode 1) để đảm bảo đúng thứ tự ────
        // FIX #4: Mode 2 cũng phải dùng threadCount=1 như Mode 1.
        // Parallel processing gây race condition khi folder cha và folder con cùng xử
        // lý.
        int threadCount = 1;
        System.out.println("🚀 Bắt đầu xử lý tuần tự deepest-first với " + threadCount + " thread...\n");

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
            System.out.println("\n⏳ Đang đợi tất cả threads hoàn thành (không giới hạn thời gian)...");

            executor.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS);

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
     * ⭐ FIX LANG: Lấy ID thực của My Drive root (language-independent).
     * Drive API luôn chấp nhận keyword "root" → dùng để resolve ra ID thực.
     * Cache lại để không gọi API nhiều lần.
     */
    private String getMyDriveRootId() {
        if (cachedMyDriveRootId != null) return cachedMyDriveRootId;
        synchronized (this) {
            if (cachedMyDriveRootId != null) return cachedMyDriveRootId;
            try {
                File root = driveService.files().get("root")
                        .setFields("id")
                        .execute();
                cachedMyDriveRootId = root.getId();
            } catch (Exception e) {
                // Không lấy được → trả về null, caller xử lý tiếp
            }
        }
        return cachedMyDriveRootId;
    }

    /**
     * Kiểm tra một folder ID có phải My Drive root không (language-independent).
     * So sánh bằng ID thực thay vì tên "My Drive" / "Drive của tôi".
     */
    private boolean isMyDriveRoot(String folderId) {
        if (folderId == null) return false;
        if ("root".equals(folderId)) return true;
        String rootId = getMyDriveRootId();
        return rootId != null && rootId.equals(folderId);
    }

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
                // ⭐ FIX LANG: Dừng khi lên đến My Drive root — dùng ID thay vì tên
                // để không bị lỗi khi UI tiếng Việt ("Drive của tôi" thay vì "My Drive")
                if (isMyDriveRoot(nextId))
                    break;
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

                // ⭐ Layer-2: pre-compute batch deleted folder IDs
                java.util.Set<String> batchDeletedFolderIds = mergedFolders.stream()
                        .filter(h -> h.deletedFromSubtree && !h.everInFolder)
                        .map(h -> h.id)
                        .collect(java.util.stream.Collectors.toSet());

                folderLoop: for (FileHistory fh : mergedFolders) {
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
                        // ── BỊ XÓA qua DELETE event — 3-layer resolution ──
                        if ("PERMANENT_DELETE".equals(fh.deleteType)) {
                            missingFolderCount++;
                            sfInfo.status = "Đã xóa vĩnh viễn";
                            sfInfo.action = "Permanent delete — không thể phục hồi";
                            sfInfo.movedFrom = "-";
                            pt.log("  │  ❌ Xóa vĩnh viễn │  " + fh.name + "  (PERMANENT_DELETE — bỏ qua)",
                                    ProgressTracker.LogLevel.WARNING);
                        } else {
                            missingFolderCount++;
                            sfInfo.status = "Bị xóa";
                            pt.log("  │  🗑️ DELETE event │  " + fh.name + "  →  đang resolve parent...",
                                    ProgressTracker.LogLevel.WARNING);
                            java.util.Set<String> otherIds = batchDeletedFolderIds.stream()
                                    .filter(id -> !id.equals(fh.id))
                                    .collect(java.util.stream.Collectors.toSet());
                            ParentResolution resolution = resolveTrueParent(fh.id, folder.id, otherIds);
                            if (resolution == ParentResolution.NESTED_IN_BATCH) {
                                sfInfo.status = "Trong subfolder khác";
                                sfInfo.action = "Không move — xác nhận nằm trong subfolder đã bị xóa";
                                sfInfo.movedFrom = "-";
                                pt.log("  │           │    ↳ ℹ️  Nested — bỏ qua, không flatten",
                                        ProgressTracker.LogLevel.INFO);
                                // ⭐ FIX: Nếu parent thật là folder ALIVE (không trong batch)
                                // -> cần gọi checkFolder(parent) đệ quy để xử lý item bị xóa trong đó
                                String actualParent = queryLastKnownParent_Layer1(fh.id);
                                if (actualParent != null
                                        && !batchDeletedFolderIds.contains(actualParent)
                                        && processedFolderIds.add(actualParent)) {
                                    try {
                                        com.google.api.services.drive.model.File pMeta = driveService.files()
                                                .get(actualParent).setFields("id, name, trashed")
                                                .setSupportsAllDrives(true).execute();
                                        if (pMeta != null && !Boolean.TRUE.equals(pMeta.getTrashed())) {
                                            pt.log("  │  [LIVE-PARENT] " + pMeta.getName()
                                                    + " -> checkFolder đệ quy", ProgressTracker.LogLevel.INFO);
                                            FolderInfo liveParent = new FolderInfo();
                                            liveParent.id = actualParent;
                                            liveParent.name = pMeta.getName();
                                            liveParent.path = folder.path + "/" + pMeta.getName();
                                            try {
                                                FolderReport sr = checkFolder(liveParent, userEmail);
                                                allReports.add(sr);
                                            } catch (Exception exLP) {
                                                pt.log("  ⚠️ checkFolder live-parent lỗi: " + exLP.getMessage(),
                                                        ProgressTracker.LogLevel.WARNING);
                                            }
                                        }
                                    } catch (Exception exLP) {
                                        pt.log("  ⚠️ Verify parent " + actualParent + " lỗi: " + exLP.getMessage(),
                                                ProgressTracker.LogLevel.WARNING);
                                    }
                                }
                            } else if (resolution == ParentResolution.CONFIRMED_DIRECT) {
                                pt.log("  │           │    ↳ ✅ Direct child → verify Drive API",
                                        ProgressTracker.LogLevel.INFO);
                                try {
                                    com.google.api.services.drive.model.File deletedFolder = null;
                                    boolean verifyFailed = false;
                                    try {
                                        deletedFolder = driveService.files().get(fh.id)
                                                .setFields(
                                                        "id, name, trashed, explicitlyTrashed, parents, owners, driveId")
                                                .setSupportsAllDrives(true).execute();
                                    } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException gje) {
                                        verifyFailed = true;
                                        sfInfo.status = gje.getStatusCode() == 404 ? "Đã xóa vĩnh viễn" : "Lỗi verify";
                                        sfInfo.action = gje.getStatusCode() == 404 ? "404 — không thể phục hồi"
                                                : "Lỗi verify: HTTP " + gje.getStatusCode();
                                        sfInfo.movedFrom = "-";
                                        pt.log("  │           │    ↳ " + (gje.getStatusCode() == 404
                                                ? "❌ 404 — đã xóa vĩnh viễn"
                                                : "⚠️  Lỗi HTTP " + gje.getStatusCode()),
                                                ProgressTracker.LogLevel.WARNING);
                                    }
                                    if (!verifyFailed && deletedFolder != null) {
                                        boolean inTrash = Boolean.TRUE.equals(deletedFolder.getTrashed())
                                                || Boolean.TRUE.equals(deletedFolder.getExplicitlyTrashed());
                                        String ownerInfo = (deletedFolder.getOwners() != null
                                                && !deletedFolder.getOwners().isEmpty())
                                                        ? deletedFolder.getOwners().get(0).getEmailAddress()
                                                        : "unknown";
                                        if (inTrash) {
                                            List<String> curParents = deletedFolder.getParents() != null
                                                    ? deletedFolder.getParents()
                                                    : java.util.List.of();
                                            boolean restored = restoreFromTrashAndMove(fh.id, curParents, folder.id);
                                            if (restored) {
                                                sfInfo.status = "Đã restore";
                                                sfInfo.action = "Restore từ Trash → move về " + folder.path;
                                                sfInfo.movedFrom = "Trash (" + ownerInfo + ")";
                                                pt.log("  │           │    ↳ ✅ Restore từ Trash thành công",
                                                        ProgressTracker.LogLevel.SUCCESS);
                                                globalRecoveredIds.add(fh.id);
                                                if (processedFolderIds.add(fh.id)) {
                                                    FolderInfo ri = new FolderInfo();
                                                    ri.id = fh.id;
                                                    ri.name = fh.name;
                                                    ri.path = folder.path + "/" + fh.name;
                                                    try {
                                                        FolderReport sr = checkFolder(ri, userEmail);
                                                        allReports.add(sr);
                                                    } catch (Exception ex) {
                                                        pt.log("  ⚠️ Lỗi đệ quy: " + ex.getMessage(),
                                                                ProgressTracker.LogLevel.WARNING);
                                                    }
                                                }
                                            } else {
                                                sfInfo.status = "Trong Thùng rác";
                                                sfInfo.action = "Không restore được — cần xử lý thủ công";
                                                sfInfo.movedFrom = "Trash (" + ownerInfo + ")";
                                                pt.log("  │           │    ↳ ⚠️  Restore thất bại",
                                                        ProgressTracker.LogLevel.WARNING);
                                            }
                                        } else {
                                            pt.log("  │           │    ↳ ✅ Folder vẫn tồn tại → move về...",
                                                    ProgressTracker.LogLevel.INFO);
                                            MoveResult mr = findAndMoveFolderWithResult(fh, folder.id, folder.path,
                                                    userEmail);
                                            sfInfo.status = mr.success ? "Đã move về" : "Thiếu";
                                            sfInfo.action = mr.success ? "Đã move" : "Không move được: " + mr.reason;
                                            sfInfo.movedFrom = mr.movedFrom != null ? mr.movedFrom : "-";
                                            if (mr.actuallyMoved && processedFolderIds.add(fh.id)) {
                                                FolderInfo ri = new FolderInfo();
                                                ri.id = fh.id;
                                                ri.name = fh.name;
                                                ri.path = folder.path + "/" + fh.name;
                                                try {
                                                    FolderReport sr = checkFolder(ri, userEmail);
                                                    allReports.add(sr);
                                                } catch (Exception ex) {
                                                    pt.log("  ⚠️ Lỗi đệ quy: " + ex.getMessage(),
                                                            ProgressTracker.LogLevel.WARNING);
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    sfInfo.action = "Lỗi: " + e.getMessage();
                                    sfInfo.movedFrom = "-";
                                    pt.log("  │           │    ↳ ❌ Lỗi: " + e.getMessage(),
                                            ProgressTracker.LogLevel.ERROR);
                                }
                            } else {
                                sfInfo.status = "Không xác định parent";
                                sfInfo.action = "Cần review thủ công — Không đủ event xác định folder cha";
                                sfInfo.movedFrom = "-";
                                pt.log("  │           │    ↳ ❓ UNKNOWN — Phương án A: không move",
                                        ProgressTracker.LogLevel.WARNING);
                            }
                        }

                    } else {
                        // ── THIẾU: không ở direct child, không ở subtree → cần tìm & move về ──
                        sfInfo.status = "Thiếu";
                        pt.log("  │  ❌ Thiếu │  " + fh.name + "  →  đang tìm...", ProgressTracker.LogLevel.WARNING);
                        try {
                            // ⭐ Fix A: Safety pre-check cho FOLDER — verify folder THỰC SỰ
                            // không nằm trong target subtree trước khi move.
                            // Lý do: allDescendantIds (từ getAllSubfolderIds) có thể không đầy đủ
                            // → subfolder đang đúng chỗ bị nhầm là "Thiếu".
                            try {
                                com.google.api.services.drive.model.File folderPreCheck = driveService.files()
                                        .get(fh.id)
                                        .setFields("id, parents, trashed")
                                        .setSupportsAllDrives(true)
                                        .execute();
                                if (folderPreCheck.getParents() != null) {
                                    for (String p : folderPreCheck.getParents()) {
                                        if (p.equals(folder.id)) {
                                            // Folder thực sự là direct child — merge/cache issue
                                            presentFolders++;
                                            sfInfo.status = "Có";
                                            sfInfo.action = "-";
                                            sfInfo.movedFrom = "-";
                                            pt.log("  │  ✅ Pre-check: Folder đang ở DIRECT CHILD (timing)  │  " + fh.name,
                                                    ProgressTracker.LogLevel.INFO);
                                            report.subFolders.add(sfInfo);
                                            continue folderLoop;
                                        }
                                        if (allDescendantIds.contains(p) || isDescendantOf(p, folder.id)) {
                                            // Folder đang trong subtree — allDescendantIds thiếu
                                            allDescendantIds.add(p);
                                            inSubtreeCount++;
                                            sfInfo.status = "Trong subfolder con";
                                            sfInfo.action = "Không cần move (verified bằng ancestor walk)";
                                            sfInfo.movedFrom = "-";
                                            pt.log("  │  📂 Pre-check: Folder trong subtree SÂU (ancestor walk)  │  " + fh.name,
                                                    ProgressTracker.LogLevel.INFO);
                                            report.subFolders.add(sfInfo);
                                            continue folderLoop;
                                        }
                                    }
                                }
                            } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException folderPreEx) {
                                if (folderPreEx.getStatusCode() == 404) {
                                    missingFolderCount++;
                                    sfInfo.action = "404 — folder đã bị xóa vĩnh viễn (pre-check)";
                                    sfInfo.movedFrom = "-";
                                    pt.log("  │  ❌ Pre-check 404: folder đã bị xóa vĩnh viễn  │  " + fh.name,
                                            ProgressTracker.LogLevel.WARNING);
                                    report.subFolders.add(sfInfo);
                                    continue folderLoop;
                                }
                                // Khác → tiếp tục recovery
                            } catch (Exception folderPreEx) {
                                // Không pre-check được → tiếp tục recovery
                            }

                            MoveResult mr = findAndMoveFolderWithResult(fh, folder.id, folder.path, userEmail);
                            sfInfo.movedFrom = mr.movedFrom != null ? mr.movedFrom : "-";

                            if (mr.inTrash) {
                                // ── TRONG THÙNG RÁC → chỉ báo cáo, KHÔNG move ──
                                missingFolderCount++;
                                sfInfo.status = "Trong Thùng rác";
                                sfInfo.action = "Đang trong Thùng rác — không tự động move";
                                pt.log("  │           │    ↳ 🗑️  Folder trong TRASH — bỏ qua, không move",
                                        ProgressTracker.LogLevel.WARNING);

                            } else if (mr.isSkipped) {
                                // ── BỎ QUA HỢP LỆ (SIBLING / grandchild / cross-user) ──
                                // Không đếm vào missingFolderCount — đây KHÔNG phải folder bị mất
                                sfInfo.status = "Bỏ qua (hợp lệ)";
                                sfInfo.action = mr.reason;
                                pt.log("  │           │    ↳ ⏭️  Bỏ qua hợp lệ: " + mr.reason,
                                        ProgressTracker.LogLevel.INFO);

                            } else if (mr.success) {
                                // ── MOVE THÀNH CÔNG ──
                                missingFolderCount++;
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
                                missingFolderCount++;
                                sfInfo.action = "Không tìm thấy: " + mr.reason;
                                pt.log("  │           │    ↳ ⚠️  " + mr.reason, ProgressTracker.LogLevel.WARNING);
                            }

                        } catch (Exception e) {
                            missingFolderCount++;
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

        // ⭐ Layer-2: pre-compute batch deleted file IDs
        java.util.Set<String> batchDeletedFileIds = mergedFiles.stream()
                .filter(h -> h.deletedFromSubtree && !h.everInFolder)
                .map(h -> h.id)
                .collect(java.util.stream.Collectors.toSet());

        fileLoop: for (FileHistory fileHistory : mergedFiles) {
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

            // CASE 2.5: File bị DELETE qua DELETE event — 3-layer resolution
            if (fileHistory.deletedFromSubtree && !fileHistory.everInFolder) {
                if ("PERMANENT_DELETE".equals(fileHistory.deleteType)) {
                    missingCount++;
                    fileInfo.status = "Đã xóa vĩnh viễn";
                    fileInfo.action = "Permanent delete — không thể phục hồi";
                    fileInfo.movedFrom = "-";
                    fileInfo.currentStatus = new CurrentStatus("DELETED", "❌ PERMANENTLY DELETED", "-", false);
                    ptf.log("  │  ❌ Xóa vĩnh viễn     │  " + fileHistory.name + "  (PERMANENT_DELETE — bỏ qua)",
                            ProgressTracker.LogLevel.WARNING);
                } else {
                    missingCount++;
                    fileInfo.status = "Bị xóa";
                    ptf.log("  │  🗑️ DELETE event    │  " + fileHistory.name + "  →  đang resolve parent...",
                            ProgressTracker.LogLevel.WARNING);
                    java.util.Set<String> otherIds = batchDeletedFileIds.stream()
                            .filter(id -> !id.equals(fileHistory.id))
                            .collect(java.util.stream.Collectors.toSet());
                    ParentResolution resolution = resolveTrueParent(fileHistory.id, folder.id, otherIds);
                    if (resolution == ParentResolution.NESTED_IN_BATCH) {
                        fileInfo.status = "Trong subfolder khác";
                        fileInfo.action = "Không move — xác nhận nằm trong subfolder đã bị xóa";
                        fileInfo.movedFrom = "-";
                        fileInfo.currentStatus = new CurrentStatus("NESTED", "ℹ️ NESTED IN BATCH", "-", false);
                        ptf.log("  │                  │    ↳ ℹ️  Nested — bỏ qua", ProgressTracker.LogLevel.INFO);
                        // ⭐ FIX: Nếu parent thật là folder ALIVE -> checkFolder(parent)
                        String actualParentId = queryLastKnownParent_Layer1(fileHistory.id);
                        if (actualParentId != null
                                && !batchDeletedFileIds.contains(actualParentId)
                                && processedFolderIds.add(actualParentId)) {
                            try {
                                com.google.api.services.drive.model.File pMeta2 = driveService.files()
                                        .get(actualParentId).setFields("id, name, trashed")
                                        .setSupportsAllDrives(true).execute();
                                if (pMeta2 != null && !Boolean.TRUE.equals(pMeta2.getTrashed())) {
                                    ptf.log("  │  [LIVE-PARENT] " + pMeta2.getName()
                                            + " -> checkFolder đệ quy", ProgressTracker.LogLevel.INFO);
                                    FolderInfo liveParent2 = new FolderInfo();
                                    liveParent2.id = actualParentId;
                                    liveParent2.name = pMeta2.getName();
                                    liveParent2.path = folder.path + "/" + pMeta2.getName();
                                    try {
                                        FolderReport sr2 = checkFolder(liveParent2, userEmail);
                                        allReports.add(sr2);
                                    } catch (Exception exLP) {
                                        ptf.log("  ⚠️ checkFolder live-parent lỗi: " + exLP.getMessage(),
                                                ProgressTracker.LogLevel.WARNING);
                                    }
                                }
                            } catch (Exception exLP) {
                                ptf.log("  ⚠️ Verify parent " + actualParentId + " lỗi: " + exLP.getMessage(),
                                        ProgressTracker.LogLevel.WARNING);
                            }
                        }
                    } else if (resolution == ParentResolution.CONFIRMED_DIRECT) {
                        ptf.log("  │                  │    ↳ ✅ Direct child → verify Drive API",
                                ProgressTracker.LogLevel.INFO);
                        try {
                            com.google.api.services.drive.model.File deletedFile = null;
                            boolean verifyFailed = false;
                            try {
                                deletedFile = driveService.files().get(fileHistory.id)
                                        .setFields("id, name, trashed, explicitlyTrashed, parents, owners")
                                        .setSupportsAllDrives(true).execute();
                            } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException gje) {
                                verifyFailed = true;
                                if (gje.getStatusCode() == 404) {
                                    fileInfo.status = "Đã xóa vĩnh viễn";
                                    fileInfo.action = "404 — không thể phục hồi";
                                    fileInfo.movedFrom = "-";
                                    fileInfo.currentStatus = new CurrentStatus("DELETED", "❌ PERMANENTLY DELETED", "-",
                                            false);
                                } else {
                                    fileInfo.action = "Lỗi verify: HTTP " + gje.getStatusCode();
                                    fileInfo.movedFrom = "-";
                                    fileInfo.currentStatus = getCurrentFileStatus(fileHistory.id);
                                }
                                ptf.log("  │                  │    ↳ ⚠️  HTTP " + gje.getStatusCode(),
                                        ProgressTracker.LogLevel.WARNING);
                            }
                            if (!verifyFailed && deletedFile != null) {
                                boolean inTrash = Boolean.TRUE.equals(deletedFile.getTrashed())
                                        || Boolean.TRUE.equals(deletedFile.getExplicitlyTrashed());
                                String ownerInfo = (deletedFile.getOwners() != null
                                        && !deletedFile.getOwners().isEmpty())
                                                ? deletedFile.getOwners().get(0).getEmailAddress()
                                                : "unknown";
                                if (inTrash) {
                                    List<String> curParents = deletedFile.getParents() != null
                                            ? deletedFile.getParents()
                                            : java.util.List.of();
                                    boolean restored = restoreFromTrashAndMove(fileHistory.id, curParents, folder.id);
                                    if (restored) {
                                        fileInfo.status = "Đã restore";
                                        fileInfo.action = "Restore từ Trash → move về " + folder.path;
                                        fileInfo.movedFrom = "Trash (" + ownerInfo + ")";
                                        fileInfo.currentStatus = new CurrentStatus("MOVED", "✅ ĐÃ RESTORE & MOVE",
                                                folder.path, false);
                                        ptf.log("  │                  │    ↳ ✅ Restore từ Trash thành công",
                                                ProgressTracker.LogLevel.SUCCESS);
                                    } else {
                                        fileInfo.status = "Trong Thùng rác";
                                        fileInfo.action = "Không restore được";
                                        fileInfo.movedFrom = "Trash (" + ownerInfo + ")";
                                        fileInfo.currentStatus = new CurrentStatus("TRASHED", "🗑️ IN TRASH",
                                                fileInfo.movedFrom, true);
                                        ptf.log("  │                  │    ↳ ⚠️  Restore thất bại",
                                                ProgressTracker.LogLevel.WARNING);
                                    }
                                } else {
                                    MoveResult mr = findAndMoveFileWithResult(fileHistory, folder.id, folder.path,
                                            userEmail, subfolderIds);
                                    fileInfo.movedFrom = mr.movedFrom != null ? mr.movedFrom : "-";
                                    if (mr.success) {
                                        fileInfo.status = "Đã move về";
                                        fileInfo.action = "Đã move";
                                        fileInfo.currentStatus = new CurrentStatus("MOVED", "✅ ĐÃ MOVE VỀ ĐÚNG CHỖ",
                                                folder.path, false);
                                        ptf.log("  │                  │    ↳ ✅ Move thành công từ: "
                                                + fileInfo.movedFrom,
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
                            ptf.log("  │                  │    ↳ ❌ Lỗi: " + e.getMessage(),
                                    ProgressTracker.LogLevel.ERROR);
                        }
                    } else {
                        fileInfo.status = "Không xác định parent";
                        fileInfo.action = "Cần review thủ công — Không đủ event xác định folder cha";
                        fileInfo.movedFrom = "-";
                        fileInfo.currentStatus = new CurrentStatus("UNKNOWN", "❓ KHÔNG XÁC ĐỊNH PARENT", "-", false);
                        ptf.log("  │                  │    ↳ ❓ UNKNOWN — Phương án A: không move",
                                ProgressTracker.LogLevel.WARNING);
                    }
                }
                report.files.add(fileInfo);
                continue;
            }

            // CASE 3: File thiếu → cần tìm & move
            fileInfo.status = "Thiếu";
            ptf.log("  │  ❌ Thiếu         │  " + fileHistory.name + "  →  đang tìm...",
                    ProgressTracker.LogLevel.WARNING);

            // ⭐ FIX: File đã bị xóa vĩnh viễn (404 khi verify CREATE) →
            // Không cần tìm kiếm trong Drive, ghi thẳng vào báo cáo.
            if (fileHistory.permanentlyDeleted) {
                missingCount++;
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
                // ⭐ FIX A+B: Safety pre-check — verify file THỰC SỰ không nằm trong target subtree
                // trước khi gọi findAndMoveFileWithResult.
                //
                // Nguyên nhân bug gốc:
                //   - setAncestorName() trả về activity của TOÀN BỘ cây thư mục (không chỉ direct children)
                //   - File F nằm trong Target/SubfolderA → có MOVE event cũ: removedParents=[Target]
                //   - processActivity đánh dấu F là everInFolder=true, currentlyInFolder=false
                //   - subfolderIds có thể không đầy đủ (Drive API miss một số subfolder sâu)
                //   - Kết quả: F bị nhầm là "Thiếu" và bị move về Target root (hoặc tệ hơn: ra My Drive root)
                //
                // Fix: Fetch vị trí thực tế qua Drive API. Nếu file đang trong subtree → skip.
                boolean confirmedNotInSubtree = false;
                try {
                    com.google.api.services.drive.model.File preCheck = driveService.files()
                            .get(fileHistory.id)
                            .setFields("id, parents, trashed")
                            .setSupportsAllDrives(true)
                            .execute();
                    if (preCheck.getParents() != null) {
                        for (String p : preCheck.getParents()) {
                            if (p.equals(folder.id)) {
                                // File thực sự là direct child — race condition giữa currentFileIds và loop
                                presentFiles++;
                                fileInfo.status = "Có";
                                fileInfo.action = "-";
                                fileInfo.movedFrom = "-";
                                fileInfo.currentStatus = null;
                                ptf.log("  │  ✅ Pre-check: File đang ở DIRECT CHILD (timing)  │  " + fileHistory.name,
                                        ProgressTracker.LogLevel.INFO);
                                report.files.add(fileInfo);
                                continue fileLoop;
                            }
                            if (subfolderIds.contains(p)) {
                                // File đang trong subfolder đã biết
                                inSubfolder++;
                                fileInfo.status = "Trong subfolder";
                                fileInfo.action = "Không cần move";
                                fileInfo.movedFrom = "-";
                                fileInfo.currentStatus = null;
                                ptf.log("  │  📂 Pre-check: File trong subfolder (known)  │  " + fileHistory.name,
                                        ProgressTracker.LogLevel.INFO);
                                report.files.add(fileInfo);
                                continue fileLoop;
                            }
                            // ⭐ FIX B: Parent không có trong subfolderIds (cache thiếu) →
                            // leo lên ancestor chain để kiểm tra có nằm trong target subtree không
                            if (isDescendantOf(p, folder.id)) {
                                subfolderIds.add(p); // cập nhật cache để các file sau dùng được
                                inSubfolder++;
                                fileInfo.status = "Trong subfolder";
                                fileInfo.action = "Không cần move (verified bằng ancestor walk)";
                                fileInfo.movedFrom = "-";
                                fileInfo.currentStatus = null;
                                ptf.log("  │  📂 Pre-check: File trong subfolder SÂU (ancestor walk)  │  " + fileHistory.name,
                                        ProgressTracker.LogLevel.INFO);
                                report.files.add(fileInfo);
                                continue fileLoop;
                            }
                        }
                    }
                    // Đã pre-check xong, file THỰC SỰ không nằm trong target subtree → tiến hành recovery
                    confirmedNotInSubtree = true;
                } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException preEx) {
                    if (preEx.getStatusCode() == 404) {
                        // File đã bị xóa vĩnh viễn (phát hiện qua pre-check)
                        missingCount++;
                        fileInfo.action = "404 — file đã bị xóa vĩnh viễn (phát hiện qua pre-check)";
                        fileInfo.movedFrom = "-";
                        fileInfo.currentStatus = new CurrentStatus("DELETED", "❌ PERMANENTLY DELETED", "-", false);
                        ptf.log("  │  ❌ Pre-check 404: file đã bị xóa vĩnh viễn  │  " + fileHistory.name,
                                ProgressTracker.LogLevel.WARNING);
                        report.files.add(fileInfo);
                        continue fileLoop;
                    }
                    // HTTP khác (403, 5xx) → không pre-check được, tiếp tục recovery bình thường
                    confirmedNotInSubtree = true;
                } catch (Exception preEx) {
                    // Không pre-check được → tiếp tục recovery
                    confirmedNotInSubtree = true;
                }

                MoveResult moveResult = findAndMoveFileWithResult(fileHistory, folder.id, folder.path, userEmail,
                        subfolderIds);

                if (moveResult.inTrash) {
                    // ── TRONG THÙNG RÁC → chỉ báo cáo, KHÔNG move ──
                    missingCount++;
                    fileInfo.status = "Trong Thùng rác";
                    fileInfo.action = "Đang trong Thùng rác — không tự động move";
                    fileInfo.movedFrom = moveResult.movedFrom != null ? moveResult.movedFrom : "Trash";
                    fileInfo.currentStatus = new CurrentStatus("TRASHED", "🗑️ IN TRASH", fileInfo.movedFrom, true);
                    ptf.log("  │                  │    ↳ 🗑️  File trong TRASH — bỏ qua",
                            ProgressTracker.LogLevel.WARNING);

                } else if (moveResult.isSkipped) {
                    // ── BỎ QUA HỢP LỆ (SIBLING / subfolder / đúng chỗ) ──
                    // Không đếm vào missingCount — file không thực sự bị mất
                    fileInfo.status = "Bỏ qua (hợp lệ)";
                    fileInfo.action = moveResult.reason;
                    fileInfo.movedFrom = moveResult.movedFrom != null ? moveResult.movedFrom : "-";
                    fileInfo.currentStatus = new CurrentStatus("SKIPPED", "⏭️ BỎ QUA HỢP LỆ", moveResult.reason, false);
                    ptf.log("  │                  │    ↳ ⏭️  Bỏ qua hợp lệ: " + moveResult.reason,
                            ProgressTracker.LogLevel.INFO);

                } else if (moveResult.success) {
                    // ── MOVE THÀNH CÔNG ──
                    missingCount++;
                    fileInfo.action = "Đã move";
                    fileInfo.currentStatus = new CurrentStatus("MOVED", "✅ ĐÃ MOVE VỀ ĐÚNG CHỖ",
                            folder.path, false);
                    ptf.log("  │                  │    ↳ ✅ Move thành công từ: " + moveResult.movedFrom,
                            ProgressTracker.LogLevel.SUCCESS);

                } else {
                    // ── KHÔNG TÌM THẤY / LỖI → query trạng thái hiện tại ──
                    missingCount++;
                    fileInfo.action = "Không tìm thấy: " + moveResult.reason;
                    ptf.log("  │                  │    ↳ ⚠️  " + moveResult.reason
                            + " — đang kiểm tra trạng thái file...", ProgressTracker.LogLevel.WARNING);
                    fileInfo.currentStatus = getCurrentFileStatus(fileHistory.id);
                    ptf.log("  │                  │         Trạng thái: " + fileInfo.currentStatus.status
                            + " | Vị trí: " + fileInfo.currentStatus.location, ProgressTracker.LogLevel.DETAIL);
                }

                fileInfo.movedFrom = moveResult.movedFrom != null ? moveResult.movedFrom : "-";
            } catch (Exception e) {
                missingCount++;
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
        //
        // ⭐ FIX false-positive: verify vị trí thực tế trước khi block.
        // Chỉ skip nếu file đang là DIRECT CHILD của targetFolder.
        // Nếu file ở trong subfolder lồng sâu hơn (sai chỗ) → vẫn cho phép move lại.
        if (globalRecoveredIds.contains(fileId)) {
            boolean trulyInTargetFolder = false;
            try {
                File currentMeta = driveService.files().get(fileId)
                        .setFields("parents")
                        .setSupportsAllDrives(true)
                        .execute();
                if (currentMeta.getParents() != null) {
                    // Chỉ đúng nếu parent trực tiếp = targetFolderId
                    trulyInTargetFolder = currentMeta.getParents().contains(targetFolderId);
                }
            } catch (Exception ex) {
                trulyInTargetFolder = true; // an toàn: block nếu không verify được
                pt.log("    ⚠️  Không verify được vị trí registry item: " + ex.getMessage(),
                        ProgressTracker.LogLevel.DETAIL);
            }

            if (trulyInTargetFolder) {
                result.success = true;
                result.isSkipped = true;
                result.reason = "Đã recover (cross-user registry) — đang ở đúng target";
                result.movedFrom = "-";
                pt.log("    ⏭️  File ID đã có trong registry VÀ đang là direct child của target → bỏ qua",
                        ProgressTracker.LogLevel.DETAIL);
                return result;
            } else {
                // False-positive: không phải direct child của target (hoặc ở chỗ khác hẳn)
                pt.log("    ⚠️  Registry hit nhưng file KHÔNG là direct child của target → false-positive, cho phép move lại",
                        ProgressTracker.LogLevel.WARNING);
                globalRecoveredIds.remove(fileId);
                // Fall through → tiếp tục xử lý move bình thường
            }
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
                result.isSkipped = true;
                result.success = true;
                result.reason = "Trong subfolder";
                result.movedFrom = "Subfolder";
                pt.log("    ⏭️  File đang trong SUBFOLDER, bỏ qua", ProgressTracker.LogLevel.DETAIL);
                return result;
            }
            if (fileLocation.getParents().contains(targetFolderId)) {
                result.success = true;
                result.isSkipped = true;
                result.reason = "Đã trong folder";
                result.movedFrom = targetFolderPath;
                pt.log("    ✓ File đã nằm trong target folder", ProgressTracker.LogLevel.DETAIL);
                return result;
            }
            // ⭐ Fix A: subfolderIds có thể không đầy đủ nếu Drive API bỏ sót một số
            // subfolder sâu (nested 3-4 cấp). Dùng isDescendantOf() để leo lên
            // ancestor chain và xác nhận file THỰC SỰ nằm trong subtree của target.
            // Tránh case: file ở Target/SubA/SubB/SubC bị move về Target root.
            for (String parentId : fileLocation.getParents()) {
                if (parentId.equals(targetFolderId)) continue; // đã check ở trên
                if (subfolderIds.contains(parentId)) continue;  // đã check ở trên
                if (isDescendantOf(parentId, targetFolderId)) {
                    result.isSkipped = true;
                    result.success = true;
                    result.reason = "Trong subfolder sâu (verified bằng ancestor walk) — không move";
                    result.movedFrom = "Subfolder của " + targetFolderPath;
                    pt.log("    ⏭️  File trong SUBFOLDER SÂU (không có trong subfolderIds) — bỏ qua, không move",
                            ProgressTracker.LogLevel.DETAIL);
                    return result;
                }
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
                                result.isSkipped = true;
                                result.success = true;
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
        //
        // ⭐ FIX false-positive: chỉ skip nếu folder đang là DIRECT CHILD của
        // targetFolder.
        // Nếu folder ở trong subfolder lồng sâu hơn (sai chỗ) → vẫn cho phép move lại.
        // Tình huống false-positive:
        // A là target, inner folder B1 (sub của B, sub của A) xử lý trước →
        // "recover" D vào B1 → D.id vào registry.
        // A xử lý sau: D.id ∈ registry, D.parents=[B1] → B1 là trong subtree của A
        // nhưng KHÔNG phải direct child → vẫn phải move D về A.
        if (globalRecoveredIds.contains(folderId)) {
            boolean trulyDirectChildOfTarget = false;
            try {
                File currentMeta = driveService.files().get(folderId)
                        .setFields("parents")
                        .setSupportsAllDrives(true)
                        .execute();
                if (currentMeta.getParents() != null) {
                    // Chỉ đúng nếu parent trực tiếp = targetFolderId
                    trulyDirectChildOfTarget = currentMeta.getParents().contains(targetFolderId);
                }
            } catch (Exception ex) {
                // Không verify được → an toàn hơn là block (tránh loop)
                trulyDirectChildOfTarget = true;
                pt.log("    ⚠️  Không verify được vị trí registry item: " + ex.getMessage(),
                        ProgressTracker.LogLevel.DETAIL);
            }

            if (trulyDirectChildOfTarget) {
                result.success = true;
                result.isSkipped = true;
                result.reason = "Đã recover (cross-user registry) — đang là direct child của target";
                result.movedFrom = "-";
                pt.log("    ⏭️  Folder ID đã có trong registry VÀ đang là direct child của target → bỏ qua",
                        ProgressTracker.LogLevel.DETAIL);
                return result;
            } else {
                // False-positive: folder không phải direct child của target
                // (hoặc ở chỗ khác hẳn) → cần move lại
                pt.log("    ⚠️  Registry hit nhưng folder KHÔNG là direct child của target → false-positive, cho phép move lại",
                        ProgressTracker.LogLevel.WARNING);
                globalRecoveredIds.remove(folderId);
                // Fall through → tiếp tục xử lý move bình thường
            }
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
            result.isSkipped = true;
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
                for (String parentId : foundFolder.getParents()) {
                    if (descendantIds.contains(parentId)) {
                        result.success = true;
                        result.isSkipped = true;
                        result.reason = "Đang trong subfolder con của target (grandchild) — không cần move";
                        result.movedFrom = targetFolderPath + " (subfolder con)";
                        pt.log("    ⏭️  Folder đang là grandchild của target → bỏ qua, không move",
                                ProgressTracker.LogLevel.DETAIL);
                        return result;
                    }
                    // ⭐ Fix A: descendantIds có thể không đầy đủ (deep nested) →
                    // leo lên ancestor chain để xác nhận parent có thuộc subtree của target không
                    if (isDescendantOf(parentId, targetFolderId)) {
                        result.success = true;
                        result.isSkipped = true;
                        result.reason = "Đang trong subfolder sâu của target (verified bằng ancestor walk) — không move";
                        result.movedFrom = targetFolderPath + " (subfolder sâu)";
                        pt.log("    ⏭️  Folder trong SUBFOLDER SÂU (ancestor walk confirmed) → bỏ qua, không move",
                                ProgressTracker.LogLevel.DETAIL);
                        return result;
                    }
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
                        result.isSkipped = true;
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
     * Fix #3: Helper gọi Activity API với service được chỉ định (không dùng this.activityService).
     * Tránh swap field this.activityService không thread-safe khi đệ quy.
     * Vẫn tuân thủ semaphore + exponential backoff giống executeActivityQueryWithRetry.
     */
    private com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse executeActivityQueryWithService(
            com.google.api.services.driveactivity.v2.model.QueryDriveActivityRequest req,
            com.google.api.services.driveactivity.v2.DriveActivity svc) throws IOException {

        int maxRetries = 5;
        long baseDelayMs = 2000;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                activityApiSemaphore.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for Activity API slot", ie);
            }
            try {
                com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse resp =
                        svc.activity().query(req).execute();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return resp;
            } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                if (e.getStatusCode() == 429) {
                    long waitMs = baseDelayMs * (1L << attempt);
                    if (attempt < maxRetries) {
                        try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    } else {
                        return new com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse();
                    }
                } else {
                    return new com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse();
                }
            } catch (Exception e) {
                return new com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse();
            } finally {
                activityApiSemaphore.release();
            }
        }
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

            // Khởi tạo Reports service — Fix #10: dùng getHttpTransport() thay vì newTrustedTransport()
            com.google.api.services.reports.Reports reportsService = new com.google.api.services.reports.Reports.Builder(
                    getHttpTransport(),
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
                // FIX #1: null-safe getMessage() tránh NPE khi message là null
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("rate") || msg.contains("429")) {
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

        // Fix #10: Dùng getHttpTransport() thay vì newTrustedTransport() — tránh tạo mới mỗi lần
        return new DriveActivity.Builder(
                getHttpTransport(),
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

        // Fix #10: Dùng getHttpTransport() thay vì newTrustedTransport() — tránh tạo mới mỗi lần
        return new Drive.Builder(
                getHttpTransport(),
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

        System.out.println("  🔍 Đang query Activity API...");

        // ✨ LOG: Hiển thị cấu hình filter
        if (Config.getActivityDays() > 0) {
            System.out.println("  ⏰ Filter: Đọc activity từ " + Config.getActivityDays() + " ngày trước");
        }
        if (Config.getActivityEndDate() != null && !Config.getActivityEndDate().isEmpty()) {
            System.out.println("  ✂️  Filter: Cắt đọc tại " + Config.getActivityEndDate());
        }

        // FIX #2: Gom toàn bộ activities từ tất cả pages TRƯỚC, rồi mới sort và
        // process.
        // Lý do: Activity API trả về newest-first. Nếu sort từng page riêng lẻ,
        // event REMOVE (mới hơn, page 1) sẽ bị override bởi event ADD (cũ hơn, page 2)
        // → file bị đánh nhầm là currentlyInFolder=true khi thực tế đã bị remove.
        // Giải pháp: Gom hết → sort oldest-first → process đúng thứ tự thời gian.
        List<com.google.api.services.driveactivity.v2.model.DriveActivity> allActivities = new ArrayList<>();
        String pageToken = null;
        String loggedFilter = null;

        do {
            QueryDriveActivityRequest request = new QueryDriveActivityRequest();
            request.setAncestorName("items/" + folderId);
            request.setPageSize(100);

            String filter = buildActivityFilter();
            if (filter != null && !filter.isEmpty()) {
                request.setFilter(filter);
                if (loggedFilter == null) {
                    System.out.println("  🔍 Filter string: " + filter);
                    loggedFilter = filter;
                }
            }

            if (pageToken != null) {
                request.setPageToken(pageToken);
            }

            // ⭐ FIX 429: dùng helper có semaphore + retry
            QueryDriveActivityResponse response = executeActivityQueryWithRetry(request);

            if (response.getActivities() != null) {
                allActivities.addAll(response.getActivities());
            }
            pageToken = response.getNextPageToken();
        } while (pageToken != null);

        // Sort oldest-first → đúng thứ tự thời gian (giống
        // getDirectSubFoldersFromActivity)
        allActivities.sort((a, b) -> {
            String timeA = a.getTimestamp() != null ? a.getTimestamp() : "";
            String timeB = b.getTimestamp() != null ? b.getTimestamp() : "";
            return timeA.compareTo(timeB);
        });

        // ✨ LOG: Hiển thị khoảng thời gian
        if (!allActivities.isEmpty()) {
            logActivityTimeRange(allActivities, folderId);
        }

        System.out.println("  🔍 Xử lý " + allActivities.size() + " activities...");

        for (com.google.api.services.driveactivity.v2.model.DriveActivity activity : allActivities) {
            processActivity(activity, folderId, fileHistoryMap);
        }

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
            String deleteTypeLocal = null; // ⭐ NEW

            for (ActionDetail detail : allActions) {
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
                if (detail.getDelete() != null) {
                    deletedFlag = true; // ⭐ FIX
                    String dtype = detail.getDelete().getType();
                    if (dtype != null && !dtype.isBlank() && !"TYPE_UNSPECIFIED".equals(dtype)) {
                        deleteTypeLocal = dtype;
                    }
                }
            }

            // ⭐ FIX: Include deletedFlag trong guard — DELETE-only items vẫn được xử lý.
            // Deepest folder (D) xử lý trước nhờ Collections.reverse() → kéo C về D đúng.
            // Shallower folder (B) chạy sau: C.parents=[D], D∈subtree(B) → grandchild →
            // skip.
            if (!addedToFolder && !removedFromFolder && !deletedFlag) {
                continue;
            }

            // ⭐ FIX #5: Handle DELETE — chỉ set deletedFromSubtree khi KHÔNG có addedToFolder
            // cùng lúc, tránh state mâu thuẫn (deletedFromSubtree=true + currentlyInFolder=true).
            // Nếu cùng 1 activity có cả ADD + DELETE → ADD thắng (file được thêm vào, sau đó
            // delete là action riêng được ghi nhận bởi removedFromFolder hoặc activity khác).
            if (deletedFlag && !addedToFolder) {
                if (!fileHistoryMap.containsKey(fileId)) {
                    FileHistory newFh = new FileHistory();
                    newFh.id = fileId;
                    newFh.name = fileName;
                    newFh.everInFolder = false;
                    newFh.currentlyInFolder = false;
                    newFh.deletedFromSubtree = true;
                    newFh.deleteType = deleteTypeLocal;
                    newFh.lastSeenTimestamp = timestamp;
                    fileHistoryMap.put(fileId, newFh);
                } else {
                    fileHistoryMap.get(fileId).currentlyInFolder = false;
                    fileHistoryMap.get(fileId).deletedFromSubtree = true;
                    if (deleteTypeLocal != null && fileHistoryMap.get(fileId).deleteType == null) {
                        fileHistoryMap.get(fileId).deleteType = deleteTypeLocal;
                    }
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
                // Nếu cùng activity có ADD lẫn DELETE → ADD thắng, reset deletedFromSubtree
                if (deletedFlag) {
                    fh.deletedFromSubtree = false;
                }
            }

            if (removedFromFolder) {
                // KEY FIX: nếu file bị REMOVE khỏi folder này → nó chắc chắn đã TỪNG ở trong
                // folder (cả trường hợp: auto-removed, bị admin xóa, folder bị un-share)
                fh.everInFolder = true;
                fh.name = fileName;
                if (fh.lastSeenTimestamp == null) {
                    fh.lastSeenTimestamp = timestamp;
                }
                fh.currentlyInFolder = false;
            }

            // Fix #5: Không cần block hasDelete riêng — đã được xử lý bởi
            // deletedFlag block ở trên + removedFromFolder block.
            // Block cũ bị dư thừa và có thể override lại currentlyInFolder sai.
            // (Removed duplicate hasDelete check)
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
            String deleteTypeLocal = null; // ⭐ NEW

            for (ActionDetail detail : allActions) {
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
                if (detail.getDelete() != null) {
                    deletedFlag = true; // ⭐ FIX
                    String dtype = detail.getDelete().getType();
                    if (dtype != null && !dtype.isBlank() && !"TYPE_UNSPECIFIED".equals(dtype)) {
                        deleteTypeLocal = dtype;
                    }
                }
            }

            // ⭐ FIX: Include deletedFlag trong guard — DELETE-only items vẫn được xử lý.
            // Deepest folder (D) xử lý trước nhờ Collections.reverse() → kéo C về D đúng.
            // Shallower folder (B) chạy sau: C.parents=[D], D∈allDescendantIds(B) → skip.
            if (!addedToFolder && !removedFromFolder && !deletedFlag)
                continue;

            // ⭐ FIX #5: Handle DELETE — chỉ set deletedFromSubtree khi KHÔNG có addedToFolder
            // cùng lúc, tránh state mâu thuẫn (deletedFromSubtree=true + currentlyInFolder=true).
            if (deletedFlag && !addedToFolder) {
                if (!map.containsKey(foldItemId)) {
                    FileHistory newFh = new FileHistory();
                    newFh.id = foldItemId;
                    newFh.name = foldItemName;
                    newFh.everInFolder = false;
                    newFh.currentlyInFolder = false;
                    newFh.deletedFromSubtree = true;
                    newFh.deleteType = deleteTypeLocal;
                    newFh.lastSeenTimestamp = timestamp;
                    map.put(foldItemId, newFh);
                } else {
                    map.get(foldItemId).currentlyInFolder = false;
                    map.get(foldItemId).deletedFromSubtree = true;
                    if (deleteTypeLocal != null && map.get(foldItemId).deleteType == null) {
                        map.get(foldItemId).deleteType = deleteTypeLocal;
                    }
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

            if (addedToFolder) {
                fh.everInFolder = true;
                fh.currentlyInFolder = true;
                fh.name = foldItemName;
                fh.lastSeenTimestamp = timestamp;
                // Nếu cùng activity có ADD lẫn DELETE → ADD thắng, reset deletedFromSubtree
                if (deletedFlag) {
                    fh.deletedFromSubtree = false;
                }
            }
            if (removedFromFolder) {
                fh.everInFolder = true;
                fh.name = foldItemName;
                if (fh.lastSeenTimestamp == null) {
                    fh.lastSeenTimestamp = timestamp;
                }
                fh.currentlyInFolder = false;
            }

            // Fix #5: Không cần block hasDelete riêng — đã được xử lý bởi
            // deletedFlag block ở trên + removedFromFolder block.
            // Block cũ bị dư thừa và có thể override lại currentlyInFolder sai.
            // (Removed duplicate hasDelete check)
        }
    }

    private String extractFileId(String name) {
        if (name == null)
            return null;
        String[] parts = name.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : null;
    }

    /**
     * Fix B: Kiểm tra xem folderId có phải là descendant của ancestorId không.
     * Leo lên parent chain tối đa 8 bước để tránh loop vô tận.
     *
     * Dùng trong pre-check của CASE 3 (checkFolder) và trong handleFoundFile để
     * bắt trường hợp file đang nằm trong subfolder SÂU mà subfolderIds không biết
     * (vì Drive API miss subfolder đó khi crawl, hoặc cache thiếu).
     *
     * Ví dụ: Target/SubA/SubB/SubC/F → subfolderIds có thể thiếu SubC
     * → isDescendantOf(SubC, Target) = true → không move F ra ngoài.
     */
    private boolean isDescendantOf(String folderId, String ancestorId) {
        if (folderId == null || ancestorId == null || folderId.equals(ancestorId)) return false;
        Set<String> visited = new HashSet<>();
        String current = folderId;
        int maxDepth = 8; // tối đa 8 level để tránh vòng lặp
        for (int depth = 0; depth < maxDepth; depth++) {
            if (current == null || !visited.add(current)) break;
            try {
                com.google.api.services.drive.model.File f = driveService.files().get(current)
                        .setFields("parents")
                        .setSupportsAllDrives(true)
                        .execute();
                if (f.getParents() == null || f.getParents().isEmpty()) break;
                for (String p : f.getParents()) {
                    if (p.equals(ancestorId)) return true; // tìm thấy ancestor!
                }
                // Tiếp tục leo lên (theo parent đầu tiên)
                current = f.getParents().get(0);
            } catch (Exception ignored) {
                break; // API error → không leo tiếp được → trả về false (safe side)
            }
        }
        return false;
    }

    /**
     * ✅ FIXED: Move file VÀ VERIFY kết quả (giống Apps Script)
     */
    private MoveResult moveFileToFolder(String fileId, List<String> currentParents,
            String targetFolderId, Drive driveService) {
        MoveResult result = new MoveResult();
        result.success = false;
        ProgressTracker pt = ProgressTracker.getInstance();

        // ⭐ FIX LANG: Nếu parents null (folder/file đang ở root My Drive — impersonation
        // không thấy được) → KHÔNG tự động dùng "root" làm removeParents vì có thể gây
        // move nhầm: nếu parents null do lỗi fetch (item đang trong subfolder) thì
        // setRemoveParents("root") sẽ remove sai.
        // Chiến lược an toàn: chỉ đặt removeParents khi biết chắc item đang ở root,
        // còn lại chỉ addParents (file xuất hiện ở cả 2 chỗ — không hại bằng move sai).
        List<String> effectiveParents = currentParents;
        if (effectiveParents == null || effectiveParents.isEmpty()) {
            pt.log("    ⚠️  Parents null — KHÔNG dùng 'root' fallback để tránh move nhầm. Sẽ thử addParents-only.",
                    ProgressTracker.LogLevel.DETAIL);
            // effectiveParents giữ nguyên null/empty → skip removeParents ở dưới
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
            // ⭐ FIX LANG: Chỉ setRemoveParents khi biết chắc parent cũ
            Drive.Files.Update updateReq = driveService.files().update(fileId, null)
                    .setAddParents(targetFolderId)
                    .setSupportsAllDrives(true)
                    .setFields("id, parents");
            if (effectiveParents != null && !effectiveParents.isEmpty()) {
                updateReq.setRemoveParents(String.join(",", effectiveParents));
            } else {
                pt.log("    ℹ️  addParents-only (parents không xác định được — tránh remove nhầm)",
                        ProgressTracker.LogLevel.DETAIL);
            }
            updateReq.execute();

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

        // Fix #9: Dùng try-with-resources cho Workbook để đảm bảo workbook.close()
        // luôn được gọi kể cả khi workbook.write() throw exception — tránh memory leak.
        try (Workbook workbook = new XSSFWorkbook()) {
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
        }
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
                    // Fix #8: Chỉ hiển thị status "Thiếu" THỰC SỰ — bỏ qua các trạng thái
                    // hợp lệ khác: "Có", "Trong subfolder con", "Bỏ qua (hợp lệ)", "Đã restore"
                    // Trước đây chỉ bỏ qua "Có" → các trạng thái không thiếu cũng lọt vào report
                    if (!"Thiếu".equals(sf.status))
                        continue;
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
                // FIX #5: checkFolder() set status = "Thiếu" (có dấu), không phải "Thieu"
                if (!"Thiếu".equals(fi.status))
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
                        .filter(f -> "Thiếu".equals(f.status))
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
    // DELETE EVENT 3-LAYER RESOLUTION HELPERS
    // ============================================

    enum ParentResolution {
        CONFIRMED_DIRECT, NESTED_IN_BATCH, UNKNOWN
    }

    private String queryLastKnownParent_Layer1(String itemId) {
        try {
            java.util.List<com.google.api.services.driveactivity.v2.model.DriveActivity> activities = new ArrayList<>();
            String pageToken = null;
            do {
                com.google.api.services.driveactivity.v2.model.QueryDriveActivityRequest req = new com.google.api.services.driveactivity.v2.model.QueryDriveActivityRequest();
                req.setItemName("items/" + itemId);
                req.setPageSize(100);
                if (pageToken != null)
                    req.setPageToken(pageToken);
                com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse resp = executeActivityQueryWithRetry(
                        req);
                if (resp.getActivities() != null)
                    activities.addAll(resp.getActivities());
                pageToken = resp.getNextPageToken();
            } while (pageToken != null);
            activities.sort((a, b) -> {
                String ta = a.getTimestamp() != null ? a.getTimestamp() : "";
                String tb = b.getTimestamp() != null ? b.getTimestamp() : "";
                return ta.compareTo(tb);
            });
            String lastParentId = null;
            for (com.google.api.services.driveactivity.v2.model.DriveActivity activity : activities) {
                java.util.List<ActionDetail> acts = new ArrayList<>();
                if (activity.getPrimaryActionDetail() != null)
                    acts.add(activity.getPrimaryActionDetail());
                if (activity.getActions() != null) {
                    for (Action a : activity.getActions()) {
                        if (a.getDetail() != null)
                            acts.add(a.getDetail());
                    }
                }
                for (ActionDetail detail : acts) {
                    if (detail.getMove() != null && detail.getMove().getAddedParents() != null) {
                        for (TargetReference parent : detail.getMove().getAddedParents()) {
                            String pid = extractFileId(parent.getDriveItem().getName());
                            if (pid != null)
                                lastParentId = pid;
                        }
                    }
                }
            }
            return lastParentId;
        } catch (Exception e) {
            ProgressTracker.getInstance().log("    [Layer1] ex: " + e.getMessage(), ProgressTracker.LogLevel.DETAIL);
            return null;
        }
    }

    private String findContainerInBatch_Layer2(String itemId, java.util.Set<String> batchDeletedIds) {
        for (String candidateId : batchDeletedIds) {
            if (candidateId.equals(itemId))
                continue;
            try {
                java.util.List<com.google.api.services.driveactivity.v2.model.DriveActivity> activities = new ArrayList<>();
                String pageToken = null;
                do {
                    com.google.api.services.driveactivity.v2.model.QueryDriveActivityRequest req = new com.google.api.services.driveactivity.v2.model.QueryDriveActivityRequest();
                    req.setAncestorName("items/" + candidateId);
                    req.setPageSize(100);
                    if (pageToken != null)
                        req.setPageToken(pageToken);
                    com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse resp = executeActivityQueryWithRetry(
                            req);
                    if (resp.getActivities() != null)
                        activities.addAll(resp.getActivities());
                    pageToken = resp.getNextPageToken();
                } while (pageToken != null);
                for (com.google.api.services.driveactivity.v2.model.DriveActivity activity : activities) {
                    if (activity.getTargets() == null)
                        continue;
                    for (Target target : activity.getTargets()) {
                        if (target.getDriveItem() == null)
                            continue;
                        String tid = extractFileId(target.getDriveItem().getName());
                        if (itemId.equals(tid))
                            return candidateId;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private ParentResolution resolveTrueParent(String itemId, String targetFolderId,
            java.util.Set<String> batchDeletedIds) {
        ProgressTracker pt = ProgressTracker.getInstance();
        String lastParent = queryLastKnownParent_Layer1(itemId);
        if (lastParent != null) {
            boolean direct = targetFolderId.equals(lastParent);
            pt.log("    [Layer1] -> " + (direct ? "DIRECT" : "NESTED"), ProgressTracker.LogLevel.DETAIL);
            return direct ? ParentResolution.CONFIRMED_DIRECT : ParentResolution.NESTED_IN_BATCH;
        }
        if (!batchDeletedIds.isEmpty()) {
            String container = findContainerInBatch_Layer2(itemId, batchDeletedIds);
            if (container != null) {
                pt.log("    [Layer2] nested in: " + container, ProgressTracker.LogLevel.DETAIL);
                return ParentResolution.NESTED_IN_BATCH;
            }
        }
        String ownerEmail = findOwnerViaReportsApi(itemId, Config.getAdminEmail());
        if (ownerEmail != null && !ownerEmail.isBlank()) {
            try {
                com.google.auth.oauth2.GoogleCredentials ownerCreds;
                if (Config.isUseJsonFile()) {
                    ownerCreds = com.google.auth.oauth2.ServiceAccountCredentials
                            .fromStream(new java.io.FileInputStream(Config.getServiceAccountFile()))
                            .createScoped(java.util.List.of("https://www.googleapis.com/auth/drive.activity.readonly"))
                            .createDelegated(ownerEmail);
                } else {
                    ownerCreds = com.google.auth.oauth2.ServiceAccountCredentials
                            .fromStream(new java.io.ByteArrayInputStream(
                                    createServiceAccountJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                            .createScoped(java.util.List.of("https://www.googleapis.com/auth/drive.activity.readonly"))
                            .createDelegated(ownerEmail);
                }
                com.google.api.services.driveactivity.v2.DriveActivity ownerSvc = new com.google.api.services.driveactivity.v2.DriveActivity.Builder(
                        getHttpTransport(), // Fix #10: dùng cached transport
                        com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
                        new com.google.auth.http.HttpCredentialsAdapter(ownerCreds))
                        .setApplicationName("Drive Recovery Tool v2.0").build();
                // Fix #3: Dùng executeActivityQueryWithService(req, ownerSvc) thay vì swap
                // this.activityService → tránh vấn đề khi đệ quy checkFolder() gây cùng
                // instance dùng 2 service khác nhau. Semaphore vẫn được tuân thủ.
                com.google.api.services.driveactivity.v2.model.QueryDriveActivityRequest req = new com.google.api.services.driveactivity.v2.model.QueryDriveActivityRequest();
                req.setItemName("items/" + itemId);
                req.setPageSize(100);
                com.google.api.services.driveactivity.v2.model.QueryDriveActivityResponse resp =
                        executeActivityQueryWithService(req, ownerSvc);
                if (resp.getActivities() != null) {
                    String lp = null;
                    for (com.google.api.services.driveactivity.v2.model.DriveActivity act : resp.getActivities()) {
                        java.util.List<ActionDetail> acts = new ArrayList<>();
                        if (act.getPrimaryActionDetail() != null)
                            acts.add(act.getPrimaryActionDetail());
                        if (act.getActions() != null) {
                            for (Action a : act.getActions()) {
                                if (a.getDetail() != null)
                                    acts.add(a.getDetail());
                            }
                        }
                        for (ActionDetail d : acts) {
                            if (d.getMove() != null && d.getMove().getAddedParents() != null) {
                                for (TargetReference p : d.getMove().getAddedParents()) {
                                    String pid = extractFileId(p.getDriveItem().getName());
                                    if (pid != null)
                                        lp = pid;
                                }
                            }
                        }
                    }
                    if (lp != null) {
                        boolean direct = targetFolderId.equals(lp);
                        pt.log("    [Layer3] -> " + (direct ? "DIRECT" : "NESTED"), ProgressTracker.LogLevel.DETAIL);
                        return direct ? ParentResolution.CONFIRMED_DIRECT : ParentResolution.NESTED_IN_BATCH;
                    }
                }
            } catch (Exception e) {
                pt.log("    [Layer3] ex: " + e.getMessage(), ProgressTracker.LogLevel.DETAIL);
            }
        }
        return ParentResolution.UNKNOWN;
    }

    private boolean restoreFromTrashAndMove(String itemId, java.util.List<String> currentParents,
            String targetFolderId) {
        try {
            com.google.api.services.drive.model.File patch = new com.google.api.services.drive.model.File();
            patch.setTrashed(false);
            driveService.files().update(itemId, patch).setSupportsAllDrives(true).execute();
            // Fix #2: Tách InterruptedException ra khỏi catch (Exception) chung
            // để không nuốt interrupt flag — nếu bị interrupt thì restore và dừng ngay.
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); // Restore interrupt flag
                ProgressTracker.getInstance().log("    restoreFromTrashAndMove interrupted",
                        ProgressTracker.LogLevel.WARNING);
                return false;
            }
            MoveResult mr = moveFileToFolder(itemId, currentParents, targetFolderId, driveService);
            return mr.success;
        } catch (Exception e) {
            ProgressTracker.getInstance().log("    restoreFromTrashAndMove failed: " + e.getMessage(),
                    ProgressTracker.LogLevel.WARNING);
            return false;
        }
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
        boolean isSkipped; // true = bỏ qua hợp lệ (SIBLING, grandchild, cross-user) — KHÔNG đếm vào
                           // "thiếu"
        String reason;
        String movedFrom;

        MoveResult() {
            this.success = false;
            this.actuallyMoved = false;
            this.inTrash = false;
            this.isSkipped = false;
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
        /** "TRASH"/"PERMANENT_DELETE"/null — from detail.getDelete().getType() */
        String deleteType;
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
