package driverecovery;

import java.util.Arrays;
import java.util.List;

public class Config {
    // ===== CHỌN CÁCH XÁC THỰC =====
    public static final boolean USE_JSON_FILE = false;

    // ===== OPTION 1: FILE JSON =====
    public static final String SERVICE_ACCOUNT_FILE = "C:\\Users\\Technical\\Downloads\\geometric-bolt-484802-c6-74cf72900ae6.json";

    // ===== OPTION 2: NHẬP TRỰC TIẾP =====
    public static final String PROJECT_ID = "drive-2-485602";
    public static final String PRIVATE_KEY_ID = "";
    public static final String PRIVATE_KEY =
            "OjpKQctv1vMQKBgQDv8ccBsJfOFIfJIznt\\nCTSd3da4Cj5ib4gs2WrS6g7LyD05pu75D58Douztq4/mgnypOIb+/t9SagQXNM+y\\ndEUeFwhsBeomR+M/iXheu6f6zojPfJgew632TTntsfhiEAwPv/NwmNqaeFS48pOu\\nV7Q3CPGogYVnK3wNNAQPRvWfmQKBgQC1tvkxzUrn95pwC/PMuW3lbukVlD/XDhhb\\n1IKQ8jMuC9pUaaITQ2KQ8JKgbgRSGrIvYQkFI01CDSQWoXUR0wJOf8p/7mchltYc\\nJpnXYs08ACcXmP3if8ZZyag93birM8U8NnpdGqBCpVfVW67WIa8Zo8e0XdhPQEEQ\\npmcYBFRmhwKBgFgfHqxjM81uwtO/CTYhzF4yK/qZYIH8XjHCg7YEWfzDhKDYyylT\\ncF4Ahy1eddH9mFT5urKJ3nDBGNGBBsqOYxgC84fgwDbg7ffWwAtitfWpxpsVMjUS\\nPqCXii/ezc8N/7AFGh3/NUHH8a2fAVflQ/12XnI5Z0oVsVCUCu2lbO35AoGARmpl\\nHntjL4ivfAPlscuZXnMgN5B/PKLlpZAwGGMCmjFVpahZegV+yJOw/iIj4n0d12ZO\\nzILliVb2SR6/8uxF1I2ItxJ3PHjq93Wt390Vks6sV2Sd3YuOHXUbkP2+dflV0QN7\\nX2DWAX15D+C7W5cp91GULby/+dX4YK9a+9+RpTkCgYEAoYfuSt4wOas0qegG4Zi9\\nBQI0mk2wuk2TcKU2SFbn5vK7K3+3rlL7zGnQXFIQl4JTd5BNQJtzqfVFR6DeQnU+\\nzT0yULLSAgnz2ojlntJD7xkIHS2X5fXYx8lTiaK/aFBuJHj0MImJLx6d+LpaObTt\\nd/HhmRING62YYo56YxM1A10=\\n-----END PRIVATE KEY-----\\n";
    public static final String SERVICE_ACCOUNT_EMAIL = "";
    public static final String CLIENT_ID = "";

    // ===== CẤU HÌNH USER =====
    public static final List<String> USERS_TO_CHECK = Arrays.asList(


    );

    // ===== THÊM LIST NÀY: TẤT CẢ USERS ĐỂ TÌM FILE =====
    public static final List<String> ALL_USERS_FOR_SEARCH = Arrays.asList(
//            "admin@chat.gcloud.id.vn",
//            "quinc@chat.gcloud.id.vn"



            // ... thêm tất cả users trong tổ chức
    );

