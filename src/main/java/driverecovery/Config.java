package driverecovery;

import java.util.Arrays;
import java.util.List;

public class Config {
    // ===== CHỌN CÁCH XÁC THỰC =====
    public static final boolean USE_JSON_FILE = false;

    // ===== OPTION 1: FILE JSON =====
    public static final String SERVICE_ACCOUNT_FILE = "src/main/resources/service-account-key.json";

    // ===== OPTION 2: NHẬP TRỰC TIẾP =====
    public static final String PROJECT_ID = "";
    public static final String PRIVATE_KEY_ID = "";
    public static final String PRIVATE_KEY =
            "";
    public static final String SERVICE_ACCOUNT_EMAIL = "";
    public static final String CLIENT_ID = "";

    // ===== CẤU HÌNH USER =====
    public static final List<String> USERS_TO_CHECK = Arrays.asList(
            "admin@gcloud.id.vn"
    );

    // ===== THÊM LIST NÀY: TẤT CẢ USERS ĐỂ TÌM FILE =====
    public static final List<String> ALL_USERS_FOR_SEARCH = Arrays.asList(
            ""

            // ... thêm tất cả users trong tổ chức
    );

    public static final int ACTIVITY_DAYS = 0;
    public static final String ADMIN_EMAIL = "";
    public static final String DOMAIN = "";

    // ===== CẤU HÌNH OUTPUT =====
    // Thư mục lưu file Excel
    // Ví dụ: "C:/Users/Technical/Desktop/" hoặc "D:/Reports/" hoặc "Reports/"
    public static final String OUTPUT_DIRECTORY = "C:\\Users\\Technical\\Downloads\\Export\\";

    // Prefix tên file (kết quả: drive-recovery-quinc-20250121_143022.xlsx)
    public static final String OUTPUT_FILE_PREFIX = "drive-recovery";

    // ===== GOOGLE API SCOPES =====
    public static final List<String> SCOPES = Arrays.asList(
            "https://www.googleapis.com/auth/drive",
            "https://www.googleapis.com/auth/drive.activity.readonly",
            "https://www.googleapis.com/auth/admin.directory.user.readonly"
    );


}