package driverecovery;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.driveactivity.v2.DriveActivity;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Scanner;

public class Main {

    // ============================================
    // ⭐ MODE SELECTION
    // ============================================
    private static final String MODE_NORMAL = "1";
    private static final String MODE_DETAILED_ACTIVITY = "2";
    private static final String MODE_DETAILED_USER = "3";      // 🆕 THÊM DÒNG NÀY
    private static final String MODE_DETAILED_ALL_USERS = "4"; // 🆕 THÊM DÒNG NÀY


    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("   DRIVE RECOVERY TOOL");
        System.out.println("========================================");
        System.out.println("Phát triển bởi: Drive Recovery Team");
        System.out.println("Version: 2.0.0\n");

        // ⭐ CHỌN MODE
        System.out.println("📋 CHỌN CHE ĐỘ CHẠY:");
        System.out.println("  1. Normal Mode - Recovery files cho tất cả users");
        System.out.println("  2. Detailed Activity Log - Phân tích chi tiết 1 folder");
        System.out.println("  3. Detailed Activity Log - Phân tích 1 user (tất cả folders)");  // 🆕 THÊM
        System.out.println("  4. Detailed Activity Log - Phân tích toàn bộ users");           // 🆕 THÊM
        System.out.print("\nNhập lựa chọn (1/2/3/4): ");  // 🆕 SỬA

        Scanner scanner = new Scanner(System.in);
        String mode = scanner.nextLine().trim();

        if (MODE_DETAILED_ACTIVITY.equals(mode)) {
            runDetailedActivityMode(scanner);
        } else if (MODE_DETAILED_USER.equals(mode)) {        // 🆕 THÊM
            runDetailedActivityUserMode(scanner);             // 🆕 THÊM
        } else if (MODE_DETAILED_ALL_USERS.equals(mode)) {   // 🆕 THÊM
            runDetailedActivityAllUsersMode(scanner);         // 🆕 THÊM
        } else {
            runNormalMode();
        }

