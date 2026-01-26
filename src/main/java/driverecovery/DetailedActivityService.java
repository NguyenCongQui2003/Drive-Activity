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

        java.io.File outputDir = new java.io.File(Config.OUTPUT_DIRECTORY);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        String fullPath = Config.OUTPUT_DIRECTORY + fileName;
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