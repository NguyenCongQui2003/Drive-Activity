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
        System.out.print("\nNhập lựa chọn (1 hoặc 2): ");

        Scanner scanner = new Scanner(System.in);
        String mode = scanner.nextLine().trim();

        if (MODE_DETAILED_ACTIVITY.equals(mode)) {
            runDetailedActivityMode(scanner);
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
            System.out.println("🔧 Đang khởi tạo services...");
            Drive driveService = createDriveService();
            DriveActivity activityService = createActivityService();
            System.out.println("✅ Services đã sẵn sàng\n");

            DriveRecoveryService recoveryService = new DriveRecoveryService(
                    driveService,
                    activityService
            );

            System.out.println("👥 Danh sách users cần kiểm tra: " + Config.USERS_TO_CHECK.size());
            for (int i = 0; i < Config.USERS_TO_CHECK.size(); i++) {
                String userEmail = Config.USERS_TO_CHECK.get(i);
                System.out.println("\n╔════════════════════════════════════════════════════════════");
                System.out.println("║ USER " + (i + 1) + "/" + Config.USERS_TO_CHECK.size() + ": " + userEmail);
                System.out.println("╚════════════════════════════════════════════════════════════");

                try {
                    recoveryService.processUserDrive(userEmail);
                } catch (Exception e) {
                    System.err.println("\n❌ LỖI khi kiểm tra " + userEmail);
                    System.err.println("Chi tiết: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            System.out.println("\n╔════════════════════════════════════════════════════════════");
            System.out.println("║ TẠO BÁO CÁO EXCEL");
            System.out.println("╚════════════════════════════════════════════════════════════");
            System.out.println("📊 Đang tổng hợp dữ liệu...");
            String reportPath = recoveryService.generateExcelReport();
            System.out.println("✅ Báo cáo đã được tạo thành công!");
            System.out.println("📁 Đường dẫn: " + reportPath);

            System.out.println("\n========================================");
            System.out.println("   ✅ HOÀN THÀNH");
            System.out.println("========================================");
            System.out.println("Hãy mở file Excel để xem chi tiết báo cáo.");

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