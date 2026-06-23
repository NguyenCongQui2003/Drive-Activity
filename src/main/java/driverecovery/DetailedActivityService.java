package driverecovery;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.driveactivity.v2.DriveActivity;
import com.google.api.services.driveactivity.v2.model.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * ⭐ DETAILED ACTIVITY SERVICE - ENHANCED EXCEL EXPORT
 * Export chi tiết y hệt Apps Script
 */
public class DetailedActivityService {

    private final Drive driveService;
    private final DriveActivity activityService;
    private final Map<String, String> folderNameCache = new HashMap<>();

    public DetailedActivityService(Drive driveService, DriveActivity activityService) {
        this.driveService = driveService;
        this.activityService = activityService;
    }

    public String analyzeFolder(String folderId, String userEmail) throws IOException {
        System.out.println("📁 Đang lấy thông tin folder...");
        FolderInfo folderInfo = getFolderInfo(folderId);
        System.out.println("✓ Folder: " + folderInfo.name);
        System.out.println("✓ Path: " + folderInfo.path);

        System.out.println("\n📋 Đang đọc Activity history...");
        List<com.google.api.services.driveactivity.v2.model.DriveActivity> activities =
                getActivityForFolder(folderId);
        System.out.println("✓ Tìm thấy " + activities.size() + " activities");

        System.out.println("\n🔍 Đang phân tích chi tiết...");
        DetailedLog detailedLog = analyzeDetailedActivity(activities, folderId);

        System.out.println("\n✓ Phân tích hoàn tất:");
        System.out.println("  - Tổng files/folders: " + detailedLog.fileMap.size());
        System.out.println("  - Tổng events: " + detailedLog.activityLog.size());

        Map<String, Integer> actionStats = new HashMap<>();
        for (ActivityEvent event : detailedLog.activityLog) {
            actionStats.put(event.action, actionStats.getOrDefault(event.action, 0) + 1);
        }

        System.out.println("\n📈 Thống kê theo loại action:");
        actionStats.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println("  - " + e.getKey() + ": " + e.getValue()));

        System.out.println("\n📊 Đang tạo Excel report...");
        String reportPath = createDetailedReport(detailedLog, folderInfo);
        System.out.println("✅ Report đã tạo: " + reportPath);