    public static final int ACTIVITY_DAYS = 0;
    // 🆕 THÊM FIELD MỚI
    /**
     * ✨ Ngày KẾT THÚC đọc activity (để trống "" = đọc đến hiện tại)
     * Format: "yyyy-MM-dd"
     * VD: "2026-01-27" = chỉ đọc activity đến hết ngày 27/01/2026
     */
    public static final String ACTIVITY_END_DATE = "";
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
            "https://www.googleapis.com/auth/admin.directory.user.readonly",
            "https://www.googleapis.com/auth/admin.reports.audit.readonly"
    );



    // ============================================
    // ⭐ GUI INJECTION - Gọi trước khi chạy service
    // ============================================

    /**
     * Inject config từ GUI vào các static fields.
     * Cần gọi trước khi tạo DriveRecoveryService hay DetailedActivityService.
     */
    public static void applyFromAppConfig(AppConfig cfg) {
        // --- Auth ---
        _useJsonFile = cfg.useJsonFile;
        _serviceAccountFile = cfg.serviceAccountJsonPath;
        _serviceAccountEmail = cfg.serviceAccountEmail;
        _projectId = cfg.projectId;
        _privateKey = cfg.privateKey;
        _privateKeyId = cfg.privateKeyId;
        _clientId = cfg.clientId;

        // --- Users ---
        _usersToCheck = new java.util.ArrayList<>(cfg.selectedUsers);
        _allUsersForSearch = new java.util.ArrayList<>(cfg.allUsersForSearch);

        // --- Filter ---
        _activityDays = cfg.activityDays;
        _activityEndDate = cfg.activityEndDate;

        // --- Output ---
        _outputDirectory = cfg.outputDirectory.endsWith(java.io.File.separator)
                ? cfg.outputDirectory
                : cfg.outputDirectory + java.io.File.separator;
        _outputFilePrefix = cfg.outputFilePrefix;

        // --- Admin ---
        _adminEmail = cfg.adminEmail;
        _domain = cfg.domain;

        // --- Search options ---
        _searchFolders = cfg.searchFolders;
        _searchFiles   = cfg.searchFiles;
    }

    // Mutable backing fields (GUI sẽ set qua applyFromAppConfig)
    private static boolean _useJsonFile = USE_JSON_FILE;
    private static String _serviceAccountFile = SERVICE_ACCOUNT_FILE;
    private static String _serviceAccountEmail = SERVICE_ACCOUNT_EMAIL;
    private static String _projectId = PROJECT_ID;
    private static String _privateKey = PRIVATE_KEY;
    private static String _privateKeyId = PRIVATE_KEY_ID;
    private static String _clientId = CLIENT_ID;
    private static java.util.List<String> _usersToCheck = USERS_TO_CHECK;
    private static java.util.List<String> _allUsersForSearch = ALL_USERS_FOR_SEARCH;
    private static int _activityDays = ACTIVITY_DAYS;
    private static String _activityEndDate = ACTIVITY_END_DATE;
    private static String _outputDirectory = OUTPUT_DIRECTORY;
    private static String _outputFilePrefix = OUTPUT_FILE_PREFIX;
    private static String _adminEmail = ADMIN_EMAIL;
    private static String _domain = DOMAIN;
    private static boolean _searchFolders = true;
    private static boolean _searchFiles   = true;

    // Getters mutable (dùng trong service thay vì trực tiếp field)
    public static boolean isUseJsonFile() { return _useJsonFile; }
    public static String getServiceAccountFile() { return _serviceAccountFile; }
    public static String getServiceAccountEmail() { return _serviceAccountEmail; }
    public static String getProjectId() { return _projectId; }
    public static String getPrivateKey() { return _privateKey; }
    public static String getPrivateKeyId() { return _privateKeyId; }
    public static String getClientId() { return _clientId; }
    public static java.util.List<String> getUsersToCheck() { return _usersToCheck; }
    public static java.util.List<String> getAllUsersForSearch() { return _allUsersForSearch; }
    /** Thêm 1 user vào search list nếu chưa có (dùng trong Mode 2 khi detect folder owner) */
    public static void addUserForSearch(String email) {
        if (email == null || email.isBlank()) return;
        if (_allUsersForSearch == null) _allUsersForSearch = new java.util.ArrayList<>();
        if (!_allUsersForSearch.contains(email)) _allUsersForSearch.add(email);
    }
    public static int getActivityDays() { return _activityDays; }
    public static String getActivityEndDate() { return _activityEndDate; }
    public static String getOutputDirectory() { return _outputDirectory; }
    public static String getOutputFilePrefix() { return _outputFilePrefix; }
    public static String getAdminEmail() { return _adminEmail; }
    public static String getDomain() { return _domain; }
    public static boolean getSearchFolders() { return _searchFolders; }
    public static boolean getSearchFiles()   { return _searchFiles; }
}