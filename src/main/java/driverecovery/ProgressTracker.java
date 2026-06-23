package driverecovery;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ⭐ PROGRESS TRACKER - Singleton bridge giữa backend services và GUI
 * Thay thế System.out.println() để GUI có thể nhận log realtime
 */
public class ProgressTracker {

    // ============================================
    // LOG LEVELS
    // ============================================
    public enum LogLevel {
        INFO,     // Trắng - thông tin thường
        SUCCESS,  // Xanh lá - thành công
        WARNING,  // Vàng - cảnh báo
        ERROR,    // Đỏ - lỗi
        DETAIL,   // Xám - chi tiết kỹ thuật
        HEADER    // Cyan - header/section
    }

    // ============================================
    // LISTENER INTERFACE
    // ============================================
    public interface ProgressListener {
        void onLog(String message, LogLevel level);
        void onUserStart(String email, int current, int total);
        void onFolderStart(String folderPath, int current, int total);
        void onFileProcessed(String fileName);
        void onProgressUpdate(int userCurrent, int userTotal, int folderCurrent, int folderTotal);
        void onComplete();
    }

    // ============================================
    // SINGLETON
    // ============================================
    private static final ProgressTracker INSTANCE = new ProgressTracker();

    public static ProgressTracker getInstance() {
        return INSTANCE;
    }

    private ProgressTracker() {}

    // ============================================
    // STATE
    // ============================================
    private final List<ProgressListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean stopped = false;
    private volatile int userCurrent = 0;
    private volatile int userTotal = 0;
    private volatile int folderCurrent = 0;
    private volatile int folderTotal = 0;
    private volatile String currentUser = "";
    private volatile String currentFolder = "";
    private volatile String currentFile = "";

    // ============================================
    // LISTENER MANAGEMENT
    // ============================================
    public void addListener(ProgressListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ProgressListener listener) {
        listeners.remove(listener);
    }

    public void clearListeners() {
        listeners.clear();
    }

    // ============================================
    // CONTROL
    // ============================================
    public void reset() {
        stopped = false;
        userCurrent = 0;
        userTotal = 0;
        folderCurrent = 0;
        folderTotal = 0;
        currentUser = "";
        currentFolder = "";
        currentFile = "";
    }

    public void requestStop() {
        stopped = true;
        log("⛔ Đã gửi yêu cầu dừng, đang chờ hoàn thành tác vụ hiện tại...", LogLevel.WARNING);
    }

    public boolean isStopped() {
        return stopped;
    }

    // ============================================
    // EMIT METHODS - Gọi từ backend services
    // ============================================
    public void log(String message, LogLevel level) {
        // Vẫn in ra console (backward compatible)
        if (level == LogLevel.ERROR) {
            System.err.println(message);
        } else {
            System.out.println(message);
        }
        // Emit tới GUI listeners
        for (ProgressListener l : listeners) {
            try {
                l.onLog(message, level);
            } catch (Exception ignored) {}
        }
    }

    /** Shortcut cho log INFO */
    public void log(String message) {
        log(message, LogLevel.INFO);
    }

    public void onUserStart(String email, int current, int total) {
        this.currentUser = email;
        this.userCurrent = current;
        this.userTotal = total;
        this.folderCurrent = 0;
        this.folderTotal = 0;
        for (ProgressListener l : listeners) {
            try {
                l.onUserStart(email, current, total);
                l.onProgressUpdate(current, total, 0, 0);
            } catch (Exception ignored) {}
        }
    }

    public void onFolderStart(String folderPath, int current, int total) {
        this.currentFolder = folderPath;
        this.folderCurrent = current;
        this.folderTotal = total;
        for (ProgressListener l : listeners) {
            try {
                l.onFolderStart(folderPath, current, total);
                l.onProgressUpdate(userCurrent, userTotal, current, total);
            } catch (Exception ignored) {}
        }
    }

    public void onFileProcessed(String fileName) {
        this.currentFile = fileName;
        for (ProgressListener l : listeners) {
            try {
                l.onFileProcessed(fileName);
            } catch (Exception ignored) {}
        }
    }

    public void onComplete() {
        for (ProgressListener l : listeners) {
            try {
                l.onComplete();
            } catch (Exception ignored) {}
        }
    }

    // ============================================
    // GETTERS
    // ============================================
    public int getUserCurrent() { return userCurrent; }
    public int getUserTotal() { return userTotal; }
    public int getFolderCurrent() { return folderCurrent; }
    public int getFolderTotal() { return folderTotal; }
    public String getCurrentUser() { return currentUser; }
    public String getCurrentFolder() { return currentFolder; }
    public String getCurrentFile() { return currentFile; }
}