        return reportPath;
    }

    private FolderInfo getFolderInfo(String folderId) throws IOException {
        File folder = driveService.files().get(folderId)
                .setFields("id, name, parents")
                .execute();

        FolderInfo info = new FolderInfo();
        info.id = folder.getId();
        info.name = folder.getName();

        String path = folder.getName();
        List<String> parents = folder.getParents();

        while (parents != null && !parents.isEmpty() && !parents.get(0).equals("root")) {
            File parent = driveService.files().get(parents.get(0))
                    .setFields("name, parents")
                    .execute();
            path = parent.getName() + "/" + path;
            parents = parent.getParents();
        }

        info.path = "/" + path;
        return info;
    }

    private List<com.google.api.services.driveactivity.v2.model.DriveActivity> getActivityForFolder(String folderId) throws IOException {
        List<com.google.api.services.driveactivity.v2.model.DriveActivity> activities = new ArrayList<>();
        String pageToken = null;

        do {
            QueryDriveActivityRequest request = new QueryDriveActivityRequest();
            request.setAncestorName("items/" + folderId);
            request.setPageSize(100);

            // 🆕 THÊM FILTER
            String filter = buildActivityFilter();
            if (filter != null && !filter.isEmpty()) {
                request.setFilter(filter);
            }

            if (pageToken != null) {
                request.setPageToken(pageToken);
            }

            QueryDriveActivityResponse response = activityService.activity()
                    .query(request)
                    .execute();

            if (response.getActivities() != null) {
                activities.addAll(response.getActivities());
            }

            pageToken = response.getNextPageToken();
        } while (pageToken != null);

        return activities;
    }

    /**
     * 🆕 Build filter string cho Activity API
     */
    private String buildActivityFilter() {
        List<String> filterParts = new ArrayList<>();

        // 1. Filter START time
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

        // 2. ✨ Filter END time
        if (Config.getActivityEndDate() != null && !Config.getActivityEndDate().isEmpty()) {
            try {
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
                dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date endDate = dateFormat.parse(Config.getActivityEndDate());

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
            } catch (Exception e) {
                System.err.println("⚠️  Lỗi parse ACTIVITY_END_DATE: " + e.getMessage());
            }
        }

        if (filterParts.isEmpty()) {
            return null;
        }

        return String.join(" AND ", filterParts);
    }

    private DetailedLog analyzeDetailedActivity(
            List<com.google.api.services.driveactivity.v2.model.DriveActivity> activities,
            String folderId) {

        Map<String, FileRecord> fileMap = new HashMap<>();
        List<ActivityEvent> activityLog = new ArrayList<>();

        activities.sort((a, b) -> {
            String timeA = a.getTimestamp() != null ? a.getTimestamp() : "";
            String timeB = b.getTimestamp() != null ? b.getTimestamp() : "";
            return timeA.compareTo(timeB);
        });

        int processed = 0;
        for (com.google.api.services.driveactivity.v2.model.DriveActivity activity : activities) {
            processed++;
            if (processed % 100 == 0) {
                System.out.println("  ... đã xử lý " + processed + "/" + activities.size() + " activities");
            }

            Date timestamp = activity.getTimestamp() != null ?
                    parseTimestamp(activity.getTimestamp()) : new Date(0);

            List<String> actors = extractActors(activity);

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

            if (activity.getTargets() != null) {
                for (Target target : activity.getTargets()) {
                    if (target.getDriveItem() == null) continue;

                    String fileId = extractFileId(target.getDriveItem().getName());
                    String fileName = target.getDriveItem().getTitle();
                    boolean isFolder = target.getDriveItem().getDriveFolder() != null;

                    if (fileId == null) continue;

                    if (!fileMap.containsKey(fileId)) {
                        FileRecord record = new FileRecord();
                        record.id = fileId;
                        record.name = fileName;
                        record.isFolder = isFolder;
                        record.events = new ArrayList<>();
                        fileMap.put(fileId, record);
                    }

                    FileRecord fileRecord = fileMap.get(fileId);
                    fileRecord.name = fileName;

                    for (ActionDetail detail : allActions) {
                        List<ActivityEvent> events = parseActionDetail(
                                detail, fileId, fileName, isFolder, actors, timestamp, folderId);
                        fileRecord.events.addAll(events);
                        activityLog.addAll(events);
                    }
                }
            }
        }

        System.out.println("\n📡 Đang kiểm tra trạng thái hiện tại của " + fileMap.size() + " files...");
        int checked = 0;
        for (FileRecord record : fileMap.values()) {
            record.currentStatus = getCurrentFileStatus(record.id);
            checked++;
            if (checked % 50 == 0) {
                System.out.println("  ... đã check " + checked + "/" + fileMap.size() + " files");
            }
        }

        activityLog.sort(Comparator.comparing(e -> e.timestamp));

        DetailedLog log = new DetailedLog();
        log.fileMap = fileMap;
        log.activityLog = activityLog;
        return log;
    }

    private List<ActivityEvent> parseActionDetail(ActionDetail detail, String fileId, String fileName,
                                                  boolean isFolder, List<String> actors, Date timestamp,
                                                  String targetFolderId) {
        List<ActivityEvent> events = new ArrayList<>();
        String actorText = String.join(", ", actors);

        if (detail.getCreate() != null) {
            ActivityEvent event = new ActivityEvent();
            event.timestamp = timestamp;
            event.fileId = fileId;
            event.fileName = fileName;
            event.isFolder = isFolder;
            event.action = "CREATE";
            event.actor = actorText;
            event.details = "Tạo mới";
            event.fromLocation = "-";
            event.toLocation = "Folder này";
            events.add(event);
        }

        if (detail.getEdit() != null) {
            ActivityEvent event = new ActivityEvent();
            event.timestamp = timestamp;
            event.fileId = fileId;
            event.fileName = fileName;
            event.isFolder = isFolder;
            event.action = "EDIT";
            event.actor = actorText;
            event.details = "Chỉnh sửa nội dung";
            event.fromLocation = "-";
            event.toLocation = "-";
            events.add(event);
        }

        if (detail.getRename() != null) {
            String oldName = detail.getRename().getOldTitle() != null ?
                    detail.getRename().getOldTitle() : "Unknown";
            String newName = detail.getRename().getNewTitle() != null ?
                    detail.getRename().getNewTitle() : fileName;

            ActivityEvent event = new ActivityEvent();
            event.timestamp = timestamp;
            event.fileId = fileId;
            event.fileName = newName;
            event.isFolder = isFolder;
            event.action = "RENAME";
            event.actor = actorText;
            event.details = "Đổi tên từ \"" + oldName + "\" → \"" + newName + "\"";
            event.fromLocation = "-";
            event.toLocation = "-";
            events.add(event);
        }

        if (detail.getDelete() != null) {
            String deleteType = detail.getDelete().getType() != null ?
                    detail.getDelete().getType() : "UNKNOWN";

            ActivityEvent event = new ActivityEvent();
            event.timestamp = timestamp;
            event.fileId = fileId;
            event.fileName = fileName;
            event.isFolder = isFolder;
            event.action = "DELETE";
            event.actor = actorText;

            if ("TRASH".equals(deleteType)) {
                event.details = "🗑️ Bỏ vào thùng rác";
                event.toLocation = "Trash";
            } else if ("PERMANENT_DELETE".equals(deleteType)) {
                event.details = "❌ Xóa vĩnh viễn";
                event.toLocation = "Permanently Deleted";
            } else {
                event.details = "🗑️ Xóa";
                event.toLocation = "Deleted";
            }

            event.fromLocation = "Folder này";
            events.add(event);
        }

        if (detail.getRestore() != null) {
            ActivityEvent event = new ActivityEvent();
            event.timestamp = timestamp;
            event.fileId = fileId;
            event.fileName = fileName;
            event.isFolder = isFolder;
            event.action = "RESTORE";
            event.actor = actorText;
            event.details = "Khôi phục từ thùng rác";
            event.fromLocation = "Trash";
            event.toLocation = "Folder này";
            events.add(event);
        }

        if (detail.getMove() != null) {
            Move move = detail.getMove();
            List<TargetReference> addedParents = move.getAddedParents();
            List<TargetReference> removedParents = move.getRemovedParents();

            boolean isAutoDelete = (removedParents != null && !removedParents.isEmpty()) &&
                    (addedParents == null || addedParents.isEmpty());

            String fromLoc = "";
            String toLoc = "";

            if (removedParents != null) {
                for (TargetReference parent : removedParents) {
                    String parentId = extractFileId(parent.getDriveItem().getName());
                    if (targetFolderId.equals(parentId)) {
                        fromLoc = "Folder này";
                    } else if (parentId != null) {
                        fromLoc = getFolderNameCached(parentId);
                    }
                }
            }

            if (addedParents != null) {
                for (TargetReference parent : addedParents) {
                    String parentId = extractFileId(parent.getDriveItem().getName());
                    if (targetFolderId.equals(parentId)) {
                        toLoc = "Folder này";
                    } else if (parentId != null) {
                        toLoc = getFolderNameCached(parentId);
                    }
                }
            }

            ActivityEvent event = new ActivityEvent();
            event.timestamp = timestamp;
            event.fileId = fileId;
            event.fileName = fileName;
            event.isFolder = isFolder;
            event.actor = actorText;
            event.fromLocation = fromLoc.isEmpty() ? "-" : fromLoc;
            event.toLocation = toLoc.isEmpty() ? "-" : toLoc;

            if (isAutoDelete) {
                event.action = "AUTO_DELETE";
                event.details = "🗑️ Tự động bị xóa" + (fromLoc.isEmpty() ? "" : " khỏi \"" + fromLoc + "\"");
                event.toLocation = "Deleted/Removed";
            } else {
                event.action = "MOVE";
                event.details = "Di chuyển " + (fromLoc.isEmpty() ? "" : "từ \"" + fromLoc + "\"") +
                        (toLoc.isEmpty() ? "" : " đến \"" + toLoc + "\"");
            }

            events.add(event);
        }

        if (detail.getPermissionChange() != null) {
            ActivityEvent event = new ActivityEvent();
            event.timestamp = timestamp;
            event.fileId = fileId;
            event.fileName = fileName;
            event.isFolder = isFolder;
            event.action = "PERMISSION";
            event.actor = actorText;
            event.details = "Thay đổi quyền";
            event.fromLocation = "-";
            event.toLocation = "-";
            events.add(event);
        }

        return events;
    }

    private List<String> extractActors(com.google.api.services.driveactivity.v2.model.DriveActivity activity) {
        List<String> actors = new ArrayList<>();

        if (activity.getActors() != null) {
            for (Actor actor : activity.getActors()) {
                if (actor.getUser() != null) {
                    if (actor.getUser().getKnownUser() != null) {
                        String personName = actor.getUser().getKnownUser().getPersonName();
                        actors.add(personName != null ? personName : "Unknown User");
                    } else if (actor.getUser().getDeletedUser() != null) {
                        actors.add("Deleted User");
                    } else if (actor.getUser().getUnknownUser() != null) {
                        actors.add("Unknown User");
                    }
                } else if (actor.getAdministrator() != null) {
                    actors.add("Administrator");
                } else if (actor.getSystem() != null) {
                    actors.add("System");
                } else if (actor.getAnonymous() != null) {
                    actors.add("Anonymous");
                } else {
                    actors.add("Unknown Actor");
                }
            }
        }

        return actors.isEmpty() ? Arrays.asList("Unknown") : actors;
    }

    private CurrentStatus getCurrentFileStatus(String fileId) {
        try {
            File file = driveService.files().get(fileId)
                    .setFields("id, name, trashed, explicitlyTrashed, parents, owners")
                    .setSupportsAllDrives(true)
                    .execute();

            Boolean trashed = file.getTrashed();
            Boolean explicitlyTrashed = file.getExplicitlyTrashed();

            if ((trashed != null && trashed) || (explicitlyTrashed != null && explicitlyTrashed)) {
                return new CurrentStatus("TRASHED", "🗑️ IN TRASH", "Trash", true);
            }

            String location = "Unknown";
            if (file.getOwners() != null && !file.getOwners().isEmpty()) {
                location = file.getOwners().get(0).getEmailAddress();
            }

            if (file.getParents() != null && !file.getParents().isEmpty()) {
                try {
                    String parentId = file.getParents().get(0);
                    String parentName = getFolderNameCached(parentId);
                    location = parentName + " (" + location + ")";
                } catch (Exception ignored) {
                }
            }

            return new CurrentStatus("EXISTS", "✅ EXISTS", location, false);

        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            int statusCode = e.getStatusCode();
            if (statusCode == 404) {
                return new CurrentStatus("DELETED", "❌ PERMANENTLY DELETED", "N/A", false);
            } else if (statusCode == 403) {
                return new CurrentStatus("NO_ACCESS", "🔒 NO ACCESS", "N/A", false);
            } else {
                return new CurrentStatus("ERROR", "⚠️ ERROR " + statusCode, "N/A", false);
            }
        } catch (Exception e) {
            return new CurrentStatus("ERROR", "⚠️ ERROR", "N/A", false);
        }
    }

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

    private String extractFileId(String name) {
        if (name == null) return null;
        String[] parts = name.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : null;
    }

    private Date parseTimestamp(String timestamp) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").parse(timestamp);
        } catch (Exception e) {
            return new Date(0);
        }
    }

    /**
     * ⭐ ENHANCED: Tạo Excel report CHI TIẾT giống Apps Script
     */
    private String createDetailedReport(DetailedLog detailedLog, FolderInfo folderInfo) throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String fileName = "detailed-activity-" + folderInfo.name.replaceAll("[^a-zA-Z0-9]", "_") +
                "-" + timestamp + ".xlsx";

        // Fix #4: Dùng getOutputDirectory() thay vì static constant OUTPUT_DIRECTORY
        java.io.File outputDir = new java.io.File(Config.getOutputDirectory());
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        String fullPath = Config.getOutputDirectory() + fileName;
        Workbook workbook = new XSSFWorkbook();

        // ⭐ 5 SHEETS CHI TIẾT
        createEnhancedSummarySheet(workbook, detailedLog, folderInfo);
        createEnhancedFilesStatusSheet(workbook, detailedLog);
        createEnhancedTimelineSheet(workbook, detailedLog);
        createEnhancedFileDetailsSheet(workbook, detailedLog);
        createEnhancedDeletedFilesSheet(workbook, detailedLog);

        try (FileOutputStream out = new FileOutputStream(fullPath)) {
            workbook.write(out);
        }
        workbook.close();

        return new java.io.File(fullPath).getAbsolutePath();
    }

    /**
     * ⭐ SHEET 1: Summary với formatting đẹp
     */
    private void createEnhancedSummarySheet(Workbook wb, DetailedLog log, FolderInfo info) {
        Sheet sheet = wb.createSheet("Summary");

        // Title style
        CellStyle titleStyle = wb.createCellStyle();
        Font titleFont = wb.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleStyle.setFont(titleFont);

        // Bold style
        CellStyle boldStyle = wb.createCellStyle();
        Font boldFont = wb.createFont();
        boldFont.setBold(true);
        boldStyle.setFont(boldFont);

        // Color styles
        CellStyle greenStyle = wb.createCellStyle();
        greenStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        greenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle yellowStyle = wb.createCellStyle();
        yellowStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        yellowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle redStyle = wb.createCellStyle();
        redStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        redStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Title
        Row row0 = sheet.createRow(0);
        Cell titleCell = row0.createCell(0);
        titleCell.setCellValue("📊 ACTIVITY LOG - ENHANCED REPORT");
        titleCell.setCellStyle(titleStyle);

        // Folder info
        sheet.createRow(2).createCell(0).setCellValue("Folder Name:");
        sheet.getRow(2).getCell(0).setCellStyle(boldStyle);
        sheet.getRow(2).createCell(1).setCellValue(info.name);

        sheet.createRow(3).createCell(0).setCellValue("Folder Path:");
        sheet.getRow(3).getCell(0).setCellStyle(boldStyle);
        sheet.getRow(3).createCell(1).setCellValue(info.path);

        sheet.createRow(4).createCell(0).setCellValue("Folder ID:");
        sheet.getRow(4).getCell(0).setCellStyle(boldStyle);
        sheet.getRow(4).createCell(1).setCellValue(info.id);

        sheet.createRow(5).createCell(0).setCellValue("Total Files/Folders:");
        sheet.getRow(5).getCell(0).setCellStyle(boldStyle);
        sheet.getRow(5).createCell(1).setCellValue(log.fileMap.size());

        sheet.createRow(6).createCell(0).setCellValue("Total Events:");
        sheet.getRow(6).getCell(0).setCellStyle(boldStyle);
        sheet.getRow(6).createCell(1).setCellValue(log.activityLog.size());

        sheet.createRow(7).createCell(0).setCellValue("Report Generated:");
        sheet.getRow(7).getCell(0).setCellStyle(boldStyle);
        sheet.getRow(7).createCell(1).setCellValue(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        // Current Status Statistics
        Map<String, Integer> statusStats = new HashMap<>();
        statusStats.put("EXISTS", 0);
        statusStats.put("TRASHED", 0);
        statusStats.put("DELETED", 0);
        statusStats.put("NO_ACCESS", 0);
        statusStats.put("ERROR", 0);

        for (FileRecord record : log.fileMap.values()) {
            if (record.currentStatus != null) {
                String code = record.currentStatus.statusCode;
                statusStats.put(code, statusStats.getOrDefault(code, 0) + 1);
            }
        }

        Row row9 = sheet.createRow(9);
        Cell statTitle = row9.createCell(0);
        statTitle.setCellValue("📈 Current Status Statistics:");
        statTitle.setCellStyle(boldStyle);

        sheet.createRow(10).createCell(0).setCellValue("✅ Still Exists");
        Cell existsCell = sheet.getRow(10).createCell(1);
        existsCell.setCellValue(statusStats.get("EXISTS"));
        existsCell.setCellStyle(greenStyle);

        sheet.createRow(11).createCell(0).setCellValue("🗑️ In Trash");
        Cell trashedCell = sheet.getRow(11).createCell(1);
        trashedCell.setCellValue(statusStats.get("TRASHED"));
        trashedCell.setCellStyle(yellowStyle);

        sheet.createRow(12).createCell(0).setCellValue("❌ Permanently Deleted");
        Cell deletedCell = sheet.getRow(12).createCell(1);
        deletedCell.setCellValue(statusStats.get("DELETED"));
        deletedCell.setCellStyle(redStyle);

        sheet.createRow(13).createCell(0).setCellValue("🔒 No Access");
        sheet.getRow(13).createCell(1).setCellValue(statusStats.get("NO_ACCESS"));

        sheet.createRow(14).createCell(0).setCellValue("⚠️ Error");
        sheet.getRow(14).createCell(1).setCellValue(statusStats.get("ERROR"));

        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
        sheet.setColumnWidth(0, 5000);
        sheet.setColumnWidth(1, 10000);
    }

    /**
     * ⭐ SHEET 2: Files + Current Status CHI TIẾT
     */
    private void createEnhancedFilesStatusSheet(Workbook wb, DetailedLog log) {
        Sheet sheet = wb.createSheet("Files + Current Status");

        // Header style
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setWrapText(true);

        // Color styles
        CellStyle greenBg = wb.createCellStyle();
        greenBg.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        greenBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font greenFont = wb.createFont();
        greenFont.setBold(true);
        greenBg.setFont(greenFont);

        CellStyle yellowBg = wb.createCellStyle();
        yellowBg.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        yellowBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font yellowFont = wb.createFont();
        yellowFont.setBold(true);
        yellowBg.setFont(yellowFont);

        CellStyle redBg = wb.createCellStyle();
        redBg.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        redBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font redFont = wb.createFont();
        redFont.setBold(true);
        redBg.setFont(redFont);

        // Header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"Type", "File/Folder Name", "File ID", "🔍 CURRENT STATUS",
                "📍 Current Location", "🗑️ Trashed?", "📝 Total Events",
                "🔧 Last Action", "👤 Last Actor"};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Data rows
        int rowNum = 1;
        for (FileRecord record : log.fileMap.values()) {
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue(record.isFolder ? "📁 Folder" : "📄 File");
            row.createCell(1).setCellValue(record.name);
            row.createCell(2).setCellValue(record.id);

            if (record.currentStatus != null) {
                Cell statusCell = row.createCell(3);
                statusCell.setCellValue(record.currentStatus.status);

                // Color code based on status
                if ("EXISTS".equals(record.currentStatus.statusCode)) {
                    statusCell.setCellStyle(greenBg);
                } else if ("TRASHED".equals(record.currentStatus.statusCode)) {
                    statusCell.setCellStyle(yellowBg);
                } else if ("DELETED".equals(record.currentStatus.statusCode) ||
                        "NO_ACCESS".equals(record.currentStatus.statusCode)) {
                    statusCell.setCellStyle(redBg);
                }

                row.createCell(4).setCellValue(record.currentStatus.location);
                row.createCell(5).setCellValue(record.currentStatus.trashed ? "✓ YES" : "✗ NO");
            } else {
                row.createCell(3).setCellValue("N/A");
                row.createCell(4).setCellValue("N/A");
                row.createCell(5).setCellValue("N/A");
            }

            row.createCell(6).setCellValue(record.events.size());

            // Last action
            if (!record.events.isEmpty()) {
                ActivityEvent lastEvent = record.events.get(record.events.size() - 1);
                row.createCell(7).setCellValue(lastEvent.action);
                row.createCell(8).setCellValue(lastEvent.actor);
            } else {
                row.createCell(7).setCellValue("N/A");
                row.createCell(8).setCellValue("N/A");
            }
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(1, 8000);  // File Name
        sheet.setColumnWidth(2, 6000);  // File ID
        sheet.setColumnWidth(3, 6000);  // Status
        sheet.setColumnWidth(4, 8000);  // Location
        sheet.setColumnWidth(8, 6000);  // Actor
        sheet.createFreezePane(0, 1);  // Freeze 1 row đầu tiên
    }

    /**
     * ⭐ SHEET 3: Complete Timeline CHI TIẾT
     */
    private void createEnhancedTimelineSheet(Workbook wb, DetailedLog log) {
        Sheet sheet = wb.createSheet("Complete Timeline");

        // Header style
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setWrapText(true);

        // Action color styles
        CellStyle createStyle = wb.createCellStyle();
        createStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        createStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle deleteStyle = wb.createCellStyle();
        deleteStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        deleteStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle moveStyle = wb.createCellStyle();
        moveStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        moveStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle editStyle = wb.createCellStyle();
        editStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        editStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle renameStyle = wb.createCellStyle();
        renameStyle.setFillForegroundColor(IndexedColors.LAVENDER.getIndex());
        renameStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle restoreStyle = wb.createCellStyle();
        restoreStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        restoreStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"⏰ Timestamp", "Type", "File/Folder", "File ID",
                "🔧 Action", "📝 Details", "👤 Actor", "📍 From → To"};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Data rows
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        int rowNum = 1;

        for (ActivityEvent event : log.activityLog) {
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue(sdf.format(event.timestamp));
            row.createCell(1).setCellValue(event.isFolder ? "📁 Folder" : "📄 File");
            row.createCell(2).setCellValue(event.fileName);
            row.createCell(3).setCellValue(event.fileId);

            Cell actionCell = row.createCell(4);
            actionCell.setCellValue(event.action);

            // Color code based on action
            if ("CREATE".equals(event.action)) {
                actionCell.setCellStyle(createStyle);
            } else if ("DELETE".equals(event.action) || "AUTO_DELETE".equals(event.action)) {
                actionCell.setCellStyle(deleteStyle);
            } else if ("MOVE".equals(event.action)) {
                actionCell.setCellStyle(moveStyle);
            } else if ("EDIT".equals(event.action)) {
                actionCell.setCellStyle(editStyle);
            } else if ("RENAME".equals(event.action)) {
                actionCell.setCellStyle(renameStyle);
            } else if ("RESTORE".equals(event.action)) {
                actionCell.setCellStyle(restoreStyle);
            }

            row.createCell(5).setCellValue(event.details);
            row.createCell(6).setCellValue(event.actor);

            String location = "-";
            if (!"-".equals(event.fromLocation) || !"-".equals(event.toLocation)) {
                location = event.fromLocation + " → " + event.toLocation;
            }
            row.createCell(7).setCellValue(location);
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(0, 4500);  // Timestamp
        sheet.setColumnWidth(2, 8000);  // File Name
        sheet.setColumnWidth(3, 6000);  // File ID
        sheet.setColumnWidth(5, 10000); // Details
        sheet.setColumnWidth(6, 6000);  // Actor
        sheet.setColumnWidth(7, 8000);  // From → To
        sheet.createFreezePane(0, 1);  // Freeze 1 row đầu tiên
    }

    /**
     * ⭐ SHEET 4: File Details CHI TIẾT (per file history)
     */
    private void createEnhancedFileDetailsSheet(Workbook wb, DetailedLog log) {
        Sheet sheet = wb.createSheet("File Details");

        // Header style
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setWrapText(true);

        // File header style
        CellStyle fileHeaderStyle = wb.createCellStyle();
        Font fileHeaderFont = wb.createFont();
        fileHeaderFont.setBold(true);
        fileHeaderFont.setFontHeightInPoints((short) 12);
        fileHeaderStyle.setFont(fileHeaderFont);
        fileHeaderStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        fileHeaderStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Column headers
        Row headerRow = sheet.createRow(0);
        String[] headers = {"File/Folder", "Event #", "⏰ Timestamp", "🔧 Action",
                "📝 Details", "👤 Actor", "📍 From → To", "🔍 Current Status"};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Data rows
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        int rowNum = 1;

        for (FileRecord record : log.fileMap.values()) {
            // File header row
            Row fileRow = sheet.createRow(rowNum++);
            Cell fileCell = fileRow.createCell(0);
            fileCell.setCellValue((record.isFolder ? "📁 " : "📄 ") + record.name);
            fileCell.setCellStyle(fileHeaderStyle);

            // Current status in header row
            if (record.currentStatus != null) {
                Cell statusCell = fileRow.createCell(7);
                statusCell.setCellValue(record.currentStatus.status);
                statusCell.setCellStyle(fileHeaderStyle);
            }

            // Events for this file
            for (int i = 0; i < record.events.size(); i++) {
                ActivityEvent event = record.events.get(i);
                Row row = sheet.createRow(rowNum++);

                row.createCell(0).setCellValue("");  // Indent
                row.createCell(1).setCellValue(i + 1);
                row.createCell(2).setCellValue(sdf.format(event.timestamp));
                row.createCell(3).setCellValue(event.action);
                row.createCell(4).setCellValue(event.details);
                row.createCell(5).setCellValue(event.actor);

                String location = "-";
                if (!"-".equals(event.fromLocation) || !"-".equals(event.toLocation)) {
                    location = event.fromLocation + " → " + event.toLocation;
                }
                row.createCell(6).setCellValue(location);
            }

            // Blank row between files
            rowNum++;
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(0, 8000);  // File Name
        sheet.setColumnWidth(1, 2000);  // Event #
        sheet.setColumnWidth(2, 4500);  // Timestamp
        sheet.setColumnWidth(4, 10000); // Details
        sheet.setColumnWidth(5, 6000);  // Actor
        sheet.setColumnWidth(6, 8000);  // From → To
        sheet.setColumnWidth(7, 6000);  // Current Status
        sheet.createFreezePane(0, 1);  // Freeze 1 row đầu tiên
    }

    /**
     * ⭐ SHEET 5: Deleted Files Only CHI TIẾT
     */
    private void createEnhancedDeletedFilesSheet(Workbook wb, DetailedLog log) {
        Sheet sheet = wb.createSheet("Deleted Files Only");

        // Header style (RED background)
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.RED.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setWrapText(true);

        // Color styles
        CellStyle redBg = wb.createCellStyle();
        redBg.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        redBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font redFont = wb.createFont();
        redFont.setBold(true);
        redBg.setFont(redFont);

        CellStyle yellowBg = wb.createCellStyle();
        yellowBg.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        yellowBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font yellowFont = wb.createFont();
        yellowFont.setBold(true);
        yellowBg.setFont(yellowFont);

        CellStyle pinkBg = wb.createCellStyle();
        pinkBg.setFillForegroundColor(IndexedColors.LAVENDER.getIndex());
        pinkBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"Type", "File/Folder Name", "File ID", "❌ Status",
                "📍 Current Location", "🔧 Last Action", "👤 Who Deleted"};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Data rows
        int rowNum = 1;
        boolean hasDeletedFiles = false;

        for (FileRecord record : log.fileMap.values()) {
            if (record.currentStatus != null &&
                    ("DELETED".equals(record.currentStatus.statusCode) ||
                            "TRASHED".equals(record.currentStatus.statusCode) ||
                            "NO_ACCESS".equals(record.currentStatus.statusCode))) {

                hasDeletedFiles = true;
                Row row = sheet.createRow(rowNum++);

                row.createCell(0).setCellValue(record.isFolder ? "📁 Folder" : "📄 File");
                row.createCell(1).setCellValue(record.name);
                row.createCell(2).setCellValue(record.id);

                Cell statusCell = row.createCell(3);
                statusCell.setCellValue(record.currentStatus.status);

                // Color code based on status
                if ("DELETED".equals(record.currentStatus.statusCode)) {
                    statusCell.setCellStyle(redBg);
                } else if ("TRASHED".equals(record.currentStatus.statusCode)) {
                    statusCell.setCellStyle(yellowBg);
                } else if ("NO_ACCESS".equals(record.currentStatus.statusCode)) {
                    statusCell.setCellStyle(pinkBg);
                }

                row.createCell(4).setCellValue(record.currentStatus.location);

                // Last action and actor
                if (!record.events.isEmpty()) {
                    ActivityEvent lastEvent = record.events.get(record.events.size() - 1);
                    row.createCell(5).setCellValue(lastEvent.action);
                    row.createCell(6).setCellValue(lastEvent.actor);
                } else {
                    row.createCell(5).setCellValue("N/A");
                    row.createCell(6).setCellValue("N/A");
                }
            }
        }

        // If no deleted files
        if (!hasDeletedFiles) {
            Row row = sheet.createRow(1);
            Cell cell = row.createCell(0);
            cell.setCellValue("✅ No deleted files found!");

            CellStyle greenStyle = wb.createCellStyle();
            Font greenFont = wb.createFont();
            greenFont.setBold(true);
            greenFont.setFontHeightInPoints((short) 12);
            greenFont.setColor(IndexedColors.GREEN.getIndex());
            greenStyle.setFont(greenFont);
            cell.setCellStyle(greenStyle);
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(1, 8000);  // File Name
        sheet.setColumnWidth(2, 6000);  // File ID
        sheet.setColumnWidth(3, 6000);  // Status
        sheet.setColumnWidth(4, 8000);  // Location
        sheet.setColumnWidth(6, 6000);  // Who Deleted
        sheet.createFreezePane(0, 1);  // Freeze 1 row đầu tiên
    }

    /**
     * 🆕 Phân tích TẤT CẢ FOLDERS của 1 USER
     */
    public String analyzeAllFoldersForUser(String userEmail) throws Exception {
        System.out.println("👤 Đang phân tích user: " + userEmail);

        // Lấy tất cả folders
        System.out.println("\n📂 Đang quét tất cả folders...");
        List<FolderInfo> allFolders = getAllFoldersRecursiveForService(userEmail);
        System.out.println("✓ Tìm thấy " + allFolders.size() + " folders\n");

        List<FolderDetailedReport> allReports = new ArrayList<>();

        for (int i = 0; i < allFolders.size(); i++) {
            FolderInfo folder = allFolders.get(i);

            System.out.println("\n[" + (i + 1) + "/" + allFolders.size() + "] " + folder.path);

            try {
                System.out.println("  📋 Đang đọc Activity...");
                List<com.google.api.services.driveactivity.v2.model.DriveActivity> activities =
                        getActivityForFolder(folder.id);

                if (activities.isEmpty()) {
                    System.out.println("  ℹ️  Không có activity");
                    continue;
                }

                System.out.println("  ✓ Có " + activities.size() + " activities");
                System.out.println("  🔍 Đang phân tích...");

                DetailedLog detailedLog = analyzeDetailedActivity(activities, folder.id);

                FolderDetailedReport report = new FolderDetailedReport();
                report.folderInfo = folder;
                report.detailedLog = detailedLog;

                allReports.add(report);
                System.out.println("  ✅ Hoàn thành");

            } catch (Exception e) {
                System.err.println("  ❌ Lỗi: " + e.getMessage());
            }
        }

        System.out.println("\n📊 Đang tạo báo cáo...");
        String reportPath = createConsolidatedReport(allReports, userEmail);

        return reportPath;
    }

    /**
     * 🆕 Phân tích TẤT CẢ FOLDERS của TẤT CẢ USERS
     */
    public String analyzeAllFoldersForAllUsers(java.util.List<String> allUsers) throws Exception {
        System.out.println("👥 Đang phân tích " + allUsers.size() + " users");

        java.util.Map<String, java.util.List<FolderDetailedReport>> allUserReports = new java.util.LinkedHashMap<>();

        for (int userIndex = 0; userIndex < allUsers.size(); userIndex++) {
            String userEmail = allUsers.get(userIndex);

            System.out.println("\n╔════════════════════════════════════════");
            System.out.println("║ USER " + (userIndex + 1) + "/" + allUsers.size() + ": " + userEmail);
            System.out.println("╚════════════════════════════════════════");

            try {
                // Tạo service cho user
                Drive userDrive = createDriveServiceForUser(userEmail);
                DriveActivity userActivity = createActivityServiceForUser(userEmail);

                DetailedActivityService userService = new DetailedActivityService(
                        userDrive,
                        userActivity
                );

                // Lấy folders
                System.out.println("\n📂 Đang quét folders...");
                java.util.List<FolderInfo> folders = userService.getAllFoldersRecursiveForService(userEmail);
                System.out.println("✓ Tìm thấy " + folders.size() + " folders");

                java.util.List<FolderDetailedReport> userReports = new java.util.ArrayList<>();

                for (int i = 0; i < folders.size(); i++) {
                    FolderInfo folder = folders.get(i);
                    System.out.println("  [" + (i + 1) + "/" + folders.size() + "] " + folder.path);

                    try {
                        java.util.List<com.google.api.services.driveactivity.v2.model.DriveActivity> activities =
                                userService.getActivityForFolder(folder.id);

                        if (activities.isEmpty()) continue;

                        DetailedLog log = userService.analyzeDetailedActivity(activities, folder.id);

                        FolderDetailedReport report = new FolderDetailedReport();
                        report.folderInfo = folder;
                        report.detailedLog = log;
                        userReports.add(report);

                    } catch (Exception e) {
                        System.err.println("    ❌ Lỗi: " + e.getMessage());
                    }
                }

                allUserReports.put(userEmail, userReports);
                System.out.println("\n✅ Hoàn thành user: " + userEmail);

            } catch (Exception e) {
                System.err.println("\n❌ Lỗi " + userEmail + ": " + e.getMessage());
            }
        }

        System.out.println("\n📊 Đang tạo báo cáo tổng hợp...");
        String reportPath = createOrganizationReport(allUserReports);

        return reportPath;
    }

    /**
     * 🆕 Helper: Quét folders (dùng cho service)
     */
    private java.util.List<FolderInfo> getAllFoldersRecursiveForService(String userEmail) throws IOException {
        java.util.List<FolderInfo> result = new java.util.ArrayList<>();
        java.util.List<File> rootFolders = getFoldersInParentForService("root");

        for (File folder : rootFolders) {
            FolderInfo info = new FolderInfo();
            info.id = folder.getId();
            info.name = folder.getName();
            info.path = "/" + folder.getName();
            result.add(info);

            result.addAll(getFoldersRecursiveHelperForService(folder.getId(), info.path));
        }

        return result;
    }

    private java.util.List<FolderInfo> getFoldersRecursiveHelperForService(String parentId, String parentPath) throws IOException {
        java.util.List<FolderInfo> result = new java.util.ArrayList<>();
        java.util.List<File> childFolders = getFoldersInParentForService(parentId);

        for (File folder : childFolders) {
            FolderInfo info = new FolderInfo();
            info.id = folder.getId();
            info.name = folder.getName();
            info.path = parentPath + "/" + folder.getName();
            result.add(info);

            result.addAll(getFoldersRecursiveHelperForService(folder.getId(), info.path));
        }

        return result;
    }

    private java.util.List<File> getFoldersInParentForService(String parentId) throws IOException {
        java.util.List<File> folders = new java.util.ArrayList<>();
        String pageToken = null;

        do {
            String query = "'" + parentId + "' in parents and mimeType='application/vnd.google-apps.folder' and trashed=false";
            com.google.api.services.drive.model.FileList result = driveService.files().list()
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

    /**
     * 🆕 Helper: Tạo Drive/Activity service cho user
     */
    private Drive createDriveServiceForUser(String userEmail) throws Exception {
        com.google.auth.oauth2.GoogleCredentials credentials;

        if (Config.USE_JSON_FILE) {
            credentials = com.google.auth.oauth2.ServiceAccountCredentials
                    .fromStream(new java.io.FileInputStream(Config.SERVICE_ACCOUNT_FILE))
                    .createScoped(Config.SCOPES)
                    .createDelegated(userEmail);
        } else {
            credentials = com.google.auth.oauth2.ServiceAccountCredentials
                    .fromStream(new java.io.ByteArrayInputStream(
                            createServiceAccountJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .createScoped(Config.SCOPES)
                    .createDelegated(userEmail);
        }

        return new Drive.Builder(
                com.google.api.client.googleapis.javanet.GoogleNetHttpTransport.newTrustedTransport(),
                com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
                new com.google.auth.http.HttpCredentialsAdapter(credentials)
        )
                .setApplicationName("Drive Recovery Tool v2.0")
                .build();
    }

    private DriveActivity createActivityServiceForUser(String userEmail) throws Exception {
        com.google.auth.oauth2.GoogleCredentials credentials;

        if (Config.USE_JSON_FILE) {
            credentials = com.google.auth.oauth2.ServiceAccountCredentials
                    .fromStream(new java.io.FileInputStream(Config.SERVICE_ACCOUNT_FILE))
                    .createScoped(Config.SCOPES)
                    .createDelegated(userEmail);
        } else {
            credentials = com.google.auth.oauth2.ServiceAccountCredentials
                    .fromStream(new java.io.ByteArrayInputStream(
                            createServiceAccountJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .createScoped(Config.SCOPES)
                    .createDelegated(userEmail);
        }

        return new DriveActivity.Builder(
                com.google.api.client.googleapis.javanet.GoogleNetHttpTransport.newTrustedTransport(),
                com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
                new com.google.auth.http.HttpCredentialsAdapter(credentials)
        )
                .setApplicationName("Drive Recovery Tool v2.0")
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

    /**
     * 🆕 Tạo báo cáo tổng hợp cho 1 user
     */
    private String createConsolidatedReport(java.util.List<FolderDetailedReport> reports, String userEmail) throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String fileName = "detailed-activity-" + userEmail.split("@")[0] + "-ALL-FOLDERS-" + timestamp + ".xlsx";

        // Fix #4: Dùng getter thay vì static constant
        java.io.File outputDir = new java.io.File(Config.getOutputDirectory());
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        String fullPath = Config.getOutputDirectory() + fileName;
        Workbook workbook = new XSSFWorkbook();

        // ⭐ SHEET 1: Organization Summary (tổng quan folders)
        createConsolidatedSummarySheet(workbook, reports, userEmail);

        // ⭐ SHEET 2: ALL FILES SUMMARY (tổng hợp TẤT CẢ files từ tất cả folders) - THÊM MỚI
        createAllFilesSummarySheet(workbook, reports);

        // ⭐ SHEET 3: ALL TIMELINE (tổng hợp timeline của tất cả folders) - THÊM MỚI
        createAllTimelineSheet(workbook, reports);

        // ⭐ SHEET 4: ALL DELETED FILES (tổng hợp files đã xóa) - THÊM MỚI
        createAllDeletedFilesSheet(workbook, reports);

        // ⭐ SHEETS 5+: CHI TIẾT TỪNG FOLDER (GIỐNG MODE 2)
        int folderIndex = 0;
        for (FolderDetailedReport report : reports) {
            if (report.detailedLog.activityLog.isEmpty()) continue;

            folderIndex++;
            String folderPrefix = "F" + folderIndex + "_";

            // Tạo 5 sheets chi tiết cho folder này
            createEnhancedSummarySheetForFolder(workbook, report, folderPrefix);
            createEnhancedFilesStatusSheetForFolder(workbook, report, folderPrefix);
            createEnhancedTimelineSheetForFolder(workbook, report, folderPrefix);
            createEnhancedFileDetailsSheetForFolder(workbook, report, folderPrefix);
            createEnhancedDeletedFilesSheetForFolder(workbook, report, folderPrefix);

            // Giới hạn để tránh quá nhiều sheets
            if (folderIndex >= 10) {
                System.out.println("⚠️  Giới hạn 10 folders đầu tiên (4 + 50 sheets = 54 sheets)");
                break;
            }
        }

        try (FileOutputStream out = new FileOutputStream(fullPath)) {
            workbook.write(out);
        }
        workbook.close();

        return new java.io.File(fullPath).getAbsolutePath();
    }

    /**
     * 🆕 Tổng hợp TIMELINE của tất cả folders
     */
    private void createAllTimelineSheet(Workbook wb, java.util.List<FolderDetailedReport> reports) {
        Sheet sheet = wb.createSheet("All Timeline");

        // Header style
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setWrapText(true);

        // Action color styles
        CellStyle createStyle = wb.createCellStyle();
        createStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        createStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle deleteStyle = wb.createCellStyle();
        deleteStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        deleteStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle moveStyle = wb.createCellStyle();
        moveStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        moveStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle editStyle = wb.createCellStyle();
        editStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        editStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle renameStyle = wb.createCellStyle();
        renameStyle.setFillForegroundColor(IndexedColors.LAVENDER.getIndex());
        renameStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle restoreStyle = wb.createCellStyle();
        restoreStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        restoreStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"Folder Path", "⏰ Timestamp", "Type", "File/Folder",
                "File ID", "🔧 Action", "📝 Details", "👤 Actor", "📍 From → To"};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Collect all events from all folders and sort by timestamp
        java.util.List<ActivityEventWithFolder> allEvents = new java.util.ArrayList<>();
        for (FolderDetailedReport report : reports) {
            for (ActivityEvent event : report.detailedLog.activityLog) {
                ActivityEventWithFolder eventWithFolder = new ActivityEventWithFolder();
                eventWithFolder.event = event;
                eventWithFolder.folderPath = report.folderInfo.path;
                allEvents.add(eventWithFolder);
            }
        }

        // Sort by timestamp
        allEvents.sort(Comparator.comparing(e -> e.event.timestamp));

        // Data rows
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        int rowNum = 1;

        for (ActivityEventWithFolder item : allEvents) {
            ActivityEvent event = item.event;
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue(item.folderPath);
            row.createCell(1).setCellValue(sdf.format(event.timestamp));
            row.createCell(2).setCellValue(event.isFolder ? "📁 Folder" : "📄 File");
            row.createCell(3).setCellValue(event.fileName);
            row.createCell(4).setCellValue(event.fileId);

            Cell actionCell = row.createCell(5);
            actionCell.setCellValue(event.action);

            // Color code based on action
            if ("CREATE".equals(event.action)) {
                actionCell.setCellStyle(createStyle);
            } else if ("DELETE".equals(event.action) || "AUTO_DELETE".equals(event.action)) {
                actionCell.setCellStyle(deleteStyle);
            } else if ("MOVE".equals(event.action)) {
                actionCell.setCellStyle(moveStyle);
            } else if ("EDIT".equals(event.action)) {
                actionCell.setCellStyle(editStyle);
            } else if ("RENAME".equals(event.action)) {
                actionCell.setCellStyle(renameStyle);
            } else if ("RESTORE".equals(event.action)) {
                actionCell.setCellStyle(restoreStyle);
            }

            row.createCell(6).setCellValue(event.details);
            row.createCell(7).setCellValue(event.actor);

            String location = "-";
            if (!"-".equals(event.fromLocation) || !"-".equals(event.toLocation)) {
                location = event.fromLocation + " → " + event.toLocation;
            }
            row.createCell(8).setCellValue(location);
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(0, 8000);  // Folder Path
        sheet.setColumnWidth(1, 4500);  // Timestamp
        sheet.setColumnWidth(3, 8000);  // File Name
        sheet.setColumnWidth(4, 6000);  // File ID
        sheet.setColumnWidth(6, 10000); // Details
        sheet.setColumnWidth(7, 6000);  // Actor
        sheet.setColumnWidth(8, 8000);  // From → To
        sheet.createFreezePane(0, 1);
    }

    // Helper class
    static class ActivityEventWithFolder {
        ActivityEvent event;
        String folderPath;
    }

    /**
     * 🆕 Tổng hợp TẤT CẢ DELETED FILES từ tất cả folders
     */
    private void createAllDeletedFilesSheet(Workbook wb, java.util.List<FolderDetailedReport> reports) {
        Sheet sheet = wb.createSheet("All Deleted Files");

        // Header style (RED background)
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.RED.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setWrapText(true);

        // Color styles
        CellStyle redBg = wb.createCellStyle();
        redBg.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        redBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font redFont = wb.createFont();
        redFont.setBold(true);
        redBg.setFont(redFont);

        CellStyle yellowBg = wb.createCellStyle();
        yellowBg.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        yellowBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font yellowFont = wb.createFont();
        yellowFont.setBold(true);
        yellowBg.setFont(yellowFont);

        CellStyle pinkBg = wb.createCellStyle();
        pinkBg.setFillForegroundColor(IndexedColors.LAVENDER.getIndex());
        pinkBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"Folder Path", "Type", "File/Folder Name", "File ID",
                "❌ Status", "📍 Current Location", "🔧 Last Action", "👤 Who Deleted"};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Data rows
        int rowNum = 1;
        boolean hasDeletedFiles = false;

        for (FolderDetailedReport report : reports) {
            for (FileRecord record : report.detailedLog.fileMap.values()) {
                if (record.currentStatus != null &&
                        ("DELETED".equals(record.currentStatus.statusCode) ||
                                "TRASHED".equals(record.currentStatus.statusCode) ||
                                "NO_ACCESS".equals(record.currentStatus.statusCode))) {

                    hasDeletedFiles = true;
                    Row row = sheet.createRow(rowNum++);

                    row.createCell(0).setCellValue(report.folderInfo.path);
                    row.createCell(1).setCellValue(record.isFolder ? "📁 Folder" : "📄 File");
                    row.createCell(2).setCellValue(record.name);
                    row.createCell(3).setCellValue(record.id);

                    Cell statusCell = row.createCell(4);
                    statusCell.setCellValue(record.currentStatus.status);

                    // Color code based on status
                    if ("DELETED".equals(record.currentStatus.statusCode)) {
                        statusCell.setCellStyle(redBg);
                    } else if ("TRASHED".equals(record.currentStatus.statusCode)) {
                        statusCell.setCellStyle(yellowBg);
                    } else if ("NO_ACCESS".equals(record.currentStatus.statusCode)) {
                        statusCell.setCellStyle(pinkBg);
                    }

                    row.createCell(5).setCellValue(record.currentStatus.location);

                    // Last action and actor
                    if (!record.events.isEmpty()) {
                        ActivityEvent lastEvent = record.events.get(record.events.size() - 1);
                        row.createCell(6).setCellValue(lastEvent.action);
                        row.createCell(7).setCellValue(lastEvent.actor);
                    } else {
                        row.createCell(6).setCellValue("N/A");
                        row.createCell(7).setCellValue("N/A");
                    }
                }
            }
        }

        // If no deleted files
        if (!hasDeletedFiles) {
            Row row = sheet.createRow(1);
            Cell cell = row.createCell(0);
            cell.setCellValue("✅ No deleted files found!");

            CellStyle greenStyle = wb.createCellStyle();
            Font greenFont = wb.createFont();
            greenFont.setBold(true);
            greenFont.setFontHeightInPoints((short) 12);
            greenFont.setColor(IndexedColors.GREEN.getIndex());
            greenStyle.setFont(greenFont);
            cell.setCellStyle(greenStyle);
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(0, 8000);  // Folder Path
        sheet.setColumnWidth(2, 8000);  // File Name
        sheet.setColumnWidth(3, 6000);  // File ID
        sheet.setColumnWidth(4, 6000);  // Status
        sheet.setColumnWidth(5, 8000);  // Location
        sheet.setColumnWidth(7, 6000);  // Who Deleted
        sheet.createFreezePane(0, 1);
    }

    /**
     * 🆕 Tổng hợp TẤT CẢ FILES từ tất cả folders
     */
    private void createAllFilesSummarySheet(Workbook wb, java.util.List<FolderDetailedReport> reports) {
        Sheet sheet = wb.createSheet("All Files Summary");

        // Header style
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setWrapText(true);

        // Color styles
        CellStyle greenBg = wb.createCellStyle();
        greenBg.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        greenBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font greenFont = wb.createFont();
        greenFont.setBold(true);
        greenBg.setFont(greenFont);

        CellStyle yellowBg = wb.createCellStyle();
        yellowBg.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        yellowBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font yellowFont = wb.createFont();
        yellowFont.setBold(true);
        yellowBg.setFont(yellowFont);

        CellStyle redBg = wb.createCellStyle();
        redBg.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        redBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font redFont = wb.createFont();
        redFont.setBold(true);
        redBg.setFont(redFont);

        // Header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"Folder Path", "Type", "File/Folder Name", "File ID",
                "🔍 CURRENT STATUS", "📍 Current Location", "🗑️ Trashed?",
                "📝 Total Events", "🔧 Last Action", "👤 Last Actor"};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Data rows - loop through all folders and all files
        int rowNum = 1;
        for (FolderDetailedReport report : reports) {
            for (FileRecord record : report.detailedLog.fileMap.values()) {
                Row row = sheet.createRow(rowNum++);

                // Folder Path
                row.createCell(0).setCellValue(report.folderInfo.path);

                // File info
                row.createCell(1).setCellValue(record.isFolder ? "📁 Folder" : "📄 File");
                row.createCell(2).setCellValue(record.name);
                row.createCell(3).setCellValue(record.id);

                // Current status
                if (record.currentStatus != null) {
                    Cell statusCell = row.createCell(4);
                    statusCell.setCellValue(record.currentStatus.status);

                    if ("EXISTS".equals(record.currentStatus.statusCode)) {
                        statusCell.setCellStyle(greenBg);
                    } else if ("TRASHED".equals(record.currentStatus.statusCode)) {
                        statusCell.setCellStyle(yellowBg);
                    } else if ("DELETED".equals(record.currentStatus.statusCode) ||
                            "NO_ACCESS".equals(record.currentStatus.statusCode)) {
                        statusCell.setCellStyle(redBg);
                    }

                    row.createCell(5).setCellValue(record.currentStatus.location);
                    row.createCell(6).setCellValue(record.currentStatus.trashed ? "✓ YES" : "✗ NO");
                } else {
                    row.createCell(4).setCellValue("N/A");
                    row.createCell(5).setCellValue("N/A");
                    row.createCell(6).setCellValue("N/A");
                }

                row.createCell(7).setCellValue(record.events.size());

                // Last action
                if (!record.events.isEmpty()) {
                    ActivityEvent lastEvent = record.events.get(record.events.size() - 1);
                    row.createCell(8).setCellValue(lastEvent.action);
                    row.createCell(9).setCellValue(lastEvent.actor);
                } else {
                    row.createCell(8).setCellValue("N/A");
                    row.createCell(9).setCellValue("N/A");
                }
            }
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(0, 8000);  // Folder Path
        sheet.setColumnWidth(2, 8000);  // File Name
        sheet.setColumnWidth(3, 6000);  // File ID
        sheet.setColumnWidth(4, 6000);  // Status
        sheet.setColumnWidth(5, 8000);  // Location
        sheet.setColumnWidth(9, 6000);  // Actor
        sheet.createFreezePane(0, 1);
    }


    /**
     * 🆕 Tạo Summary sheet cho 1 folder (với prefix)
     */
    private void createEnhancedSummarySheetForFolder(Workbook wb, FolderDetailedReport report, String prefix) {
        String sheetName = prefix + "Summary";
        // Giới hạn tên sheet (Excel max 31 chars)
        if (sheetName.length() > 31) {
            sheetName = sheetName.substring(0, 31);
        }
        Sheet sheet = wb.createSheet(sheetName);

        // Title style
        CellStyle titleStyle = wb.createCellStyle();
        Font titleFont = wb.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleStyle.setFont(titleFont);

        // Bold style
        CellStyle boldStyle = wb.createCellStyle();
        Font boldFont = wb.createFont();
        boldFont.setBold(true);
        boldStyle.setFont(boldFont);

        // Color styles
        CellStyle greenStyle = wb.createCellStyle();
        greenStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        greenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle yellowStyle = wb.createCellStyle();
        yellowStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        yellowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle redStyle = wb.createCellStyle();
        redStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        redStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Title
        Row row0 = sheet.createRow(0);
        Cell titleCell = row0.createCell(0);
        titleCell.setCellValue("📊 ACTIVITY LOG - ENHANCED REPORT");
        titleCell.setCellStyle(titleStyle);

        // Folder info
        sheet.createRow(2).createCell(0).setCellValue("Folder Name:");
        sheet.getRow(2).getCell(0).setCellStyle(boldStyle);
        sheet.getRow(2).createCell(1).setCellValue(report.folderInfo.name);

        sheet.createRow(3).createCell(0).setCellValue("Folder Path:");
        sheet.getRow(3).getCell(0).setCellStyle(boldStyle);
        sheet.getRow(3).createCell(1).setCellValue(report.folderInfo.path);

        sheet.createRow(4).createCell(0).setCellValue("Folder ID:");
        sheet.getRow(4).getCell(0).setCellStyle(boldStyle);
        sheet.getRow(4).createCell(1).setCellValue(report.folderInfo.id);

        sheet.createRow(5).createCell(0).setCellValue("Total Files/Folders:");
        sheet.getRow(5).getCell(0).setCellStyle(boldStyle);
        sheet.getRow(5).createCell(1).setCellValue(report.detailedLog.fileMap.size());

        sheet.createRow(6).createCell(0).setCellValue("Total Events:");
        sheet.getRow(6).getCell(0).setCellStyle(boldStyle);
        sheet.getRow(6).createCell(1).setCellValue(report.detailedLog.activityLog.size());

        sheet.createRow(7).createCell(0).setCellValue("Report Generated:");
        sheet.getRow(7).getCell(0).setCellStyle(boldStyle);
        sheet.getRow(7).createCell(1).setCellValue(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        // Current Status Statistics
        Map<String, Integer> statusStats = new HashMap<>();
        statusStats.put("EXISTS", 0);
        statusStats.put("TRASHED", 0);
        statusStats.put("DELETED", 0);
        statusStats.put("NO_ACCESS", 0);
        statusStats.put("ERROR", 0);

        for (FileRecord record : report.detailedLog.fileMap.values()) {
            if (record.currentStatus != null) {
                String code = record.currentStatus.statusCode;
                statusStats.put(code, statusStats.getOrDefault(code, 0) + 1);
            }
        }

        Row row9 = sheet.createRow(9);
        Cell statTitle = row9.createCell(0);
        statTitle.setCellValue("📈 Current Status Statistics:");
        statTitle.setCellStyle(boldStyle);

        sheet.createRow(10).createCell(0).setCellValue("✅ Still Exists");
        Cell existsCell = sheet.getRow(10).createCell(1);
        existsCell.setCellValue(statusStats.get("EXISTS"));
        existsCell.setCellStyle(greenStyle);

        sheet.createRow(11).createCell(0).setCellValue("🗑️ In Trash");
        Cell trashedCell = sheet.getRow(11).createCell(1);
        trashedCell.setCellValue(statusStats.get("TRASHED"));
        trashedCell.setCellStyle(yellowStyle);

        sheet.createRow(12).createCell(0).setCellValue("❌ Permanently Deleted");
        Cell deletedCell = sheet.getRow(12).createCell(1);
        deletedCell.setCellValue(statusStats.get("DELETED"));
        deletedCell.setCellStyle(redStyle);

        sheet.createRow(13).createCell(0).setCellValue("🔒 No Access");
        sheet.getRow(13).createCell(1).setCellValue(statusStats.get("NO_ACCESS"));

        sheet.createRow(14).createCell(0).setCellValue("⚠️ Error");
        sheet.getRow(14).createCell(1).setCellValue(statusStats.get("ERROR"));

        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
        sheet.setColumnWidth(0, 5000);
        sheet.setColumnWidth(1, 10000);
    }

    /**
     * 🆕 Tạo Files Status sheet cho 1 folder (với prefix)
     */
    private void createEnhancedFilesStatusSheetForFolder(Workbook wb, FolderDetailedReport report, String prefix) {
        String sheetName = prefix + "Files_Status";
        if (sheetName.length() > 31) {
            sheetName = sheetName.substring(0, 31);
        }
        Sheet sheet = wb.createSheet(sheetName);

        // Header style
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setWrapText(true);

        // Color styles
        CellStyle greenBg = wb.createCellStyle();
        greenBg.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        greenBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font greenFont = wb.createFont();
        greenFont.setBold(true);
        greenBg.setFont(greenFont);

        CellStyle yellowBg = wb.createCellStyle();
        yellowBg.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        yellowBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font yellowFont = wb.createFont();
        yellowFont.setBold(true);
        yellowBg.setFont(yellowFont);

        CellStyle redBg = wb.createCellStyle();
        redBg.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        redBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font redFont = wb.createFont();
        redFont.setBold(true);
        redBg.setFont(redFont);

        // Header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"Type", "File/Folder Name", "File ID", "🔍 CURRENT STATUS",
                "📍 Current Location", "🗑️ Trashed?", "📝 Total Events",
                "🔧 Last Action", "👤 Last Actor"};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Data rows
        int rowNum = 1;
        for (FileRecord record : report.detailedLog.fileMap.values()) {
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue(record.isFolder ? "📁 Folder" : "📄 File");
            row.createCell(1).setCellValue(record.name);
            row.createCell(2).setCellValue(record.id);

            if (record.currentStatus != null) {
                Cell statusCell = row.createCell(3);
                statusCell.setCellValue(record.currentStatus.status);

                // Color code based on status
                if ("EXISTS".equals(record.currentStatus.statusCode)) {
                    statusCell.setCellStyle(greenBg);
                } else if ("TRASHED".equals(record.currentStatus.statusCode)) {
                    statusCell.setCellStyle(yellowBg);
                } else if ("DELETED".equals(record.currentStatus.statusCode) ||
                        "NO_ACCESS".equals(record.currentStatus.statusCode)) {
                    statusCell.setCellStyle(redBg);
                }

                row.createCell(4).setCellValue(record.currentStatus.location);
                row.createCell(5).setCellValue(record.currentStatus.trashed ? "✓ YES" : "✗ NO");
            } else {
                row.createCell(3).setCellValue("N/A");
                row.createCell(4).setCellValue("N/A");
                row.createCell(5).setCellValue("N/A");
            }

            row.createCell(6).setCellValue(record.events.size());

            // Last action
            if (!record.events.isEmpty()) {
                ActivityEvent lastEvent = record.events.get(record.events.size() - 1);
                row.createCell(7).setCellValue(lastEvent.action);
                row.createCell(8).setCellValue(lastEvent.actor);
            } else {
                row.createCell(7).setCellValue("N/A");
                row.createCell(8).setCellValue("N/A");
            }
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(1, 8000);  // File Name
        sheet.setColumnWidth(2, 6000);  // File ID
        sheet.setColumnWidth(3, 6000);  // Status
        sheet.setColumnWidth(4, 8000);  // Location
        sheet.setColumnWidth(8, 6000);  // Actor
        sheet.createFreezePane(0, 1);
    }

    /**
     * 🆕 Tạo Timeline sheet cho 1 folder (với prefix)
     */
    private void createEnhancedTimelineSheetForFolder(Workbook wb, FolderDetailedReport report, String prefix) {
        String sheetName = prefix + "Timeline";
        if (sheetName.length() > 31) {
            sheetName = sheetName.substring(0, 31);
        }
        Sheet sheet = wb.createSheet(sheetName);

        // Header style
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setWrapText(true);

        // Action color styles
        CellStyle createStyle = wb.createCellStyle();
        createStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        createStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle deleteStyle = wb.createCellStyle();
        deleteStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        deleteStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle moveStyle = wb.createCellStyle();
        moveStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        moveStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle editStyle = wb.createCellStyle();
        editStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        editStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle renameStyle = wb.createCellStyle();
        renameStyle.setFillForegroundColor(IndexedColors.LAVENDER.getIndex());
        renameStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle restoreStyle = wb.createCellStyle();
        restoreStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        restoreStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"⏰ Timestamp", "Type", "File/Folder", "File ID",
                "🔧 Action", "📝 Details", "👤 Actor", "📍 From → To"};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Data rows
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        int rowNum = 1;

        for (ActivityEvent event : report.detailedLog.activityLog) {
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue(sdf.format(event.timestamp));
            row.createCell(1).setCellValue(event.isFolder ? "📁 Folder" : "📄 File");
            row.createCell(2).setCellValue(event.fileName);
            row.createCell(3).setCellValue(event.fileId);

            Cell actionCell = row.createCell(4);
            actionCell.setCellValue(event.action);

            // Color code based on action
            if ("CREATE".equals(event.action)) {
                actionCell.setCellStyle(createStyle);
            } else if ("DELETE".equals(event.action) || "AUTO_DELETE".equals(event.action)) {
                actionCell.setCellStyle(deleteStyle);
            } else if ("MOVE".equals(event.action)) {
                actionCell.setCellStyle(moveStyle);
            } else if ("EDIT".equals(event.action)) {
                actionCell.setCellStyle(editStyle);
            } else if ("RENAME".equals(event.action)) {
                actionCell.setCellStyle(renameStyle);
            } else if ("RESTORE".equals(event.action)) {
                actionCell.setCellStyle(restoreStyle);
            }

            row.createCell(5).setCellValue(event.details);
            row.createCell(6).setCellValue(event.actor);

            String location = "-";
            if (!"-".equals(event.fromLocation) || !"-".equals(event.toLocation)) {
                location = event.fromLocation + " → " + event.toLocation;
            }
            row.createCell(7).setCellValue(location);
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(0, 4500);  // Timestamp
        sheet.setColumnWidth(2, 8000);  // File Name
        sheet.setColumnWidth(3, 6000);  // File ID
        sheet.setColumnWidth(5, 10000); // Details
        sheet.setColumnWidth(6, 6000);  // Actor
        sheet.setColumnWidth(7, 8000);  // From → To
        sheet.createFreezePane(0, 1);
    }

    /**
     * 🆕 Tạo File Details sheet cho 1 folder (với prefix)
     */
    private void createEnhancedFileDetailsSheetForFolder(Workbook wb, FolderDetailedReport report, String prefix) {
        String sheetName = prefix + "File_Details";
        if (sheetName.length() > 31) {
            sheetName = sheetName.substring(0, 31);
        }
        Sheet sheet = wb.createSheet(sheetName);

        // Header style
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setWrapText(true);

        // File header style
        CellStyle fileHeaderStyle = wb.createCellStyle();
        Font fileHeaderFont = wb.createFont();
        fileHeaderFont.setBold(true);
        fileHeaderFont.setFontHeightInPoints((short) 12);
        fileHeaderStyle.setFont(fileHeaderFont);
        fileHeaderStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        fileHeaderStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Column headers
        Row headerRow = sheet.createRow(0);
        String[] headers = {"File/Folder", "Event #", "⏰ Timestamp", "🔧 Action",
                "📝 Details", "👤 Actor", "📍 From → To", "🔍 Current Status"};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Data rows
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        int rowNum = 1;

        for (FileRecord record : report.detailedLog.fileMap.values()) {
            // File header row
            Row fileRow = sheet.createRow(rowNum++);
            Cell fileCell = fileRow.createCell(0);
            fileCell.setCellValue((record.isFolder ? "📁 " : "📄 ") + record.name);
            fileCell.setCellStyle(fileHeaderStyle);

            // Current status in header row
            if (record.currentStatus != null) {
                Cell statusCell = fileRow.createCell(7);
                statusCell.setCellValue(record.currentStatus.status);
                statusCell.setCellStyle(fileHeaderStyle);
            }

            // Events for this file
            for (int i = 0; i < record.events.size(); i++) {
                ActivityEvent event = record.events.get(i);
                Row row = sheet.createRow(rowNum++);

                row.createCell(0).setCellValue("");  // Indent
                row.createCell(1).setCellValue(i + 1);
                row.createCell(2).setCellValue(sdf.format(event.timestamp));
                row.createCell(3).setCellValue(event.action);
                row.createCell(4).setCellValue(event.details);
                row.createCell(5).setCellValue(event.actor);

                String location = "-";
                if (!"-".equals(event.fromLocation) || !"-".equals(event.toLocation)) {
                    location = event.fromLocation + " → " + event.toLocation;
                }
                row.createCell(6).setCellValue(location);
            }

            // Blank row between files
            rowNum++;
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(0, 8000);  // File Name
        sheet.setColumnWidth(1, 2000);  // Event #
        sheet.setColumnWidth(2, 4500);  // Timestamp
        sheet.setColumnWidth(4, 10000); // Details
        sheet.setColumnWidth(5, 6000);  // Actor
        sheet.setColumnWidth(6, 8000);  // From → To
        sheet.setColumnWidth(7, 6000);  // Current Status
        sheet.createFreezePane(0, 1);
    }

    /**
     * 🆕 Tạo Deleted Files sheet cho 1 folder (với prefix)
     */
    private void createEnhancedDeletedFilesSheetForFolder(Workbook wb, FolderDetailedReport report, String prefix) {
        String sheetName = prefix + "Deleted";
        if (sheetName.length() > 31) {
            sheetName = sheetName.substring(0, 31);
        }
        Sheet sheet = wb.createSheet(sheetName);

        // Header style (RED background)
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.RED.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setWrapText(true);

        // Color styles
        CellStyle redBg = wb.createCellStyle();
        redBg.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        redBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font redFont = wb.createFont();
        redFont.setBold(true);
        redBg.setFont(redFont);

        CellStyle yellowBg = wb.createCellStyle();
        yellowBg.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        yellowBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font yellowFont = wb.createFont();
        yellowFont.setBold(true);
        yellowBg.setFont(yellowFont);

        CellStyle pinkBg = wb.createCellStyle();
        pinkBg.setFillForegroundColor(IndexedColors.LAVENDER.getIndex());
        pinkBg.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"Type", "File/Folder Name", "File ID", "❌ Status",
                "📍 Current Location", "🔧 Last Action", "👤 Who Deleted"};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Data rows
        int rowNum = 1;
        boolean hasDeletedFiles = false;

        for (FileRecord record : report.detailedLog.fileMap.values()) {
            if (record.currentStatus != null &&
                    ("DELETED".equals(record.currentStatus.statusCode) ||
                            "TRASHED".equals(record.currentStatus.statusCode) ||
                            "NO_ACCESS".equals(record.currentStatus.statusCode))) {

                hasDeletedFiles = true;
                Row row = sheet.createRow(rowNum++);

                row.createCell(0).setCellValue(record.isFolder ? "📁 Folder" : "📄 File");
                row.createCell(1).setCellValue(record.name);
                row.createCell(2).setCellValue(record.id);

                Cell statusCell = row.createCell(3);
                statusCell.setCellValue(record.currentStatus.status);

                // Color code based on status
                if ("DELETED".equals(record.currentStatus.statusCode)) {
                    statusCell.setCellStyle(redBg);
                } else if ("TRASHED".equals(record.currentStatus.statusCode)) {
                    statusCell.setCellStyle(yellowBg);
                } else if ("NO_ACCESS".equals(record.currentStatus.statusCode)) {
                    statusCell.setCellStyle(pinkBg);
                }

                row.createCell(4).setCellValue(record.currentStatus.location);

                // Last action and actor
                if (!record.events.isEmpty()) {
                    ActivityEvent lastEvent = record.events.get(record.events.size() - 1);
                    row.createCell(5).setCellValue(lastEvent.action);
                    row.createCell(6).setCellValue(lastEvent.actor);
                } else {
                    row.createCell(5).setCellValue("N/A");
                    row.createCell(6).setCellValue("N/A");
                }
            }
        }

        // If no deleted files
        if (!hasDeletedFiles) {
            Row row = sheet.createRow(1);
            Cell cell = row.createCell(0);
            cell.setCellValue("✅ No deleted files found!");

            CellStyle greenStyle = wb.createCellStyle();
            Font greenFont = wb.createFont();
            greenFont.setBold(true);
            greenFont.setFontHeightInPoints((short) 12);
            greenFont.setColor(IndexedColors.GREEN.getIndex());
            greenStyle.setFont(greenFont);
            cell.setCellStyle(greenStyle);
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.setColumnWidth(1, 8000);  // File Name
        sheet.setColumnWidth(2, 6000);  // File ID
        sheet.setColumnWidth(3, 6000);  // Status
        sheet.setColumnWidth(4, 8000);  // Location
        sheet.setColumnWidth(6, 6000);  // Who Deleted
        sheet.createFreezePane(0, 1);
    }

    /**
     * 🆕 Tạo báo cáo tổ chức
     */
    private String createOrganizationReport(java.util.Map<String, java.util.List<FolderDetailedReport>> allUserReports) throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String fileName = "detailed-activity-ORGANIZATION-" + timestamp + ".xlsx";

        // Fix #4: Dùng getter thay vì static constant
        java.io.File outputDir = new java.io.File(Config.getOutputDirectory());
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        String fullPath = Config.getOutputDirectory() + fileName;
        Workbook workbook = new XSSFWorkbook();

        // ⭐ SHEET 1: Organization Summary
        createOrganizationSummarySheet(workbook, allUserReports);

        // ⭐ SHEETS 2+: CHI TIẾT TỪNG USER + FOLDERS
        int totalSheets = 1; // Đã có Organization Summary
        int maxSheets = 250; // Excel limit

        for (java.util.Map.Entry<String, java.util.List<FolderDetailedReport>> entry : allUserReports.entrySet()) {
            String userEmail = entry.getKey();
            java.util.List<FolderDetailedReport> userReports = entry.getValue();

            if (userReports.isEmpty()) continue;

            String userPrefix = userEmail.split("@")[0].substring(0, Math.min(3, userEmail.length())) + "_";

            // Giới hạn folders cho mỗi user
            int folderCount = 0;
            for (FolderDetailedReport report : userReports) {
                if (report.detailedLog.activityLog.isEmpty()) continue;
                if (totalSheets >= maxSheets - 5) break; // Dành chỗ cho 5 sheets của folder cuối

                folderCount++;
                String prefix = userPrefix + "F" + folderCount + "_";

                // Tạo 5 sheets chi tiết
                createEnhancedSummarySheetForFolder(workbook, report, prefix);
                createEnhancedFilesStatusSheetForFolder(workbook, report, prefix);
                createEnhancedTimelineSheetForFolder(workbook, report, prefix);
                createEnhancedFileDetailsSheetForFolder(workbook, report, prefix);
                createEnhancedDeletedFilesSheetForFolder(workbook, report, prefix);

                totalSheets += 5;

                // Giới hạn 3 folders/user để tránh quá nhiều sheets
                if (folderCount >= 3) break;
            }

            System.out.println("✓ Đã export " + folderCount + " folders cho " + userEmail);
        }

        try (FileOutputStream out = new FileOutputStream(fullPath)) {
            workbook.write(out);
        }
        workbook.close();

        System.out.println("📊 Tổng số sheets: " + totalSheets);
        return new java.io.File(fullPath).getAbsolutePath();
    }

    /**
     * 🆕 Tạo Organization Summary Sheet
     */
    private void createOrganizationSummarySheet(Workbook wb, java.util.Map<String, java.util.List<FolderDetailedReport>> allUserReports) {
        Sheet sheet = wb.createSheet("Organization Summary");

        // Title style
        CellStyle titleStyle = wb.createCellStyle();
        Font titleFont = wb.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleStyle.setFont(titleFont);

        // Header style
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        // Title
        Row row0 = sheet.createRow(0);
        Cell titleCell = row0.createCell(0);
        titleCell.setCellValue("📊 ORGANIZATION-WIDE DETAILED ACTIVITY REPORT");
        titleCell.setCellStyle(titleStyle);

        sheet.createRow(2).createCell(0).setCellValue("Report Generated:");
        sheet.getRow(2).createCell(1).setCellValue(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        sheet.createRow(3).createCell(0).setCellValue("Total Users:");
        sheet.getRow(3).createCell(1).setCellValue(allUserReports.size());

        // Calculate totals
        int totalFolders = 0;
        int totalFiles = 0;
        int totalEvents = 0;

        for (java.util.List<FolderDetailedReport> reports : allUserReports.values()) {
            totalFolders += reports.size();
            for (FolderDetailedReport report : reports) {
                totalFiles += report.detailedLog.fileMap.size();
                totalEvents += report.detailedLog.activityLog.size();
            }
        }

        sheet.createRow(4).createCell(0).setCellValue("Total Folders:");
        sheet.getRow(4).createCell(1).setCellValue(totalFolders);

        sheet.createRow(5).createCell(0).setCellValue("Total Files:");
        sheet.getRow(5).createCell(1).setCellValue(totalFiles);

        sheet.createRow(6).createCell(0).setCellValue("Total Events:");
        sheet.getRow(6).createCell(1).setCellValue(totalEvents);

        // User breakdown header
        Row headerRow = sheet.createRow(8);
        String[] headers = {"User Email", "Folders", "Files", "Events"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // User data
        int rowNum = 9;
        for (java.util.Map.Entry<String, java.util.List<FolderDetailedReport>> entry : allUserReports.entrySet()) {
            String userEmail = entry.getKey();
            java.util.List<FolderDetailedReport> reports = entry.getValue();

            int userFolders = reports.size();
            int userFiles = 0;
            int userEvents = 0;

            for (FolderDetailedReport report : reports) {
                userFiles += report.detailedLog.fileMap.size();
                userEvents += report.detailedLog.activityLog.size();
            }

            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(userEmail);
            row.createCell(1).setCellValue(userFolders);
            row.createCell(2).setCellValue(userFiles);
            row.createCell(3).setCellValue(userEvents);
        }

        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
        sheet.autoSizeColumn(2);
        sheet.autoSizeColumn(3);
        sheet.setColumnWidth(0, 8000);
    }

    /**
     * 🆕 Tạo User Summary Sheet
     */
    private void createUserSummarySheet(Sheet sheet, java.util.List<FolderDetailedReport> reports, String userEmail, Workbook wb) {
        // Title style
        CellStyle titleStyle = wb.createCellStyle();
        Font titleFont = wb.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 12);
        titleStyle.setFont(titleFont);

        // Header style
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        // Title
        Row row0 = sheet.createRow(0);
        Cell titleCell = row0.createCell(0);
        titleCell.setCellValue("USER: " + userEmail);
        titleCell.setCellStyle(titleStyle);

        sheet.createRow(2).createCell(0).setCellValue("Total Folders:");
        sheet.getRow(2).createCell(1).setCellValue(reports.size());

        int totalFiles = 0;
        int totalEvents = 0;
        for (FolderDetailedReport report : reports) {
            totalFiles += report.detailedLog.fileMap.size();
            totalEvents += report.detailedLog.activityLog.size();
        }

        sheet.createRow(3).createCell(0).setCellValue("Total Files:");
        sheet.getRow(3).createCell(1).setCellValue(totalFiles);

        sheet.createRow(4).createCell(0).setCellValue("Total Events:");
        sheet.getRow(4).createCell(1).setCellValue(totalEvents);

        // Folder breakdown
        Row headerRow = sheet.createRow(6);
        String[] headers = {"Folder Path", "Folder ID", "Files", "Events"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowNum = 7;
        for (FolderDetailedReport report : reports) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(report.folderInfo.path);
            row.createCell(1).setCellValue(report.folderInfo.id);
            row.createCell(2).setCellValue(report.detailedLog.fileMap.size());
            row.createCell(3).setCellValue(report.detailedLog.activityLog.size());
        }

        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
        sheet.autoSizeColumn(2);
        sheet.autoSizeColumn(3);
        sheet.setColumnWidth(0, 8000);
        sheet.setColumnWidth(1, 8000);
    }

    /**
     * 🆕 Tạo Consolidated Summary Sheet
     */
    private void createConsolidatedSummarySheet(Workbook wb, java.util.List<FolderDetailedReport> reports, String userEmail) {
        Sheet sheet = wb.createSheet("Summary");

        // Title style
        CellStyle titleStyle = wb.createCellStyle();
        Font titleFont = wb.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleStyle.setFont(titleFont);

        // Header style
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        // Title
        Row row0 = sheet.createRow(0);
        Cell titleCell = row0.createCell(0);
        titleCell.setCellValue("📊 ALL FOLDERS ACTIVITY REPORT - " + userEmail);
        titleCell.setCellStyle(titleStyle);

        sheet.createRow(2).createCell(0).setCellValue("User Email:");
        sheet.getRow(2).createCell(1).setCellValue(userEmail);

        sheet.createRow(3).createCell(0).setCellValue("Total Folders:");
        sheet.getRow(3).createCell(1).setCellValue(reports.size());

        int totalFiles = 0;
        int totalEvents = 0;
        for (FolderDetailedReport report : reports) {
            totalFiles += report.detailedLog.fileMap.size();
            totalEvents += report.detailedLog.activityLog.size();
        }

        sheet.createRow(4).createCell(0).setCellValue("Total Files:");
        sheet.getRow(4).createCell(1).setCellValue(totalFiles);

        sheet.createRow(5).createCell(0).setCellValue("Total Events:");
        sheet.getRow(5).createCell(1).setCellValue(totalEvents);

        sheet.createRow(6).createCell(0).setCellValue("Report Generated:");
        sheet.getRow(6).createCell(1).setCellValue(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        // Folder details
        Row headerRow = sheet.createRow(8);
        String[] headers = {"Folder Path", "Folder ID", "Files", "Events"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowNum = 9;
        for (FolderDetailedReport report : reports) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(report.folderInfo.path);
            row.createCell(1).setCellValue(report.folderInfo.id);
            row.createCell(2).setCellValue(report.detailedLog.fileMap.size());
            row.createCell(3).setCellValue(report.detailedLog.activityLog.size());
        }

        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
        sheet.autoSizeColumn(2);
        sheet.autoSizeColumn(3);
        sheet.setColumnWidth(0, 10000);
        sheet.setColumnWidth(1, 8000);
    }

    /**
     * 🆕 Tạo Folder Detail Sheet (simplified)
     */
    private void createFolderDetailSheetSimple(Sheet sheet, FolderDetailedReport report, Workbook wb) {
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        // Title
        Row row0 = sheet.createRow(0);
        row0.createCell(0).setCellValue("Folder: " + report.folderInfo.path);

        Row row1 = sheet.createRow(1);
        row1.createCell(0).setCellValue("Folder ID: " + report.folderInfo.id);

        // Activity timeline
        Row headerRow = sheet.createRow(3);
        String[] headers = {"Timestamp", "File/Folder", "Action", "Actor", "Details"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        int rowNum = 4;

        for (ActivityEvent event : report.detailedLog.activityLog) {
            if (rowNum > 1000) break; // Limit rows

            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(sdf.format(event.timestamp));
            row.createCell(1).setCellValue(event.fileName);
            row.createCell(2).setCellValue(event.action);
            row.createCell(3).setCellValue(event.actor);
            row.createCell(4).setCellValue(event.details);
        }

        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
        sheet.autoSizeColumn(2);
        sheet.autoSizeColumn(3);
        sheet.autoSizeColumn(4);
        sheet.setColumnWidth(1, 8000);
        sheet.setColumnWidth(4, 10000);
    }

    /**
     * 🆕 Inner class cho detailed report
     */
    static class FolderDetailedReport {
        FolderInfo folderInfo;
        DetailedLog detailedLog;
    }

    // ============================================
    // INNER CLASSES
    // ============================================

    static class FolderInfo {
        String id;
        String name;
        String path;
    }

    static class DetailedLog {
        Map<String, FileRecord> fileMap;
        List<ActivityEvent> activityLog;
    }

    static class FileRecord {
        String id;
        String name;
        boolean isFolder;
        List<ActivityEvent> events;
        CurrentStatus currentStatus;
    }

    static class ActivityEvent {
        Date timestamp;
        String fileId;
        String fileName;
        boolean isFolder;
        String action;
        String actor;
        String details;
        String fromLocation;
        String toLocation;
    }

    static class CurrentStatus {
        String statusCode;
        String status;
        String location;
        boolean trashed;

        CurrentStatus(String statusCode, String status, String location, boolean trashed) {
            this.statusCode = statusCode;
            this.status = status;
            this.location = location;
            this.trashed = trashed;
        }
    }
}