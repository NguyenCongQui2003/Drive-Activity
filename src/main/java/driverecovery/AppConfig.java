package driverecovery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ⭐ APP CONFIG - Runtime configuration (thay thế static fields trong Config.java)
 * GUI sẽ tạo instance này rồi inject vào Config trước khi chạy
 */
public class AppConfig {

    // ===== SERVICE ACCOUNT =====
    public boolean useJsonFile = true;
    public String serviceAccountJsonPath = "";
    public String serviceAccountEmail = "";
    public String projectId = "";
    public String privateKey = "";
    public String privateKeyId = "";
    public String clientId = "";

    // ===== ADMIN =====
    public String adminEmail = "";
    public String domain = "";

    // ===== USERS =====
    public List<String> selectedUsers = new ArrayList<>();
    public List<String> allUsersForSearch = new ArrayList<>();

    // ===== FILTER =====
    public int activityDays = 0;
    public String activityEndDate = "";

    // ===== OUTPUT =====
    public String outputDirectory = "";
    public String outputFilePrefix = "drive-recovery";

    // ===== SEARCH OPTIONS =====
    public boolean searchFolders = true;
    public boolean searchFiles = true;

    // ===== RUN OPTIONS =====
    public String runMode = "1";       // "1" / "2" / "3" / "4"
    public String folderIdMode2 = "";  // Dùng cho mode 2

    // ===== SCOPES =====
    public List<String> scopes = Arrays.asList(
            "https://www.googleapis.com/auth/drive",
            "https://www.googleapis.com/auth/drive.activity.readonly",
            "https://www.googleapis.com/auth/admin.directory.user.readonly"
    );

    // ===== RUNTIME FLAGS (set by MainWindow) =====
    public boolean resumeFromCheckpoint = false;

    /**
     * Validate config trước khi chạy.
     * Trả về null nếu OK, hoặc thông báo lỗi.
     */
    public String validate() {
        // ── Xác thực Service Account ──────────────────────────────
        // GUI hiện chỉ hỗ trợ JSON file — useJsonFile phải true
        if (serviceAccountJsonPath == null || serviceAccountJsonPath.isBlank()) {
            return "Chưa chọn file Service Account JSON!\nKéo thả file .json vào ô bên trái hoặc dùng nút \"Duyệt tìm\".";
        }
        java.io.File f = new java.io.File(serviceAccountJsonPath);
        if (!f.exists() || !f.isFile()) {
            return "File JSON không tồn tại hoặc đường dẫn sai:\n" + serviceAccountJsonPath;
        }

        // ── Người dùng ────────────────────────────────────────────
        // Mode 2 chỉ cần Folder ID + admin account — không bắt buộc selectedUsers
        if (!"2".equals(runMode) && (selectedUsers == null || selectedUsers.isEmpty())) {
            return "Chưa chọn người dùng nào để chạy!\nHãy tích chọn ít nhất 1 người dùng ở bảng bên phải.";
        }

        // ── Thư mục xuất ──────────────────────────────────────────
        if (outputDirectory == null || outputDirectory.isBlank()) {
            return "Chưa cấu hình thư mục xuất báo cáo!\nVui lòng kiểm tra Config.java → OUTPUT_DIRECTORY.";
        }

        // ── Mode 2 cần Folder ID ──────────────────────────────────
        if ("2".equals(runMode) && (folderIdMode2 == null || folderIdMode2.isBlank())) {
            return "Chế độ 2 yêu cầu nhập Mã thư mục (Folder ID)!";
        }

        // ── Mode 2: nếu không có selectedUsers thì cần adminEmail để chạy ──
        if ("2".equals(runMode) && (selectedUsers == null || selectedUsers.isEmpty())
                && (adminEmail == null || adminEmail.isBlank())) {
            return "Chế độ 2 cần ít nhất Email Admin hoặc 1 người dùng được chọn để thực hiện impersonation.";
        }

        return null; // OK
    }
}