        scanner.close();
    }

    /**
     * ⭐ MODE 1: Normal Recovery Mode (như cũ)
     */
    private static void runNormalMode() {
        System.out.println("\n🔧 Đang chạy NORMAL MODE...\n");

        try {
            System.out.println("👥 Danh sách users cần kiểm tra: " + Config.USERS_TO_CHECK.size());

            // ✅ LOOP QUA TỪNG USER - MỖI USER TẠO 1 FILE EXCEL RIÊNG
            for (int i = 0; i < Config.USERS_TO_CHECK.size(); i++) {
                String userEmail = Config.USERS_TO_CHECK.get(i);

                System.out.println("\n╔════════════════════════════════════════════════════════════");
                System.out.println("║ USER " + (i + 1) + "/" + Config.USERS_TO_CHECK.size() + ": " + userEmail);
                System.out.println("╚════════════════════════════════════════════════════════════");

                try {
                    // 🆕 TẠO SERVICE MỚI CHO TỪNG USER
                    System.out.println("🔧 Đang khởi tạo services cho user: " + userEmail);
                    Drive driveService = createDriveService();
                    DriveActivity activityService = createActivityService();
                    System.out.println("✅ Services đã sẵn sàng\n");

                    // 🆕 Hiển thị cấu hình filter
                    System.out.println("⚙️  CẤU HÌNH ACTIVITY FILTER:");
                    if (Config.ACTIVITY_DAYS > 0) {
                        System.out.println("   - Đọc activity từ " + Config.ACTIVITY_DAYS + " ngày trước");
                    } else {
                        System.out.println("   - Đọc activity từ quá khứ (không giới hạn)");
                    }

                    if (Config.ACTIVITY_END_DATE != null && !Config.ACTIVITY_END_DATE.isEmpty()) {
                        System.out.println("   - ✂️  CẮT TẠI: " + Config.ACTIVITY_END_DATE + " (không đọc activity sau ngày này)");
                    } else {
                        System.out.println("   - Đọc đến hiện tại (không giới hạn ngày kết thúc)");
                    }
                    System.out.println("");

                    // 🆕 TẠO RECOVERY SERVICE MỚI CHO TỪNG USER
                    DriveRecoveryService recoveryService = new DriveRecoveryService(
                            driveService,
                            activityService
                    );

                    // 🆕 XỬ LÝ DRIVE CỦA USER NÀY
                    recoveryService.processUserDrive(userEmail);

                    // 🆕 TẠO FILE EXCEL RIÊNG CHO USER NÀY NGAY SAU KHI XỬ LÝ XONG
                    System.out.println("\n╔════════════════════════════════════════════════════════════");
                    System.out.println("║ TẠO BÁO CÁO EXCEL CHO: " + userEmail);
                    System.out.println("╚════════════════════════════════════════════════════════════");
                    System.out.println("📊 Đang tổng hợp dữ liệu...");

                    String reportPath = recoveryService.generateExcelReport(userEmail);

                    System.out.println("✅ Báo cáo đã được tạo thành công cho " + userEmail + "!");
                    System.out.println("📁 Đường dẫn: " + reportPath);
                    System.out.println("");

                } catch (Exception e) {
                    System.err.println("\n❌ LỖI khi xử lý user: " + userEmail);
                    System.err.println("Chi tiết: " + e.getMessage());
                    e.printStackTrace();
                    System.err.println("⏭️  Tiếp tục với user tiếp theo...\n");
                }
            }

            // ✅ KẾT THÚC - ĐÃ TẠO XONG TẤT CẢ FILE EXCEL
            System.out.println("\n========================================");
            System.out.println("   ✅ HOÀN THÀNH TẤT CẢ USERS");
            System.out.println("========================================");
            System.out.println("📊 Đã tạo " + Config.USERS_TO_CHECK.size() + " file Excel riêng biệt.");
            System.out.println("📁 Tất cả file đều nằm trong thư mục Export");
            System.out.println("\nHãy mở từng file Excel để xem chi tiết báo cáo của từng user.");

        } catch (Exception e) {
            System.err.println("\n╔════════════════════════════════════════════════════════════");
            System.err.println("║ ❌ LỖI NGHIÊM TRỌNG");
            System.err.println("╚════════════════════════════════════════════════════════════");
            System.err.println("Lỗi: " + e.getMessage());
            System.err.println("\nChi tiết lỗi:");
            e.printStackTrace();
            System.err.println("\n⚠️ Vui lòng kiểm tra:");
            System.err.println("  1. Service Account có quyền truy cập Drive không?");
            System.err.println("  2. Domain-wide delegation đã được bật chưa?");
            System.err.println("  3. Private Key và Email có đúng không?");
            System.exit(1);
        }
    }

    /**
     * ⭐ MODE 2: Detailed Activity Log Mode (GIỐNG Apps Script)
     */
    private static void runDetailedActivityMode(Scanner scanner) {
        System.out.println("\n🔍 Đang chạy DETAILED ACTIVITY LOG MODE...\n");

        // Nhập Folder ID
        System.out.print("📁 Nhập Folder ID cần phân tích: ");
        String folderId = scanner.nextLine().trim();

        if (folderId.isEmpty()) {
            System.err.println("❌ Folder ID không được để trống!");
            System.exit(1);
        }

        try {
            System.out.println("\n🔧 Đang khởi tạo services...");
            Drive driveService = createDriveService();
            DriveActivity activityService = createActivityService();
            System.out.println("✅ Services đã sẵn sàng\n");

// 🆕 THÊM ĐOẠN NÀY - Hiển thị cấu hình filter
            System.out.println("⚙️  CẤU HÌNH ACTIVITY FILTER:");
            if (Config.ACTIVITY_DAYS > 0) {
                System.out.println("   - Đọc activity từ " + Config.ACTIVITY_DAYS + " ngày trước");
            } else {
                System.out.println("   - Đọc activity từ quá khứ (không giới hạn)");
            }

            if (Config.ACTIVITY_END_DATE != null && !Config.ACTIVITY_END_DATE.isEmpty()) {
                System.out.println("   - ✂️  CẮT TẠI: " + Config.ACTIVITY_END_DATE + " (không đọc activity sau ngày này)");
            } else {
                System.out.println("   - Đọc đến hiện tại (không giới hạn ngày kết thúc)");
            }
            System.out.println("");
// 🆕 KẾT THÚC ĐOẠN THÊM

            DetailedActivityService detailedService = new DetailedActivityService(
                    driveService,
                    activityService
            );

            String userEmail = Config.USERS_TO_CHECK.get(0);
            System.out.println("👤 User: " + userEmail);
            System.out.println("📁 Folder ID: " + folderId);

            System.out.println("\n========================================");
            System.out.println("   BẮT ĐẦU PHÂN TÍCH CHI TIẾT");
            System.out.println("========================================\n");

            String reportPath = detailedService.analyzeFolder(folderId, userEmail);

            System.out.println("\n========================================");
            System.out.println("   ✅ HOÀN THÀNH");
            System.out.println("========================================");
            System.out.println("📁 Báo cáo chi tiết: " + reportPath);
            System.out.println("\nHãy mở file Excel để xem:");
            System.out.println("  - Summary: Tổng quan activity");
            System.out.println("  - Files + Current Status: Trạng thái hiện tại");
            System.out.println("  - Complete Timeline: Timeline đầy đủ");
            System.out.println("  - File Details: Chi tiết từng file");
            System.out.println("  - Deleted Files: File đã xóa");

        } catch (Exception e) {
            System.err.println("\n❌ LỖI: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 🆕 MODE 3: Detailed Activity cho TẤT CẢ FOLDERS của 1 USER
     */
    private static void runDetailedActivityUserMode(Scanner scanner) {
        System.out.println("\n🔍 Đang chạy DETAILED ACTIVITY LOG MODE (1 USER - TẤT CẢ FOLDERS)...\n");

        System.out.print("👤 Nhập email user cần phân tích (Enter = dùng user đầu tiên): ");
        String userEmail = scanner.nextLine().trim();

        if (userEmail.isEmpty()) {
            userEmail = Config.USERS_TO_CHECK.get(0);
            System.out.println("→ Sử dụng user mặc định: " + userEmail);
        }

        try {
            System.out.println("\n🔧 Đang khởi tạo services...");
            Drive driveService = createDriveServiceForUser(userEmail);
            DriveActivity activityService = createActivityServiceForUser(userEmail);
            System.out.println("✅ Services đã sẵn sàng\n");

// 🆕 THÊM ĐOẠN NÀY - Hiển thị cấu hình filter
            System.out.println("⚙️  CẤU HÌNH ACTIVITY FILTER:");
            if (Config.ACTIVITY_DAYS > 0) {
                System.out.println("   - Đọc activity từ " + Config.ACTIVITY_DAYS + " ngày trước");
            } else {
                System.out.println("   - Đọc activity từ quá khứ (không giới hạn)");
            }

            if (Config.ACTIVITY_END_DATE != null && !Config.ACTIVITY_END_DATE.isEmpty()) {
                System.out.println("   - ✂️  CẮT TẠI: " + Config.ACTIVITY_END_DATE + " (không đọc activity sau ngày này)");
            } else {
                System.out.println("   - Đọc đến hiện tại (không giới hạn ngày kết thúc)");
            }
            System.out.println("");
// 🆕 KẾT THÚC ĐOẠN THÊM

            DetailedActivityService detailedService = new DetailedActivityService(
                    driveService,
                    activityService
            );

            System.out.println("========================================");
            System.out.println("   BẮT ĐẦU PHÂN TÍCH TẤT CẢ FOLDERS");
            System.out.println("========================================\n");

            String reportPath = detailedService.analyzeAllFoldersForUser(userEmail);

            System.out.println("\n========================================");
            System.out.println("   ✅ HOÀN THÀNH");
            System.out.println("========================================");
            System.out.println("📁 Báo cáo chi tiết: " + reportPath);
            System.out.println("\nFile Excel chứa:");
            System.out.println("  - Summary: Tổng hợp tất cả folders");
            System.out.println("  - Chi tiết từng folder (tối đa 10 folders)");

        } catch (Exception e) {
            System.err.println("\n❌ LỖI: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 🆕 MODE 4: Detailed Activity cho TẤT CẢ USERS trong tổ chức
     */
    private static void runDetailedActivityAllUsersMode(Scanner scanner) {
        System.out.println("\n🔍 Đang chạy DETAILED ACTIVITY LOG MODE (TOÀN BỘ USERS)...\n");

        System.out.println("⚠️  CHẾ ĐỘ NÀY SẼ MẤT NHIỀU THỜI GIAN!");
        System.out.println("📋 Sẽ phân tích " + Config.USERS_TO_CHECK.size() + " users");
        System.out.print("Bạn có chắc chắn muốn tiếp tục? (y/n): ");
        String confirm = scanner.nextLine().trim().toLowerCase();

        if (!confirm.equals("y")) {
            System.out.println("❌ Đã hủy.");
            System.exit(0);
        }

        try {
            System.out.println("\n🔧 Đang khởi tạo services...");
            Drive driveService = createDriveService();
            DriveActivity activityService = createActivityService();
            System.out.println("✅ Services đã sẵn sàng\n");

// 🆕 THÊM ĐOẠN NÀY - Hiển thị cấu hình filter
            System.out.println("⚙️  CẤU HÌNH ACTIVITY FILTER:");
            if (Config.ACTIVITY_DAYS > 0) {
                System.out.println("   - Đọc activity từ " + Config.ACTIVITY_DAYS + " ngày trước");
            } else {
                System.out.println("   - Đọc activity từ quá khứ (không giới hạn)");
            }

            if (Config.ACTIVITY_END_DATE != null && !Config.ACTIVITY_END_DATE.isEmpty()) {
                System.out.println("   - ✂️  CẮT TẠI: " + Config.ACTIVITY_END_DATE + " (không đọc activity sau ngày này)");
            } else {
                System.out.println("   - Đọc đến hiện tại (không giới hạn ngày kết thúc)");
            }
            System.out.println("");
// 🆕 KẾT THÚC ĐOẠN THÊM

            DetailedActivityService detailedService = new DetailedActivityService(
                    driveService,
                    activityService
            );

            System.out.println("========================================");
            System.out.println("   BẮT ĐẦU PHÂN TÍCH TOÀN TỔ CHỨC");
            System.out.println("========================================\n");

            String reportPath = detailedService.analyzeAllFoldersForAllUsers(Config.USERS_TO_CHECK);

            System.out.println("\n========================================");
            System.out.println("   ✅ HOÀN THÀNH");
            System.out.println("========================================");
            System.out.println("📁 Báo cáo tổng hợp: " + reportPath);
            System.out.println("\nFile Excel chứa:");
            System.out.println("  - Organization Summary: Tổng quan toàn tổ chức");
            System.out.println("  - Chi tiết từng user (tối đa 20 users)");

        } catch (Exception e) {
            System.err.println("\n❌ LỖI: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 🆕 Helper: Create Drive service for specific user
     */
    private static Drive createDriveServiceForUser(String userEmail) throws IOException, GeneralSecurityException {
        GoogleCredentials credentials = createCredentials()
                .createDelegated(userEmail);

        return new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials)
        )
                .setApplicationName("Drive Recovery Tool v2.0")
                .build();
    }

    /**
     * 🆕 Helper: Create Activity service for specific user
     */
    private static DriveActivity createActivityServiceForUser(String userEmail) throws IOException, GeneralSecurityException {
        GoogleCredentials credentials = createCredentials()
                .createDelegated(userEmail);

        return new DriveActivity.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials)
        )
                .setApplicationName("Drive Recovery Tool v2.0")
                .build();
    }

    // ============================================
    // SERVICE CREATION (giữ nguyên như cũ)
    // ============================================

    private static GoogleCredentials createCredentials() throws IOException {
        GoogleCredentials credentials;

        if (Config.USE_JSON_FILE) {
            System.out.println("📄 Đang đọc credentials từ file: " + Config.SERVICE_ACCOUNT_FILE);
            try {
                credentials = ServiceAccountCredentials
                        .fromStream(new FileInputStream(Config.SERVICE_ACCOUNT_FILE))
                        .createScoped(Config.SCOPES);
                System.out.println("✅ Đã load credentials từ file JSON");
            } catch (Exception e) {
                System.err.println("❌ Không thể đọc file JSON: " + e.getMessage());
                throw e;
            }
        } else {
            System.out.println("🔑 Đang tạo credentials từ Private Key...");

            if (Config.SERVICE_ACCOUNT_EMAIL == null || Config.SERVICE_ACCOUNT_EMAIL.isEmpty()) {
                throw new IllegalArgumentException("SERVICE_ACCOUNT_EMAIL không được để trống!");
            }
            if (Config.PRIVATE_KEY == null || Config.PRIVATE_KEY.isEmpty()) {
                throw new IllegalArgumentException("PRIVATE_KEY không được để trống!");
            }
            if (Config.PROJECT_ID == null || Config.PROJECT_ID.isEmpty()) {
                throw new IllegalArgumentException("PROJECT_ID không được để trống!");
            }

            String privateKeyId = (Config.PRIVATE_KEY_ID != null && !Config.PRIVATE_KEY_ID.isEmpty())
                    ? Config.PRIVATE_KEY_ID
                    : "0";

            String clientId = (Config.CLIENT_ID != null && !Config.CLIENT_ID.isEmpty())
                    ? Config.CLIENT_ID
                    : "0";

            String jsonContent = String.format(
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

            try {
                credentials = ServiceAccountCredentials
                        .fromStream(new ByteArrayInputStream(jsonContent.getBytes(StandardCharsets.UTF_8)))
                        .createScoped(Config.SCOPES);
                System.out.println("✅ Đã tạo credentials từ Private Key");
                System.out.println("📧 Service Account: " + Config.SERVICE_ACCOUNT_EMAIL);
            } catch (Exception e) {
                System.err.println("❌ Lỗi khi tạo credentials: " + e.getMessage());
                throw e;
            }
        }

        return credentials;
    }

    private static Drive createDriveService() throws IOException, GeneralSecurityException {
        String userEmail = Config.USERS_TO_CHECK.get(0);
        System.out.println("👤 Delegate quyền cho user: " + userEmail);

        GoogleCredentials credentials = createCredentials()
                .createDelegated(userEmail);

        return new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials)
        )
                .setApplicationName("Drive Recovery Tool v2.0")
                .build();
    }

    private static DriveActivity createActivityService() throws IOException, GeneralSecurityException {
        GoogleCredentials credentials = createCredentials()
                .createDelegated(Config.USERS_TO_CHECK.get(0));

        return new DriveActivity.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials)
        )
                .setApplicationName("Drive Recovery Tool v2.0")
                .build();
    }
}