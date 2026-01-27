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

public class DriveRecoveryService {

    private final Drive driveService;
    private final DriveActivity activityService;
    private final java.util.concurrent.ConcurrentLinkedQueue<FolderReport> allReports = new java.util.concurrent.ConcurrentLinkedQueue<>();

    // Cache để lưu folder names
    private final Map<String, String> folderNameCache = new ConcurrentHashMap<>();

    public DriveRecoveryService(Drive driveService, DriveActivity activityService) {
        this.driveService = driveService;
        this.activityService = activityService;
    }

    public void processUserDrive(String userEmail) throws IOException {
        System.out.println("✓ Đang xử lý Drive của: " + userEmail);

        System.out.println("\n📂 Đang quét tất cả folder trong My Drive...");
        List<FolderInfo> allFolders = getAllFoldersRecursive(userEmail);
        System.out.println("✓ Tìm thấy " + allFolders.size() + " folder\n");

        System.out.println("🚀 Bắt đầu xử lý SONG SONG với 1 thread...\n");

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(1);
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

                    synchronized (System.err) {
                        System.err.println("  ❌ Lỗi tại " + folder.path + ": " + e.getMessage());
                    }

                    FolderReport errorReport = new FolderReport();
                    errorReport.folderPath = folder.path;
                    errorReport.folderId = folder.id;
                    errorReport.error = e.getMessage();
                    allReports.add(errorReport);
                }
            });
        }

        executor.shutdown();

        try {
            System.out.println("\n⏳ Đang đợi tất cả threads hoàn thành...");

            boolean finished = executor.awaitTermination(2, java.util.concurrent.TimeUnit.HOURS);

            if (!finished) {
                System.err.println("⚠️  Timeout! Một số threads chưa hoàn thành sau 2 giờ");
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

    private List<FolderInfo> getFoldersRecursiveHelper(String parentId, String parentPath, String userEmail) throws IOException {
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
            String query = "'" + parentId + "' in parents and mimeType='application/vnd.google-apps.folder' and trashed=false";
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

        System.out.println("  📋 Đang đọc Activity history...");
        List<FileHistory> filesFromActivity = getFilesFromActivity(folder.id, userEmail);

        if (filesFromActivity.isEmpty()) {
            System.out.println("  ℹ️  Không có activity nào");
            return report;
        }

        System.out.println("  ✓ Activity cho thấy " + filesFromActivity.size() + " file từng có TRỰC TIẾP trong folder");

        List<File> currentFiles = getCurrentFilesInFolder(folder.id, userEmail);
        Set<String> currentFileIds = currentFiles.stream()
                .map(File::getId)
                .collect(Collectors.toSet());

        System.out.println("  ✓ Hiện tại có " + currentFiles.size() + " file TRỰC TIẾP trong folder");

        Set<String> subfolderIds = getAllSubfolderIds(folder.id, userEmail);
        System.out.println("  ✓ Có " + subfolderIds.size() + " subfolder bên trong");

        Set<String> filesInSubfolders = getAllFilesInSubfolders(subfolderIds, userEmail);
        System.out.println("  ✓ Có " + filesInSubfolders.size() + " file đang nằm trong các subfolder");

        int missingCount = 0;
        for (FileHistory fileHistory : filesFromActivity) {
            FileInfo fileInfo = new FileInfo();
            fileInfo.fileName = fileHistory.name;
            fileInfo.fileId = fileHistory.id;
            fileInfo.lastSeen = fileHistory.lastSeenTimestamp != null ? fileHistory.lastSeenTimestamp : "N/A";

            // CASE 1: File đang có trong folder
            if (currentFileIds.contains(fileHistory.id)) {
                fileInfo.status = "✓ Có";
                fileInfo.action = "-";
                fileInfo.movedFrom = "-";

                // ⭐ Kiểm tra current status
                fileInfo.currentStatus = getCurrentFileStatus(fileHistory.id);

                report.files.add(fileInfo);
                continue;
            }

            // CASE 2: File trong subfolder
            if (filesInSubfolders.contains(fileHistory.id)) {
                fileInfo.status = "⏭️ Trong subfolder";
                fileInfo.action = "Không cần move";
                fileInfo.movedFrom = "-";

                fileInfo.currentStatus = getCurrentFileStatus(fileHistory.id);

                System.out.println("    ⏭️  File " + fileHistory.name + " đang nằm trong SUBFOLDER, bỏ qua");
                report.files.add(fileInfo);
                continue;
            }

            // CASE 3: File thiếu - cần tìm và move
            fileInfo.status = "✗ Thiếu";
            missingCount++;
            System.out.println("    ⚠️  File " + fileHistory.name + " thiếu, đang tìm...");

            try {
                MoveResult moveResult = findAndMoveFileWithResult(fileHistory, folder.id, folder.path, userEmail, subfolderIds);
                fileInfo.action = moveResult.success ? "✓ Đã move" : "✗ " + moveResult.reason;
                fileInfo.movedFrom = moveResult.movedFrom != null ? moveResult.movedFrom : "-";

                // ⭐ Kiểm tra current status sau khi move
                fileInfo.currentStatus = getCurrentFileStatus(fileHistory.id);

            } catch (Exception e) {
                fileInfo.action = "✗ Lỗi: " + e.getMessage();
                fileInfo.movedFrom = "-";
                fileInfo.currentStatus = new CurrentStatus("ERROR", "⚠️ ERROR", "N/A", false);
                System.out.println("    ❌ Không thể xử lý file " + fileHistory.name + ": " + e.getMessage());
            }

            report.files.add(fileInfo);
        }

        if (missingCount == 0) {
            System.out.println("  ✅ Không thiếu file nào");
        } else {
            System.out.println("  ⚠️  Có " + missingCount + " file thiếu");
        }

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

    private MoveResult findAndMoveFileWithResult(FileHistory file, String targetFolderId, String targetFolderPath, String userEmail, Set<String> subfolderIds) {
        MoveResult result = new MoveResult();
        result.success = false;
        result.reason = "";
        result.movedFrom = "";

        File fileLocation = findFileById(file.id);

        if (fileLocation != null) {
            System.out.println("    ✓ Tìm thấy file trong Drive của " + userEmail);

            if (fileLocation.getTrashed() != null && fileLocation.getTrashed()) {
                result.reason = "File đang trong TRASH của " + userEmail;
                result.movedFrom = "Trash (" + userEmail + ")";
                System.out.println("    🗑️  File trong TRASH của: " + userEmail);
                return result;
            }

            if (fileLocation.getParents() != null) {
                boolean isInSubfolder = fileLocation.getParents().stream()
                        .anyMatch(p -> subfolderIds.contains(p));
                if (isInSubfolder) {
                    result.reason = "Trong subfolder";
                    result.movedFrom = "Subfolder";
                    System.out.println("    ⏭️  File đang trong SUBFOLDER, bỏ qua");
                    return result;
                }

                if (fileLocation.getParents().contains(targetFolderId)) {
                    result.reason = "Đã trong folder";
                    result.movedFrom = targetFolderPath;
                    System.out.println("    ✓ File đã nằm trong target folder");
                    return result;
                }
            }

            String parentName = getParentFolderName(fileLocation);
            result.movedFrom = parentName + " (" + userEmail + ")";

// ✅ Thử move với current user token
            try {
                Drive currentUserDrive = createDriveServiceForUserWithRetry(userEmail);

                System.out.println("    ➡️  Đang move file về " + targetFolderPath + "...");
                MoveResult moveResult = moveFileToFolder(file.id, fileLocation.getParents(),
                        targetFolderId, currentUserDrive);

                if (moveResult.success) {
                    result.success = true;
                    result.reason = "Success";
                    System.out.println("    ✅ Đã move thành công từ: " + parentName);
                    return result;
                }

                // ✅ FALLBACK: Thử với owner token
                System.out.println("    ⚠️  Move với user token thất bại: " + moveResult.reason);
                System.out.println("    🔄 Thử move với owner token...");

                if (fileLocation.getOwners() != null && !fileLocation.getOwners().isEmpty()) {
                    String ownerEmail = fileLocation.getOwners().get(0).getEmailAddress();

                    try {
                        Drive ownerDrive = createDriveServiceForUserWithRetry(ownerEmail);

                        MoveResult ownerMoveResult = moveFileToFolder(file.id, fileLocation.getParents(),
                                targetFolderId, ownerDrive);

                        if (ownerMoveResult.success) {
                            result.success = true;
                            result.reason = "Success (dùng quyền owner)";
                            result.movedFrom = parentName + " (dùng quyền " + ownerEmail + ")";
                            System.out.println("    ✅ Đã move bằng owner token");
                            return result;
                        } else {
                            result.reason = "Lỗi move (cả user lẫn owner): " + ownerMoveResult.reason;
                            System.out.println("    ❌ Move thất bại: " + ownerMoveResult.reason);
                            return result;
                        }
                    } catch (Exception e) {
                        result.reason = "Không lấy được owner token: " + e.getMessage();
                        return result;
                    }
                }

                result.reason = "Lỗi move: " + moveResult.reason;
                return result;

            } catch (Exception e) {
                result.reason = "Không tạo được Drive service: " + e.getMessage();
                System.out.println("    ❌ Lỗi: " + e.getMessage());
                return result;
            }
        }

        System.out.println("    🔍 Tìm trong Drive của các user khác...");

        List<String> allUsers = Config.ALL_USERS_FOR_SEARCH;
        System.out.println("    📋 Có " + allUsers.size() + " users trong danh sách");

        for (String otherUserEmail : allUsers) {
            if (otherUserEmail.equals(userEmail)) {
                continue;
            }

            try {
                System.out.println("    🔎 Đang kiểm tra Drive của: " + otherUserEmail);

                Drive userDriveService = createDriveServiceForUserWithRetry(otherUserEmail);

                try {
                    File foundFile = userDriveService.files().get(file.id)
                            .setFields("id, name, parents, trashed, mimeType, owners")
                            .setSupportsAllDrives(true)
                            .execute();

                    if (foundFile != null) {
                        System.out.println("    ✓ Tìm thấy trong Drive của: " + otherUserEmail);

                        if (foundFile.getTrashed() != null && foundFile.getTrashed()) {
                            result.reason = "File đang trong TRASH của " + otherUserEmail;
                            result.movedFrom = "Trash (" + otherUserEmail + ")";
                            System.out.println("    🗑️  File trong TRASH của: " + otherUserEmail);
                            return result;
                        }

                        if (foundFile.getParents() != null) {
                            boolean isInSubfolder = foundFile.getParents().stream()
                                    .anyMatch(p -> subfolderIds.contains(p));
                            if (isInSubfolder) {
                                result.reason = "Trong subfolder";
                                result.movedFrom = "Subfolder";
                                System.out.println("    ⏭️  File đang trong SUBFOLDER, bỏ qua");
                                return result;
                            }

                            if (foundFile.getParents().contains(targetFolderId)) {
                                result.reason = "Đã trong folder";
                                result.movedFrom = targetFolderPath;
                                System.out.println("    ✓ File đã nằm trong target folder");
                                return result;
                            }
                        }

                        String parentName = getParentFolderName(foundFile);
                        result.movedFrom = parentName + " (" + otherUserEmail + ")";

                        // ✅ THAY BẰNG:
                        // Trong phần tìm thấy file ở user khác (line ~572)
                        System.out.println("    ➡️  Đang move file về " + targetFolderPath + "...");

// ✅ BƯỚC 1: Thử move với userDriveService
                        MoveResult moveResult = moveFileToFolder(file.id, foundFile.getParents(),
                                targetFolderId, userDriveService);

                        if (moveResult.success) {
                            result.success = true;
                            result.reason = "Success";
                            System.out.println("    ✅ Đã move thành công từ: " + otherUserEmail);
                            return result;
                        }

// ✅ BƯỚC 2: FALLBACK - Thử với owner token
                        System.out.println("    ⚠️  Move với user token thất bại: " + moveResult.reason);
                        System.out.println("    🔄 Thử move với owner token...");

                        if (foundFile.getOwners() != null && !foundFile.getOwners().isEmpty()) {
                            String ownerEmail = foundFile.getOwners().get(0).getEmailAddress();

                            try {
                                Drive ownerDriveService = createDriveServiceForUserWithRetry(ownerEmail);

                                MoveResult ownerMoveResult = moveFileToFolder(file.id, foundFile.getParents(),
                                        targetFolderId, ownerDriveService);

                                if (ownerMoveResult.success) {
                                    result.success = true;
                                    result.reason = "Success (dùng quyền owner)";
                                    result.movedFrom = parentName + " (" + ownerEmail + ")";
                                    System.out.println("    ✅ Đã move bằng owner token: " + ownerEmail);
                                    return result;
                                } else {
                                    result.reason = "Lỗi move (cả user lẫn owner): " + ownerMoveResult.reason;
                                    return result;
                                }
                            } catch (Exception e) {
                                result.reason = "Không lấy được owner token: " + e.getMessage();
                                return result;
                            }
                        }

                        result.reason = "Move thất bại: " + moveResult.reason;
                        return result;
                    }
                } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                    int statusCode = e.getStatusCode();
                    if (statusCode == 404) {
                        System.out.println("    ⊗ File không có trong Drive của " + otherUserEmail + " (404)");
                    } else if (statusCode == 403) {
                        System.out.println("    ⊗ Không có quyền truy cập file từ " + otherUserEmail + " (403)");
                    } else {
                        System.out.println("    ⚠️  Lỗi " + statusCode + " khi check " + otherUserEmail);
                    }
                    continue;
                }

            } catch (Exception e) {
                System.out.println("    ❌ Lỗi khi tạo service cho " + otherUserEmail + ": " + e.getMessage());
                continue;
            }
        }

        result.reason = "Không tìm thấy file";
        result.movedFrom = "-";
        System.out.println("    ❌ Đã tìm trong " + allUsers.size() + " users nhưng không thấy file");
        return result;
    }

    private String getParentFolderName(File file) {
        if (file.getParents() != null && !file.getParents().isEmpty()) {
            try {
                String parentId = file.getParents().get(0);
                return getFolderNameCached(parentId);
            } catch (Exception ex) {
                return file.getParents().get(0);
            }
        }
        return "Unknown location";
    }

    private File findFileById(String fileId) {
        try {
            File file = driveService.files().get(fileId)
                    .setFields("id, name, parents, trashed, mimeType, owners")
                    .setSupportsAllDrives(true)
                    .execute();

            if (file.getTrashed() != null && file.getTrashed()) {
                System.out.println("      🗑️  File đang trong TRASH!");
            }

            return file;
        } catch (Exception e) {
            return null;
        }
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

    private Drive createDriveServiceForUser(String userEmail) throws Exception {
        com.google.auth.oauth2.GoogleCredentials credentials;

        if (Config.USE_JSON_FILE) {
            credentials = com.google.auth.oauth2.ServiceAccountCredentials
                    .fromStream(new java.io.FileInputStream(Config.SERVICE_ACCOUNT_FILE))
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
                new com.google.auth.http.HttpCredentialsAdapter(credentials)
        )
                .setApplicationName("Drive Recovery Tool v1.0")
                .build();
    }

    private String createServiceAccountJson() {
        String privateKeyId = (Config.PRIVATE_KEY_ID != null && !Config.PRIVATE_KEY_ID.isEmpty())
                ? Config.PRIVATE_KEY_ID : "0";
        String clientId = (Config.CLIENT_ID != null && !Config.CLIENT_ID.isEmpty())
                ? Config.CLIENT_ID : "0";

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
                Config.PROJECT_ID,
                privateKeyId,
                Config.PRIVATE_KEY.replace("\n", "\\n"),
                Config.SERVICE_ACCOUNT_EMAIL,
                clientId
        );
    }

    private List<FileHistory> getFilesFromActivity(String folderId, String userEmail) throws IOException {
        Map<String, FileHistory> fileHistoryMap = new HashMap<>();
        String pageToken = null;

        System.out.println("  🔍 Đang query Activity API...");

        do {
            QueryDriveActivityRequest request = new QueryDriveActivityRequest();
            request.setAncestorName("items/" + folderId);
            request.setPageSize(100);
            if (pageToken != null) {
                request.setPageToken(pageToken);
            }

            QueryDriveActivityResponse response = activityService.activity()
                    .query(request)
                    .execute();

            if (response.getActivities() != null) {
                List<com.google.api.services.driveactivity.v2.model.DriveActivity> activities =
                        new ArrayList<>(response.getActivities());

                activities.sort((a, b) -> {
                    String timeA = a.getTimestamp() != null ? a.getTimestamp() : "";
                    String timeB = b.getTimestamp() != null ? b.getTimestamp() : "";
                    return timeA.compareTo(timeB);
                });

                System.out.println("  🔍 Xử lý " + activities.size() + " activities...");

                for (com.google.api.services.driveactivity.v2.model.DriveActivity activity : activities) {
                    processActivity(activity, folderId, fileHistoryMap);
                }
            }

            pageToken = response.getNextPageToken();
        } while (pageToken != null);

        List<FileHistory> result = fileHistoryMap.values().stream()
                .filter(fh -> fh.everInFolder)
                .collect(Collectors.toList());

        System.out.println("  🔍 Có " + result.size() + " file từng thuộc TRỰC TIẾP folder này");

        return result;
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

            if (target.getDriveItem().getDriveFolder() != null) {
                continue;
            }

            if (target.getDriveItem().getDriveFile() == null) {
                continue;
            }

            String fileId = extractFileId(target.getDriveItem().getName());
            String fileName = target.getDriveItem().getTitle();

            if (fileId == null) continue;

            boolean addedToFolder = false;
            boolean removedFromFolder = false;
            boolean createdInFolder = false;

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

                // ⭐ CREATE
                if (detail.getCreate() != null) {
                    createdInFolder = addedToFolder;
                }

                // ⭐ EDIT
                if (detail.getEdit() != null) {
                    if (addedToFolder) {
                        createdInFolder = true;
                    }
                }
            }

            if (!addedToFolder && !removedFromFolder && !createdInFolder) {
                continue;
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

            if (addedToFolder || createdInFolder) {
                fh.everInFolder = true;
                fh.currentlyInFolder = true;
                fh.name = fileName;
                fh.lastSeenTimestamp = timestamp;
            }

            if (removedFromFolder) {
                fh.currentlyInFolder = false;
            }

            boolean hasDelete = allActions.stream().anyMatch(a -> a.getDelete() != null);
            if (hasDelete) {
                fh.currentlyInFolder = false;
            }
        }
    }

    private String extractFileId(String name) {
        if (name == null) return null;
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

        try {
            String removeParents = currentParents != null && !currentParents.isEmpty()
                    ? String.join(",", currentParents)
                    : "";

            // ✅ BƯỚC 1: Execute move
            File response = driveService.files().update(fileId, null)
                    .setAddParents(targetFolderId)
                    .setRemoveParents(removeParents)
                    .setSupportsAllDrives(true)
                    .setFields("id, parents")
                    .execute();

            // ✅ BƯỚC 2: Đợi Drive sync (giống Apps Script)
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // ✅ BƯỚC 3: VERIFY file đã move chưa
            File verifyFile = driveService.files().get(fileId)
                    .setFields("id, name, parents")
                    .setSupportsAllDrives(true)
                    .execute();

            // ✅ BƯỚC 4: KIỂM TRA file có trong target folder chưa
            if (verifyFile.getParents() != null &&
                    verifyFile.getParents().contains(targetFolderId)) {

                System.out.println("    ✅ Move SUCCESS - Verified");
                result.success = true;
                result.reason = "Success";
                return result;

            } else {
                System.out.println("    ❌ Move FAILED - File not in target folder");
                System.out.println("    Current parents: " + verifyFile.getParents());
                result.reason = "File not in target folder after move";
                return result;
            }

        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            int statusCode = e.getStatusCode();
            String errorMsg = e.getDetails() != null ? e.getDetails().getMessage() : e.getMessage();

            System.out.println("    ❌ Move FAILED (" + statusCode + "): " + errorMsg);

            result.reason = "HTTP " + statusCode + ": " + errorMsg;
            return result;

        } catch (Exception e) {
            System.out.println("    ❌ Move EXCEPTION: " + e.getMessage());
            result.reason = e.getMessage();
            return result;
        }
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
        Set<String> result = new HashSet<>();
        List<File> folders = getFoldersInParent(parentId, userEmail);

        for (File folder : folders) {
            result.add(folder.getId());
            result.addAll(getAllSubfolderIds(folder.getId(), userEmail));
        }

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

    /**
     * ⭐ ENHANCED: Tạo Excel report với 5 sheets
     */
    public String generateExcelReport() throws IOException {
        String userEmails = Config.USERS_TO_CHECK.stream()
                .map(email -> email.split("@")[0])
                .collect(Collectors.joining("_"));

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String fileName = Config.OUTPUT_FILE_PREFIX + "-" + userEmails + "-" + timestamp + ".xlsx";

        java.io.File outputDir = new java.io.File(Config.OUTPUT_DIRECTORY);
        if (!outputDir.exists()) {
            System.out.println("📁 Tạo thư mục: " + outputDir.getAbsolutePath());
            outputDir.mkdirs();
        }

        String fullPath = Config.OUTPUT_DIRECTORY + fileName;

        Workbook workbook = new XSSFWorkbook();

        List<FolderReport> reportsList = new ArrayList<>(allReports);

        // ⭐ SHEET 1: Summary + Current Status Statistics
        Sheet summarySheet = workbook.createSheet("Summary");
        createEnhancedSummarySheet(summarySheet, workbook, reportsList);

        // ⭐ SHEET 2: Files + Current Status
        Sheet filesStatusSheet = workbook.createSheet("Files + Current Status");
        createFilesStatusSheet(filesStatusSheet, workbook, reportsList);

        // ⭐ SHEET 3: Complete Timeline
        Sheet timelineSheet = workbook.createSheet("Complete Timeline");
        createTimelineSheet(timelineSheet, workbook, reportsList);

        // ⭐ SHEET 4: File Details (per file)
        int sheetIndex = 1;
        for (int i = 0; i < reportsList.size() && sheetIndex <= 10; i++) {
            FolderReport report = reportsList.get(i);
            if (report.files != null && !report.files.isEmpty()) {
                String sheetName = "Folder_" + sheetIndex;
                Sheet detailSheet = workbook.createSheet(sheetName);
                createDetailSheet(detailSheet, report, workbook);
                sheetIndex++;
            }
        }

        // ⭐ SHEET 5: Deleted Files Only
        Sheet deletedSheet = workbook.createSheet("Deleted Files Only");
        createDeletedFilesSheet(deletedSheet, workbook, reportsList);

        try (FileOutputStream out = new FileOutputStream(fullPath)) {
            workbook.write(out);
        }
        workbook.close();

        return new java.io.File(fullPath).getAbsolutePath();
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

        // Statistics
        Map<String, Integer> statusStats = new HashMap<>();
        statusStats.put("EXISTS", 0);
        statusStats.put("TRASHED", 0);
        statusStats.put("DELETED", 0);
        statusStats.put("NO_ACCESS", 0);
        statusStats.put("ERROR", 0);

        for (FolderReport report : reports) {
            if (report.files != null) {
                for (FileInfo file : report.files) {
                    if (file.currentStatus != null) {
                        String code = file.currentStatus.statusCode;
                        statusStats.put(code, statusStats.getOrDefault(code, 0) + 1);
                    }
                }
            }
        }

        // Current Status Statistics
        sheet.createRow(2).createCell(0).setCellValue("📈 Current Status Statistics:");

        CellStyle greenStyle = workbook.createCellStyle();
        greenStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        greenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle yellowStyle = workbook.createCellStyle();
        yellowStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        yellowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle redStyle = workbook.createCellStyle();
        redStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        redStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        int row = 3;
        sheet.createRow(row++).createCell(0).setCellValue("✅ Still Exists: " + statusStats.get("EXISTS"));
        sheet.getRow(row - 1).getCell(0).setCellStyle(greenStyle);

        sheet.createRow(row++).createCell(0).setCellValue("🗑️ In Trash: " + statusStats.get("TRASHED"));
        sheet.getRow(row - 1).getCell(0).setCellStyle(yellowStyle);

        sheet.createRow(row++).createCell(0).setCellValue("❌ Permanently Deleted: " + statusStats.get("DELETED"));
        sheet.getRow(row - 1).getCell(0).setCellStyle(redStyle);

        sheet.createRow(row++).createCell(0).setCellValue("🔒 No Access: " + statusStats.get("NO_ACCESS"));
        sheet.createRow(row++).createCell(0).setCellValue("⚠️ Error: " + statusStats.get("ERROR"));

        // Folder Summary
        row += 2;
        Row headerRow = sheet.createRow(row++);
        String[] headers = {"Folder Path", "Folder ID", "Total Files", "Files OK", "Files Missing", "Files Recovered"};
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
                long totalFiles = report.files.size();
                long filesOK = report.files.stream()
                        .filter(f -> "✓ Có".equals(f.status) || "⏭️ Trong subfolder".equals(f.status))
                        .count();
                long filesMissing = report.files.stream()
                        .filter(f -> "✗ Thiếu".equals(f.status))
                        .count();
                long filesRecovered = report.files.stream()
                        .filter(f -> "✓ Đã move".equals(f.action))
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
        String[] headers = {"Folder Path", "File Name", "File ID", "🔍 CURRENT STATUS", "📍 Current Location", "🗑️ Trashed?", "Last Action"};
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
     * ⭐ NEW: Complete Timeline Sheet
     */
    private void createTimelineSheet(Sheet sheet, Workbook workbook, List<FolderReport> reports) {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        Row headerRow = sheet.createRow(0);
        String[] headers = {"Folder Path", "File Name", "Last Seen", "Status", "Action", "Moved From"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowNum = 1;
        for (FolderReport report : reports) {
            if (report.files != null) {
                for (FileInfo file : report.files) {
                    Row row = sheet.createRow(rowNum++);
                    row.createCell(0).setCellValue(report.folderPath);
                    row.createCell(1).setCellValue(file.fileName);
                    row.createCell(2).setCellValue(file.lastSeen);
                    row.createCell(3).setCellValue(file.status);
                    row.createCell(4).setCellValue(file.action);
                    row.createCell(5).setCellValue(file.movedFrom != null ? file.movedFrom : "-");
                }
            }
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(0, 6000);
        sheet.setColumnWidth(1, 8000);
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
        String[] headers = {"Folder Path", "File Name", "File ID", "❌ Status", "📍 Location", "Last Action"};
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
                    CurrentStatus status = file.currentStatus;
                    if (status != null &&
                            ("DELETED".equals(status.statusCode) ||
                                    "TRASHED".equals(status.statusCode) ||
                                    "NO_ACCESS".equals(status.statusCode))) {

                        hasDeletedFiles = true;
                        Row row = sheet.createRow(rowNum++);
                        row.createCell(0).setCellValue(report.folderPath);
                        row.createCell(1).setCellValue(file.fileName);
                        row.createCell(2).setCellValue(file.fileId);

                        Cell statusCell = row.createCell(3);
                        statusCell.setCellValue(status.status);

                        if ("DELETED".equals(status.statusCode) || "NO_ACCESS".equals(status.statusCode)) {
                            statusCell.setCellStyle(redBg);
                        } else {
                            statusCell.setCellStyle(yellowBg);
                        }

                        row.createCell(4).setCellValue(status.location);
                        row.createCell(5).setCellValue(file.action);
                    }
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
        String[] headers = {"File Name", "File ID", "Status", "Action", "Moved From", "Last Seen", "Current Status"};
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

//    static class MoveResult {
//        boolean success;
//        String reason;
//        String movedFrom;
//    }

    static class MoveResult {
        boolean success;
        String reason;
        String movedFrom;

        MoveResult() {
            this.success = false;
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
    }

    static class FolderReport {
        String folderPath;
        String folderId;
        List<FileInfo> files;
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
        String status;     // Display text
        String location;   // Current location
        boolean trashed;   // Is in trash?

        CurrentStatus(String statusCode, String status, String location, boolean trashed) {
            this.statusCode = statusCode;
            this.status = status;
            this.location = location;
            this.trashed = trashed;
        }
    }
}