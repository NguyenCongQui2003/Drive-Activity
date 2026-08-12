package driverecovery;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.driveactivity.v2.DriveActivity;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

import javax.swing.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ⭐ TASK RUNNER - SwingWorker bọc toàn bộ logic chạy nền
 * - Chạy 4 modes tương ứng với Main.java cũ
 * - Hỗ trợ dừng giữa chừng (stopped flag)
 * - Ghi checkpoint.json sau mỗi user xong
 * - Phát hiện checkpoint cũ và hỏi resume
 */
public class TaskRunner extends SwingWorker<Void, String> {

    private final AppConfig appConfig;
    private final Runnable onDone;
    private static final String CHECKPOINT_FILENAME = "checkpoint.json";

    public TaskRunner(AppConfig appConfig, Runnable onDone) {
        this.appConfig = appConfig;
        this.onDone = onDone;
    }

    // ============================================
    // CHECKPOINT
    // ============================================

    public static File getCheckpointFile(String outputDirectory) {
        return new File(outputDirectory, CHECKPOINT_FILENAME);
    }

    public static boolean checkpointExists(String outputDirectory) {
        return getCheckpointFile(outputDirectory).exists();
    }

    /** Đọc danh sách đã hoàn thành từ checkpoint */
    public static Set<String> loadCompletedUsers(String outputDirectory) {
        Set<String> completed = new LinkedHashSet<>();
        File f = getCheckpointFile(outputDirectory);
        if (!f.exists()) return completed;
        try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String json = sb.toString();
            // Parse "completedUsers": ["a","b","c"]
            int idx = json.indexOf("\"completedUsers\"");
            if (idx >= 0) {
                int arrStart = json.indexOf('[', idx);
                int arrEnd = json.indexOf(']', arrStart);
                if (arrStart >= 0 && arrEnd > arrStart) {
                    String arr = json.substring(arrStart + 1, arrEnd);
                    for (String part : arr.split(",")) {
                        String email = part.trim().replace("\"", "");
                        if (!email.isEmpty()) completed.add(email);
                    }
                }
            }
        } catch (Exception ignored) {}
        return completed;
    }

    /** Đọc mode từ checkpoint */
    public static String loadCheckpointMode(String outputDirectory) {
        File f = getCheckpointFile(outputDirectory);
        if (!f.exists()) return "1";
        try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String json = sb.toString();
            int idx = json.indexOf("\"mode\"");
            if (idx >= 0) {
                int colon = json.indexOf(':', idx);
                int q1 = json.indexOf('"', colon);
                int q2 = json.indexOf('"', q1 + 1);
                if (q1 >= 0 && q2 > q1) return json.substring(q1 + 1, q2);
            }
        } catch (Exception ignored) {}
        return "1";
    }

    private void saveCheckpoint(Set<String> completedUsers) {
        String outputDir = Config.getOutputDirectory();
        try {
            new File(outputDir).mkdirs();
            File f = getCheckpointFile(outputDir);
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"mode\": \"").append(appConfig.runMode).append("\",\n");
            sb.append("  \"lastUpdated\": \"")
                    .append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .append("\",\n");
            sb.append("  \"completedUsers\": [");
            boolean first = true;
            for (String u : completedUsers) {
                if (!first) sb.append(", ");
                sb.append("\"").append(u).append("\"");
                first = false;
            }
            sb.append("]\n}\n");
            try (FileWriter fw = new FileWriter(f, StandardCharsets.UTF_8)) {
                fw.write(sb.toString());
            }
        } catch (Exception e) {
            ProgressTracker.getInstance().log("⚠️ Không ghi được checkpoint: " + e.getMessage(),
                    ProgressTracker.LogLevel.WARNING);
        }
    }

    private void deleteCheckpoint() {
        File f = getCheckpointFile(Config.getOutputDirectory());
        if (f.exists()) f.delete();
    }

    // ============================================
    // MAIN BACKGROUND WORK
    // ============================================

    @Override
    protected Void doInBackground() {
        ProgressTracker pt = ProgressTracker.getInstance();
        pt.reset();

        // Inject AppConfig vào Config static fields
        Config.applyFromAppConfig(appConfig);

        try {
            switch (appConfig.runMode) {
                case "2" -> runMode2(pt);
                case "3" -> runMode3(pt);
                case "4" -> runMode4(pt);
                default  -> runMode1(pt);
            }
        } catch (StoppedException e) {
            pt.log("\n⛔ Đã dừng theo yêu cầu người dùng.", ProgressTracker.LogLevel.WARNING);
        } catch (Exception e) {
            pt.log("\n❌ LỖI NGHIÊM TRỌNG: " + e.getMessage(), ProgressTracker.LogLevel.ERROR);
            pt.log("Chi tiết: " + getStackTrace(e), ProgressTracker.LogLevel.DETAIL);
        }

        return null;
    }

    @Override
    protected void done() {
        ProgressTracker.getInstance().onComplete();
        if (onDone != null) onDone.run();
    }

    // ============================================
    // MODE 1: Normal Recovery
    // ============================================
    private void runMode1(ProgressTracker pt) throws Exception {
        List<String> users = Config.getUsersToCheck();
        Set<String> completedUsers = new LinkedHashSet<>(appConfig.resumeFromCheckpoint
                ? loadCompletedUsers(Config.getOutputDirectory())
                : Collections.emptySet());

        pt.log("════════════════════════════════════════", ProgressTracker.LogLevel.HEADER);
        pt.log("   🚀 MODE 1: NORMAL RECOVERY", ProgressTracker.LogLevel.HEADER);
        pt.log("════════════════════════════════════════", ProgressTracker.LogLevel.HEADER);
        pt.log("👥 Tổng người dùng: " + users.size(), ProgressTracker.LogLevel.INFO);
        if (!completedUsers.isEmpty()) {
            pt.log("⏭️  Resume: Bỏ qua " + completedUsers.size() + " người dùng đã hoàn thành trước đó",
                    ProgressTracker.LogLevel.WARNING);
        }

        // ⭐ AUTO FETCH allUsersForSearch (giống Mode 2) ─────────────────────────
        // Nếu allUsersForSearch chưa mở rộng toàn tổ chức (chỉ bằng selected users)
        // → tự động fetch toàn bộ user từ Admin SDK để tìm file/folder thất lạc tốt hơn.
        String adminEmail = appConfig.adminEmail;
        java.util.List<String> searchList = new java.util.ArrayList<>(Config.getAllUsersForSearch());

        // Đảm bảo selected users + admin luôn có trong list
        for (String u : users) {
            if (!searchList.contains(u)) searchList.add(u);
        }
        if (adminEmail != null && !adminEmail.isBlank() && !searchList.contains(adminEmail)) {
            searchList.add(adminEmail);
        }

        if (searchList.size() <= users.size()) {
            // Search list chỉ bằng danh sách chạy → chưa có toàn tổ chức → fetch Admin SDK
            pt.log("⚠️  Search list chỉ có " + searchList.size()
                    + " user (bằng danh sách chạy) — tự động fetch toàn tổ chức...",
                    ProgressTracker.LogLevel.WARNING);
            try {
                com.google.auth.oauth2.GoogleCredentials creds = ServiceAccountCredentials
                        .fromStream(new FileInputStream(Config.getServiceAccountFile()))
                        .createScoped(List.of("https://www.googleapis.com/auth/admin.directory.user.readonly"))
                        .createDelegated(adminEmail);

                com.google.api.services.directory.Directory adminSdk =
                        new com.google.api.services.directory.Directory.Builder(
                                GoogleNetHttpTransport.newTrustedTransport(),
                                GsonFactory.getDefaultInstance(),
                                new HttpCredentialsAdapter(creds))
                                .setApplicationName("Drive Recovery Tool v2.0")
                                .build();

                String pageToken = null;
                int fetched = 0;
                do {
                    com.google.api.services.directory.Directory.Users.List req =
                            adminSdk.users().list()
                                    .setCustomer("my_customer")
                                    .setMaxResults(500)
                                    .setOrderBy("email");
                    if (pageToken != null) req.setPageToken(pageToken);
                    com.google.api.services.directory.model.Users usersResult = req.execute();
                    if (usersResult.getUsers() != null) {
                        for (com.google.api.services.directory.model.User u : usersResult.getUsers()) {
                            String email = u.getPrimaryEmail();
                            if (email != null && !email.isBlank() && !searchList.contains(email)) {
                                searchList.add(email);
                                fetched++;
                            }
                        }
                    }
                    pageToken = usersResult.getNextPageToken();
                } while (pageToken != null);

                pt.log("✅ Đã fetch " + fetched + " users từ Admin SDK → search list: "
                        + searchList.size() + " users", ProgressTracker.LogLevel.SUCCESS);
            } catch (Exception eFetch) {
                pt.log("⚠️ Không fetch được users từ Admin SDK: " + eFetch.getMessage()
                        + " → tiếp tục với " + searchList.size() + " users hiện có",
                        ProgressTracker.LogLevel.WARNING);
            }
        } else {
            pt.log("ℹ️  Dùng search list từ UI: " + searchList.size() + " users (đã đủ toàn tổ chức)",
                    ProgressTracker.LogLevel.INFO);
        }

        // Inject search list vào Config để findAndMoveFile/FolderWithResult dùng trong suốt Mode 1
        appConfig.allUsersForSearch = searchList;
        Config.applyFromAppConfig(appConfig);
        pt.log("👥 Search list (" + searchList.size() + " user): sẵn sàng tìm kiếm toàn tổ chức",
                ProgressTracker.LogLevel.INFO);
        // ─────────────────────────────────────────────────────────────────────────

        int processed = 0;
        for (int i = 0; i < users.size(); i++) {
            checkStopped();
            String userEmail = users.get(i);

            if (completedUsers.contains(userEmail)) {
                pt.log("⏭️  Bỏ qua (đã xong): " + userEmail, ProgressTracker.LogLevel.DETAIL);
                continue;
            }

            processed++;
            pt.onUserStart(userEmail, i + 1, users.size());
            pt.log("\n╔══════════════════════════════════════════════════════", ProgressTracker.LogLevel.HEADER);
            pt.log("║ NGƯỜI DÙNG " + (i + 1) + "/" + users.size() + ": " + userEmail, ProgressTracker.LogLevel.HEADER);
            pt.log("╚══════════════════════════════════════════════════════", ProgressTracker.LogLevel.HEADER);

            try {
                Drive driveService = createDriveServiceForUser(userEmail);
                DriveActivity activityService = createActivityServiceForUser(userEmail);
                logFilterConfig(pt);

                DriveRecoveryService recoveryService = new DriveRecoveryService(driveService, activityService);
                recoveryService.processUserDrive(userEmail);

                pt.log("\n📊 Đang tạo báo cáo Excel cho: " + userEmail, ProgressTracker.LogLevel.INFO);
                String reportPath = recoveryService.generateExcelReport(userEmail);
                pt.log("✅ Báo cáo: " + reportPath, ProgressTracker.LogLevel.SUCCESS);

                completedUsers.add(userEmail);
                saveCheckpoint(completedUsers);

            } catch (Exception e) {
                pt.log("❌ Lỗi user " + userEmail + ": " + e.getMessage(), ProgressTracker.LogLevel.ERROR);
                pt.log("⏭️  Tiếp tục user tiếp theo...", ProgressTracker.LogLevel.WARNING);
            }
        }

        pt.log("\n════════════════════════════════════════", ProgressTracker.LogLevel.HEADER);
        pt.log("   ✅ HOÀN THÀNH - Đã xử lý " + processed + " người dùng", ProgressTracker.LogLevel.SUCCESS);
        pt.log("════════════════════════════════════════", ProgressTracker.LogLevel.HEADER);
        deleteCheckpoint();
    }

    // ============================================
    // MODE 2: Recovery 1 Folder cụ thể theo ID
    // ============================================
    private void runMode2(ProgressTracker pt) throws Exception {
        String folderId = appConfig.folderIdMode2;
        String adminEmail = appConfig.adminEmail;

        pt.log("════════════════════════════════════════", ProgressTracker.LogLevel.HEADER);
        pt.log("   🔍 MODE 2: KHÔI PHỤC 1 FOLDER CỤ THỂ", ProgressTracker.LogLevel.HEADER);
        pt.log("════════════════════════════════════════", ProgressTracker.LogLevel.HEADER);
        pt.log("📁 Folder ID: " + folderId, ProgressTracker.LogLevel.INFO);

        // ⭐ BƯỚC 1: Dùng admin fetch folder → detect owner + lấy tên folder
        // (Admin có thể thấy mọi folder trong tổ chức, kể cả Shared Drive)
        pt.log("🔍 Đang xác định owner + tên folder bằng admin " + adminEmail + "...", ProgressTracker.LogLevel.INFO);
        String ownerEmail = adminEmail; // fallback nếu không detect được
        String folderName  = folderId;  // fallback tên = ID nếu không lấy được
        boolean ownerDetected = false;

        // Nếu user đã chọn sẵn email trong UI → ưu tiên dùng (có thể override sau nếu admin detect được)
        if (appConfig.selectedUsers != null && !appConfig.selectedUsers.isEmpty()) {
            ownerEmail = appConfig.selectedUsers.get(0);
            pt.log("👤 Dùng user được chọn trong UI: " + ownerEmail, ProgressTracker.LogLevel.INFO);
            ownerDetected = true;
        }

        try {
            Drive adminDrive = createDriveServiceForUser(adminEmail);
            com.google.api.services.drive.model.File folderMeta = adminDrive.files().get(folderId)
                    .setFields("id, name, owners, driveId")
                    .setSupportsAllDrives(true)
                    .execute();

            // Lấy tên folder → truyền xuống processSpecificFolder (không fetch lại)
            if (folderMeta.getName() != null && !folderMeta.getName().isBlank()) {
                folderName = folderMeta.getName();
                pt.log("📁 Tên folder: " + folderName, ProgressTracker.LogLevel.INFO);
            } else {
                pt.log("⚠️ Admin fetch được metadata nhưng tên folder rỗng — có thể folder bị ẩn hoặc không có quyền xem tên", ProgressTracker.LogLevel.WARNING);
            }

            // Trường hợp My Drive: lấy owner từ metadata
            if (folderMeta.getOwners() != null && !folderMeta.getOwners().isEmpty()) {
                String detected = folderMeta.getOwners().get(0).getEmailAddress();
                if (detected != null && !detected.isBlank()) {
                    ownerEmail = detected;
                    ownerDetected = true;
                    pt.log("👤 Owner (My Drive): " + ownerEmail, ProgressTracker.LogLevel.SUCCESS);
                }
            }
            // Trường hợp Shared Drive: owners = null → dùng selectedUsers (đã set ở trên) hoặc admin
            else if (folderMeta.getDriveId() != null && !folderMeta.getDriveId().isBlank()) {
                pt.log("ℹ️  Folder trong Shared Drive: " + folderMeta.getDriveId()
                        + " → impersonate: " + ownerEmail, ProgressTracker.LogLevel.WARNING);
            } else if (!ownerDetected) {
                pt.log("⚠️ Không tìm được owner từ metadata và không có user nào được chọn trong UI", ProgressTracker.LogLevel.WARNING);
            }

        } catch (Exception e) {
            pt.log("⚠️ Không fetch được folder metadata qua admin: " + e.getMessage(), ProgressTracker.LogLevel.WARNING);
            if (!ownerDetected) {
                pt.log("⚠️ Chưa detect được owner → sẽ thử lần lượt từng user trong selectedUsers để tìm ai có quyền đọc folder", ProgressTracker.LogLevel.WARNING);
            }
        }

        // ⭐ FIX: Nếu chưa detect được owner (ownerEmail vẫn là adminEmail) → thử từng selectedUser
        // Admin impersonate chính mình KHÔNG có quyền xem My Drive của user khác
        // → phải tìm user thực sự có quyền đọc folderId này
        if (!ownerDetected || ownerEmail.equals(adminEmail)) {
            pt.log("🔍 Đang thử từng user trong selectedUsers để tìm owner thực sự của folder...", ProgressTracker.LogLevel.INFO);
            List<String> candidates = new ArrayList<>();
            if (appConfig.selectedUsers != null) candidates.addAll(appConfig.selectedUsers);
            // Thêm một số user từ Config nếu có
            candidates.addAll(Config.getAllUsersForSearch().stream()
                    .filter(u -> !candidates.contains(u))
                    .limit(10)  // Thử tối đa 10 user đầu để không mất quá nhiều thời gian
                    .collect(java.util.stream.Collectors.toList()));

            for (String candidate : candidates) {
                if (candidate.equals(adminEmail)) continue; // admin đã thử rồi
                try {
                    Drive candidateDrive = createDriveServiceForUser(candidate);
                    com.google.api.services.drive.model.File meta = candidateDrive.files().get(folderId)
                            .setFields("id, name, owners")
                            .setSupportsAllDrives(true)
                            .execute();
                    if (meta != null) {
                        ownerEmail = candidate;
                        ownerDetected = true;
                        if (meta.getName() != null && !meta.getName().isBlank() && folderName.equals(folderId)) {
                            folderName = meta.getName();
                            pt.log("📁 Tên folder (qua candidate): " + folderName, ProgressTracker.LogLevel.INFO);
                        }
                        pt.log("✅ Tìm thấy: user [" + candidate + "] có quyền đọc folder → dùng làm owner", ProgressTracker.LogLevel.SUCCESS);
                        break;
                    }
                } catch (Exception ignored) {
                    pt.log("  ⬝ [" + candidate + "] không có quyền", ProgressTracker.LogLevel.DETAIL);
                }
            }

            if (!ownerDetected || ownerEmail.equals(adminEmail)) {
                pt.log("⚠️ Không tìm được user nào có quyền đọc folder → tiếp tục với adminEmail (kết quả có thể thiếu)", ProgressTracker.LogLevel.WARNING);
            }
        }

        // ⭐ BƯỚC 2: Xây dựng search list để tìm file/folder thất lạc
        java.util.List<String> searchList = new java.util.ArrayList<>(Config.getAllUsersForSearch());
        if (!searchList.contains(ownerEmail)) searchList.add(ownerEmail);
        if (!searchList.contains(adminEmail)) searchList.add(adminEmail);
        for (String u : appConfig.selectedUsers) {
            if (!searchList.contains(u)) searchList.add(u);
        }

        // ⭐ FIX: Nếu search list quá ít (<= 2 user) → tự động fetch toàn bộ user từ domain
        if (searchList.size() <= 2) {
            pt.log("⚠️  Search list chỉ có " + searchList.size() + " user — tự động fetch toàn tổ chức...", ProgressTracker.LogLevel.WARNING);
            try {
                com.google.auth.oauth2.GoogleCredentials creds = ServiceAccountCredentials
                        .fromStream(new FileInputStream(Config.getServiceAccountFile()))
                        .createScoped(List.of("https://www.googleapis.com/auth/admin.directory.user.readonly"))
                        .createDelegated(adminEmail);

                com.google.api.services.directory.Directory adminSdk =
                        new com.google.api.services.directory.Directory.Builder(
                                GoogleNetHttpTransport.newTrustedTransport(),
                                GsonFactory.getDefaultInstance(),
                                new HttpCredentialsAdapter(creds))
                                .setApplicationName("Drive Recovery Tool v2.0")
                                .build();

                String pageToken = null;
                int fetched = 0;
                do {
                    com.google.api.services.directory.Directory.Users.List req =
                            adminSdk.users().list()
                                    .setCustomer("my_customer")
                                    .setMaxResults(500)
                                    .setOrderBy("email");
                    if (pageToken != null) req.setPageToken(pageToken);
                    com.google.api.services.directory.model.Users usersResult = req.execute();
                    if (usersResult.getUsers() != null) {
                        for (com.google.api.services.directory.model.User u : usersResult.getUsers()) {
                            String email = u.getPrimaryEmail();
                            if (email != null && !email.isBlank() && !searchList.contains(email)) {
                                searchList.add(email);
                                fetched++;
                            }
                        }
                    }
                    pageToken = usersResult.getNextPageToken();
                } while (pageToken != null);
                pt.log("✅ Đã fetch " + fetched + " users → search list: " + searchList.size() + " users", ProgressTracker.LogLevel.SUCCESS);
            } catch (Exception eFetch) {
                pt.log("⚠️ Không fetch được users: " + eFetch.getMessage(), ProgressTracker.LogLevel.WARNING);
            }
        }

        // Inject vào Config để findAndMoveFile/FolderWithResult dùng
        appConfig.allUsersForSearch = searchList;
        Config.applyFromAppConfig(appConfig);

        pt.log("👤 Impersonate owner  : " + ownerEmail, ProgressTracker.LogLevel.INFO);
        pt.log("🔎 Tìm folder thiếu  : " + Config.getSearchFolders(), ProgressTracker.LogLevel.INFO);
        pt.log("🔎 Tìm file thiếu    : " + Config.getSearchFiles(),   ProgressTracker.LogLevel.INFO);
        pt.log("👥 Search list (" + searchList.size() + " user): " + searchList, ProgressTracker.LogLevel.INFO);

        checkStopped();

        // Tạo Drive + Activity service với đúng owner — Activity API chỉ thấy activity của user đang impersonate
        Drive driveService = createDriveServiceForUser(ownerEmail);
        DriveActivity activityService = createActivityServiceForUser(ownerEmail);
        logFilterConfig(pt);

        DriveRecoveryService recoveryService = new DriveRecoveryService(driveService, activityService);
        // Truyền folderName (đã lấy qua admin ở BƯỚC 1) → không cần fetch lại trong DriveRecoveryService
        String reportPath = recoveryService.processSpecificFolder(folderId, folderName, ownerEmail);

        pt.log("\n✅ Hoàn thành! Báo cáo: " + reportPath, ProgressTracker.LogLevel.SUCCESS);
    }


    // ============================================
    // MODE 3: Detailed Activity - 1 User
    // ============================================
    private void runMode3(ProgressTracker pt) throws Exception {
        String userEmail = Config.getUsersToCheck().isEmpty()
                ? appConfig.adminEmail : Config.getUsersToCheck().get(0);

        pt.log("════════════════════════════════════════", ProgressTracker.LogLevel.HEADER);
        pt.log("   🔍 MODE 3: PHÂN TÍCH TẤT CẢ FOLDERS CỦA 1 USER", ProgressTracker.LogLevel.HEADER);
        pt.log("════════════════════════════════════════", ProgressTracker.LogLevel.HEADER);
        pt.log("👤 User: " + userEmail, ProgressTracker.LogLevel.INFO);

        checkStopped();
        Drive driveService = createDriveServiceForUser(userEmail);
        DriveActivity activityService = createActivityServiceForUser(userEmail);
        logFilterConfig(pt);

        DetailedActivityService detailedService = new DetailedActivityService(driveService, activityService);
        String reportPath = detailedService.analyzeAllFoldersForUser(userEmail);

        pt.log("\n✅ Hoàn thành! Báo cáo: " + reportPath, ProgressTracker.LogLevel.SUCCESS);
    }

    // ============================================
    // MODE 4: Detailed Activity - All Users
    // ============================================
    private void runMode4(ProgressTracker pt) throws Exception {
        List<String> users = Config.getUsersToCheck();

        pt.log("════════════════════════════════════════", ProgressTracker.LogLevel.HEADER);
        pt.log("   🔍 MODE 4: PHÂN TÍCH TOÀN BỘ USERS", ProgressTracker.LogLevel.HEADER);
        pt.log("════════════════════════════════════════", ProgressTracker.LogLevel.HEADER);
        pt.log("📋 Sẽ phân tích " + users.size() + " người dùng", ProgressTracker.LogLevel.INFO);

        checkStopped();
        Drive driveService = createDriveService();
        DriveActivity activityService = createActivityService();
        logFilterConfig(pt);

        DetailedActivityService detailedService = new DetailedActivityService(driveService, activityService);
        String reportPath = detailedService.analyzeAllFoldersForAllUsers(users);

        pt.log("\n✅ Hoàn thành! Báo cáo tổng hợp: " + reportPath, ProgressTracker.LogLevel.SUCCESS);
    }

    // ============================================
    // HELPERS
    // ============================================

    private void checkStopped() throws StoppedException {
        if (ProgressTracker.getInstance().isStopped()) {
            throw new StoppedException();
        }
    }

    private void logFilterConfig(ProgressTracker pt) {
        pt.log("\n⚙️  CẤU HÌNH FILTER:", ProgressTracker.LogLevel.INFO);
        if (Config.getActivityDays() > 0) {
            pt.log("   - Đọc từ " + Config.getActivityDays() + " ngày trước", ProgressTracker.LogLevel.INFO);
        } else {
            pt.log("   - Đọc từ quá khứ (không giới hạn)", ProgressTracker.LogLevel.INFO);
        }
        String endDate = Config.getActivityEndDate();
        if (endDate != null && !endDate.isEmpty()) {
            pt.log("   - ✂️  Cắt tại: " + endDate, ProgressTracker.LogLevel.WARNING);
        } else {
            pt.log("   - Đọc đến hiện tại", ProgressTracker.LogLevel.INFO);
        }
    }

    private String getStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    // ============================================
    // SERVICE CREATION
    // ============================================

    private GoogleCredentials createCredentials() throws IOException {
        if (Config.isUseJsonFile()) {
            return ServiceAccountCredentials
                    .fromStream(new FileInputStream(Config.getServiceAccountFile()))
                    .createScoped(Config.SCOPES);
        } else {
            String json = buildServiceAccountJson();
            return ServiceAccountCredentials
                    .fromStream(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))
                    .createScoped(Config.SCOPES);
        }
    }

    private String buildServiceAccountJson() {
        String pkId = (Config.getPrivateKeyId() != null && !Config.getPrivateKeyId().isEmpty())
                ? Config.getPrivateKeyId() : "0";
        String cid = (Config.getClientId() != null && !Config.getClientId().isEmpty())
                ? Config.getClientId() : "0";
        return String.format("""
                {
                  "type": "service_account",
                  "project_id": "%s",
                  "private_key_id": "%s",
                  "private_key": "%s",
                  "client_email": "%s",
                  "client_id": "%s",
                  "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                  "token_uri": "https://oauth2.googleapis.com/token",
                  "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs"
                }""",
                Config.getProjectId(), pkId,
                Config.getPrivateKey().replace("\n", "\\n"),
                Config.getServiceAccountEmail(), cid);
    }

    private Drive createDriveServiceForUser(String userEmail) throws IOException, GeneralSecurityException {
        GoogleCredentials creds = createCredentials().createDelegated(userEmail);
        return new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(creds))
                .setApplicationName("Drive Recovery Tool v2.0")
                .build();
    }

    private DriveActivity createActivityServiceForUser(String userEmail) throws IOException, GeneralSecurityException {
        GoogleCredentials creds = createCredentials().createDelegated(userEmail);
        return new DriveActivity.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(creds))
                .setApplicationName("Drive Recovery Tool v2.0")
                .build();
    }

    private Drive createDriveService() throws IOException, GeneralSecurityException {
        List<String> users = Config.getUsersToCheck();
        if (users == null || users.isEmpty()) {
            throw new IllegalStateException("Chưa có người dùng nào được chọn!");
        }
        return createDriveServiceForUser(users.get(0));
    }

    private DriveActivity createActivityService() throws IOException, GeneralSecurityException {
        List<String> users = Config.getUsersToCheck();
        if (users == null || users.isEmpty()) {
            throw new IllegalStateException("Chưa có người dùng nào được chọn!");
        }
        return createActivityServiceForUser(users.get(0));
    }

    // ============================================
    // INNER CLASSES
    // ============================================

    /** Thrown when user requests stop */
    static class StoppedException extends Exception {
        StoppedException() { super("Stopped by user"); }
    }
}
