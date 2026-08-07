package driverecovery;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.Set;

import static driverecovery.ConfigPanel.*;


/**
 * MAIN WINDOW - Left sidebar | Right (Users top / Run bottom)
 */
public class MainWindow extends JFrame {

    private final ConfigPanel   cfgPanel;
    private final UserListPanel userPanel;
    private final RunPanel      runPanel;
    private TaskRunner currentTask;

    public MainWindow() {
        super("Drive Recovery Tool  v2.0");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1200, 700));
        setPreferredSize(new Dimension(1440, 860));

        // Apply font + UI defaults BEFORE building panels
        applyUIDefaults();

        cfgPanel  = new ConfigPanel();
        userPanel = new UserListPanel();
        runPanel  = new RunPanel();

        userPanel.setConfigSupplier(cfgPanel::buildAppConfig);
        userPanel.loadDefaultUsers(Config.USERS_TO_CHECK);

        runPanel.setOnRunAction(this::onRun);
        runPanel.setOnStopAction(this::onStop);

        setContentPane(buildContent());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { confirmExit(); }
        });

        pack();
        setLocationRelativeTo(null);

        SwingUtilities.invokeLater(() -> {
        });
    }

    // ══════════════════════════════════════════════════════
    // LAYOUT
    // ══════════════════════════════════════════════════════

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(C_BG0);

        root.add(buildTitleBar(), BorderLayout.NORTH);

        // Left sidebar
        JPanel sidebar = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, new Color(0x080C10), getWidth(), 0, C_BG1));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        sidebar.setOpaque(false);
        // Glowing right border
        sidebar.setBorder(new javax.swing.border.AbstractBorder() {
            @Override public void paintBorder(java.awt.Component c, Graphics g, int x, int y, int w, int h) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(w-4, 0, new Color(0x58A6FF44, true), w, 0, new Color(0x00000000, true)));
                g2.fillRect(w-4, 0, 4, h);
                g2.setColor(C_BORDER);
                g2.drawLine(w-1, 0, w-1, h);
                g2.dispose();
            }
            @Override public java.awt.Insets getBorderInsets(java.awt.Component c) {
                return new java.awt.Insets(0, 0, 0, 1);
            }
        });
        sidebar.setPreferredSize(new Dimension(360, 0));
        sidebar.setMinimumSize(new Dimension(300, 0));
        sidebar.add(cfgPanel, BorderLayout.CENTER);

        // Right: Users (top) + Run (bottom)
        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, userPanel, runPanel);
        rightSplit.setDividerLocation(300);
        rightSplit.setDividerSize(6);
        rightSplit.setBorder(null);
        rightSplit.setBackground(C_BG0);
        rightSplit.setResizeWeight(0.38);

        rightSplit.setUI(new javax.swing.plaf.basic.BasicSplitPaneUI() {
            @Override public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
                return new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                    @Override public void paint(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setColor(C_BG2);
                        g2.fillRect(0, 0, getWidth(), getHeight());
                        g2.setColor(C_BORDER2);
                        float[] dash = {4f, 6f};
                        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, dash, 0));
                        g2.drawLine(20, getHeight()/2, getWidth()-20, getHeight()/2);
                        g2.dispose();
                    }
                };
            }
        });

        JPanel rightWrapper = new JPanel(new BorderLayout());
        rightWrapper.setBackground(C_BG0);
        rightWrapper.add(rightSplit, BorderLayout.CENTER);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, rightWrapper);
        mainSplit.setDividerLocation(360);
        mainSplit.setDividerSize(0);
        mainSplit.setBorder(null);
        mainSplit.setBackground(C_BG0);

        root.add(mainSplit, BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
        return root;
    }

    // Title Bar

    private JPanel buildTitleBar() {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(
                    0, 0, new Color(0x05080D),
                    getWidth(), 0, new Color(0x0D1117)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setPaint(new java.awt.RadialGradientPaint(
                    26, getHeight()/2f, 60,
                    new float[]{0f, 1f},
                    new Color[]{new Color(0x58A6FF1A, true), new Color(0x00000000, true)}));
                g2.fillRect(0, 0, 120, getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x21262D)),
                BorderFactory.createEmptyBorder(0, 0, 0, 0)));
        bar.setPreferredSize(new Dimension(0, 52));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);

        JPanel iconBlock = new JPanel(new java.awt.GridBagLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, new Color(0x1D3557), 0, getHeight(), new Color(0x0A0F17)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        iconBlock.setOpaque(false);
        iconBlock.setPreferredSize(new Dimension(52, 52));
        iconBlock.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(0x30363D)));
        JLabel iconLbl = new JLabel("\u2601");
        iconLbl.setForeground(C_ACCENT);
        iconLbl.setFont(new Font(F_TITLE.getFamily(), Font.PLAIN, 22));
        iconBlock.add(iconLbl);

        JPanel names = new JPanel();
        names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
        names.setOpaque(false);
        names.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));

        JLabel appName = new JLabel("Drive Recovery Tool");
        appName.setForeground(C_TEXT);
        appName.setFont(new Font(F_TITLE.getFamily(), Font.BOLD, 17));
        appName.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("v2.0  \u2014  Google Workspace Edition");
        subtitle.setForeground(C_TEXT3);
        subtitle.setFont(new Font(F_LABEL.getFamily(), Font.PLAIN, 11));
        subtitle.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        names.add(appName); names.add(Box.createVerticalStrut(2)); names.add(subtitle);
        left.add(iconBlock); left.add(names);
        bar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 14));
        right.setOpaque(false);
        right.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
        String jv = System.getProperty("java.version","?").split("\\.")[0];
        right.add(mkBadge("Java " + jv, C_ACCENT));
        right.add(mkBadge("Drive API v3", C_GREEN));
        right.add(mkBadge("Activity API v2", C_PURPLE));
        right.add(mkBadge("Admin SDK", C_YELLOW));
        bar.add(right, BorderLayout.EAST);

        return bar;
    }

    private JLabel mkBadge(String text, Color accent) {
        JLabel l = new JLabel(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = new Color(
                        (int)(accent.getRed()*0.08 + C_BG2.getRed()*0.92),
                        (int)(accent.getGreen()*0.08 + C_BG2.getGreen()*0.92),
                        (int)(accent.getBlue()*0.08 + C_BG2.getBlue()*0.92));
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 120));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setForeground(accent.brighter());
        l.setFont(new Font(F_SMALL.getFamily(), Font.PLAIN, 10));
        l.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        l.setOpaque(false);
        return l;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(0x040709));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x21262D)));
        bar.setPreferredSize(new Dimension(0, 24));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        left.setOpaque(false);

        JLabel dot = new JLabel("\u25CF");
        dot.setForeground(C_GREEN);
        dot.setFont(new Font(F_SMALL.getFamily(), Font.PLAIN, 7));

        // "S\u1eb5n s\u00e0ng" = "Sẵn sàng"
        JLabel lbl = new JLabel("S\u1eb5n s\u00e0ng");
        lbl.setForeground(C_TEXT3);
        lbl.setFont(new Font(F_SMALL.getFamily(), Font.PLAIN, 10));

        left.add(dot); left.add(lbl);
        bar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        right.setOpaque(false);
        JLabel ver = new JLabel("Drive Recovery Tool  v2.0");
        ver.setForeground(new Color(0x3D444D));
        ver.setFont(new Font(F_SMALL.getFamily(), Font.PLAIN, 10));
        right.add(ver);
        bar.add(right, BorderLayout.EAST);

        return bar;
    }

    // ══════════════════════════════════════════════════════
    // RUN / STOP
    // ══════════════════════════════════════════════════════

    private void onRun() {
        AppConfig cfg      = cfgPanel.buildAppConfig();
        List<String> users = userPanel.getSelectedUsers();

        // Mode 2: không cần user list — dùng adminEmail nếu không có ai được tick
        cfg.runMode       = runPanel.getSelectedMode();
        cfg.folderIdMode2 = runPanel.getFolderId();
        if ("2".equals(cfg.runMode) && users.isEmpty() && !cfg.adminEmail.isBlank()) {
            users = List.of(cfg.adminEmail);
        }

        // Validate cơ bản ngay (tránh fetch SDK rồi mới báo lỗi config)
        cfg.selectedUsers     = users;
        cfg.allUsersForSearch = users; // tạm, sẽ cập nhật sau fetch
        String err = cfg.validate();
        if (err != null) {
            JOptionPane.showMessageDialog(this, "\u274c  " + err,
                    "C\u1ea5u h\u00ecnh ch\u01b0a \u0111\u00fang", JOptionPane.WARNING_MESSAGE);
            return;
        }

        final List<String> finalUsers = users;

        // Nếu chưa fetch Admin SDK và có đủ config → tự động fetch trước khi chạy
        if (!userPanel.isAllForSearchReady()
                && !cfg.adminEmail.isBlank()
                && !cfg.serviceAccountJsonPath.isBlank()) {

            runPanel.appendLog("\uD83D\uDD04  T\u1ef1 \u0111\u1ed9ng t\u1ea3i danh s\u00e1ch user t\u1eeb Admin SDK tr\u01b0\u1edbc khi ch\u1ea1y...",
                    ProgressTracker.LogLevel.INFO);
            runPanel.setRunning(true); // khoá nút Chạy trong lúc fetch

            userPanel.fetchUsersForRun(
                success -> SwingUtilities.invokeLater(() -> doStartRun(finalUsers)),
                msg     -> runPanel.appendLog(msg, ProgressTracker.LogLevel.INFO)
            );
        } else {
            // Đã sẵn sàng (đã fetch trước đó hoặc không có config SDK) → chạy ngay
            doStartRun(finalUsers);
        }
    }

    /**
     * Giai đoạn 2 của onRun() — được gọi sau khi fetch Admin SDK xong (hoặc bỏ qua).
     * Lúc này allForSearch đã được cập nhật → lấy lại cfg đầy đủ rồi khởi động TaskRunner.
     */
    private void doStartRun(List<String> users) {
        AppConfig cfg = cfgPanel.buildAppConfig();
        cfg.selectedUsers = users;
        cfg.runMode       = runPanel.getSelectedMode();
        cfg.folderIdMode2 = runPanel.getFolderId();

        // Mode 2: không cần user list — dùng adminEmail nếu không có ai được tick
        if ("2".equals(cfg.runMode) && users.isEmpty() && !cfg.adminEmail.isBlank()) {
            users = List.of(cfg.adminEmail);
            cfg.selectedUsers = users;
        }

        List<String> allUsers = userPanel.getAllUsersForSearch();
        cfg.allUsersForSearch = allUsers.isEmpty() ? users : allUsers;

        // Mode 2: allUsersForSearch = TOÀN BỘ users đã load (kể cả không tích)
        if ("2".equals(cfg.runMode)) {
            List<String> allLoaded   = userPanel.getAllLoadedUsers();
            List<String> mode2Search = new java.util.ArrayList<>(allLoaded.isEmpty() ? allUsers : allLoaded);
            if (!cfg.adminEmail.isBlank() && !mode2Search.contains(cfg.adminEmail))
                mode2Search.add(cfg.adminEmail);
            cfg.allUsersForSearch = mode2Search;
            runPanel.appendLog("\u2139\ufe0f  Mode 2: S\u1ebd qu\u00e9t " + cfg.allUsersForSearch.size()
                    + " users trong t\u1ed5 ch\u1ee9c khi t\u00ecm file/folder b\u1ecb thi\u1ebfu.",
                    ProgressTracker.LogLevel.INFO);
        }

        // Cảnh báo allUsersForSearch nhỏ
        if (!"2".equals(cfg.runMode) && !users.isEmpty()
                && cfg.allUsersForSearch.size() <= users.size()) {
            runPanel.appendLog("\u26a0\ufe0f  Danh s\u00e1ch qu\u00e9t = " + cfg.allUsersForSearch.size()
                    + " user \u2014 c\u00e0i Email Admin + JSON \u0111\u1ec3 t\u00ecm r\u1ed9ng h\u01a1n!",
                    ProgressTracker.LogLevel.WARNING);
        } else if (!"2".equals(cfg.runMode) && !users.isEmpty()) {
            runPanel.appendLog("\u2139\ufe0f  S\u1ebd qu\u00e9t " + cfg.allUsersForSearch.size()
                    + " users trong t\u1ed5 ch\u1ee9c khi t\u00ecm file/folder b\u1ecb thi\u1ebfu.",
                    ProgressTracker.LogLevel.INFO);
        }

        // Checkpoint chỉ dùng cho Mode 1
        if ("1".equals(cfg.runMode) && TaskRunner.checkpointExists(cfg.outputDirectory)) {
            Set<String> done = TaskRunner.loadCompletedUsers(cfg.outputDirectory);
            if (!done.isEmpty()) {
                int choice = JOptionPane.showOptionDialog(this,
                        String.format("\uD83D\uDCD6  T\u00ecm th\u1ea5y \u0111i\u1ec3m ki\u1ec3m tra!\n  \u0110\u00e3 xong: %d ng\u01b0\u1eddi d\u00f9ng\n  T\u1ed5ng: %d ng\u01b0\u1eddi d\u00f9ng\n\nTi\u1ebfp t\u1ee5c hay l\u00e0m l\u1ea1i?",
                                done.size(), users.size()),
                        "\u0110i\u1ec3m ki\u1ec3m tra",
                        JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                        new String[]{"\u23e9  Ti\u1ebfp t\u1ee5c", "\uD83D\uDD04  L\u00e0m l\u1ea1i", "Hu\u1ef7"},
                        "\u23e9  Ti\u1ebfp t\u1ee5c");
                if (choice == 2 || choice < 0) {
                    runPanel.setRunning(false);
                    return;
                }
                cfg.resumeFromCheckpoint = (choice == 0);
            }
        }

        ProgressTracker.getInstance().reset();
        runPanel.resetProgress();
        runPanel.setRunning(true);
        runPanel.appendLog("\uD83D\uDE80  B\u1eaft \u0111\u1ea7u  Ch\u1ebf \u0111\u1ed9 " + cfg.runMode
                + "  |  " + users.size() + " ng\u01b0\u1eddi d\u00f9ng",
                ProgressTracker.LogLevel.HEADER);

        final AppConfig finalCfg = cfg;
        currentTask = new TaskRunner(finalCfg, () -> SwingUtilities.invokeLater(() -> runPanel.setRunning(false)));
        currentTask.execute();
    }

    private void onStop() {
        if (currentTask != null && !currentTask.isDone()) {
            // "\u26d4  D\u1eebng sau khi ho\u00e0n th\u00e0nh th\u01b0 m\u1ee5c hi\u1ec7n t\u1ea1i?"
            int r = JOptionPane.showConfirmDialog(this,
                    "\u26d4  D\u1eebng sau khi ho\u00e0n th\u00e0nh th\u01b0 m\u1ee5c hi\u1ec7n t\u1ea1i?",
                    "X\u00e1c nh\u1eadn d\u1eebng",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r == JOptionPane.YES_OPTION) {
                ProgressTracker.getInstance().requestStop();
                runPanel.appendLog("\u26d4  \u0110\u00e3 g\u1eedi l\u1ec7nh d\u1eebng...", ProgressTracker.LogLevel.WARNING);
            }
        }
    }

    private void confirmExit() {
        if (currentTask != null && !currentTask.isDone()) {
            int r = JOptionPane.showConfirmDialog(this,
                    "\u26a0\ufe0f  \u0110ang ch\u1ea1y! Tho\u00e1t?",
                    "X\u00e1c nh\u1eadn",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
        }
        dispose(); System.exit(0);
    }

    // ══════════════════════════════════════════════════════
    // GLOBAL UI DEFAULTS
    // ══════════════════════════════════════════════════════

    private void applyUIDefaults() {
        // Use "Dialog" logical font — Java maps this to the best Unicode-capable system font
        Font defaultFont = new Font("Dialog", Font.PLAIN, 12);
        Font monoFont    = new Font("Monospaced", Font.PLAIN, 12);

        UIManager.put("defaultFont",                  defaultFont);
        UIManager.put("Panel.background",             C_BG0);
        UIManager.put("Panel.font",                   defaultFont);
        UIManager.put("Label.foreground",             C_TEXT);
        UIManager.put("Label.font",                   defaultFont);
        UIManager.put("TextField.background",         C_BG3);
        UIManager.put("TextField.foreground",         C_TEXT);
        UIManager.put("TextField.caretForeground",    C_ACCENT);
        UIManager.put("TextField.font",               defaultFont);
        UIManager.put("TextArea.background",          C_BG0);
        UIManager.put("TextArea.foreground",          C_TEXT);
        UIManager.put("TextArea.font",                monoFont);
        UIManager.put("TextPane.background",          C_BG0);
        UIManager.put("TextPane.foreground",          C_TEXT);
        UIManager.put("TextPane.font",                monoFont);
        UIManager.put("Button.background",            C_BG2);
        UIManager.put("Button.foreground",            C_TEXT2);
        UIManager.put("Button.font",                  defaultFont);
        UIManager.put("CheckBox.background",          C_BG1);
        UIManager.put("CheckBox.foreground",          C_TEXT);
        UIManager.put("CheckBox.font",                defaultFont);
        UIManager.put("RadioButton.background",       C_BG1);
        UIManager.put("RadioButton.foreground",       C_TEXT);
        UIManager.put("RadioButton.font",             defaultFont);
        UIManager.put("TabbedPane.background",        C_BG2);
        UIManager.put("TabbedPane.foreground",        C_TEXT2);
        UIManager.put("TabbedPane.font",              defaultFont);
        UIManager.put("TabbedPane.selected",          C_BG0);
        UIManager.put("TabbedPane.contentAreaColor",  C_BG0);
        UIManager.put("TabbedPane.light",             C_BORDER);
        UIManager.put("TabbedPane.darkShadow",        C_BORDER);
        UIManager.put("TabbedPane.shadow",            C_BORDER);
        UIManager.put("TabbedPane.focus",             C_BG0);
        UIManager.put("Table.background",             C_BG0);
        UIManager.put("Table.foreground",             C_TEXT);
        UIManager.put("Table.gridColor",              C_BG2);
        UIManager.put("Table.selectionBackground",    new Color(0x1F3A5F));
        UIManager.put("Table.selectionForeground",    C_TEXT);
        UIManager.put("Table.font",                   defaultFont);
        UIManager.put("TableHeader.background",       C_BG2);
        UIManager.put("TableHeader.foreground",       C_TEXT2);
        UIManager.put("TableHeader.font",             new Font("Dialog", Font.BOLD, 11));
        UIManager.put("ScrollPane.background",        C_BG0);
        UIManager.put("ScrollBar.background",         C_BG1);
        UIManager.put("ScrollBar.thumb",              C_BG3);
        UIManager.put("SplitPane.background",         C_BG0);
        UIManager.put("SplitPane.dividerSize",        4);
        UIManager.put("OptionPane.background",        C_BG1);
        UIManager.put("OptionPane.messageForeground", C_TEXT);
        UIManager.put("OptionPane.font",              defaultFont);
        UIManager.put("Spinner.background",           C_BG3);
        UIManager.put("Spinner.font",                 defaultFont);
        UIManager.put("ComboBox.background",          C_BG3);
        UIManager.put("ComboBox.foreground",          C_TEXT);
        UIManager.put("ComboBox.font",                defaultFont);
        UIManager.put("ProgressBar.background",       C_BG3);
        UIManager.put("ProgressBar.foreground",       C_ACCENT);
        UIManager.put("ToolTip.background",           C_BG2);
        UIManager.put("ToolTip.foreground",           C_TEXT);
        UIManager.put("ToolTip.font",                 defaultFont);
    }

    // ══════════════════════════════════════════════════════
    // LAUNCH
    // ══════════════════════════════════════════════════════

    public static void launch() {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        System.setProperty("flatlaf.useWindowDecorations", "false");

        SwingUtilities.invokeLater(() -> {
            try {
                com.formdev.flatlaf.FlatDarculaLaf.setup();

                UIManager.put("@accent",                   "#58A6FF");
                UIManager.put("@accentDarker",             "#388BFD");
                UIManager.put("Panel.background",          C_BG0);
                UIManager.put("TabbedPane.background",     C_BG2);
                UIManager.put("TabbedPane.underlineColor", C_ACCENT);
                UIManager.put("TabbedPane.inactiveUnderlineColor", C_BORDER);
                UIManager.put("TabbedPane.tabHeight",      36);
                UIManager.put("Table.background",          C_BG0);
                UIManager.put("Table.alternateRowColor",   new Color(0x131820));
                UIManager.put("Table.selectionBackground", new Color(0x1F3A5F));
                UIManager.put("TableHeader.background",    C_BG2);
                UIManager.put("ScrollBar.width",           10);
                UIManager.put("ScrollBar.thumbArc",        999);
                UIManager.put("ScrollBar.thumbInsets",     new Insets(2,3,2,3));
                UIManager.put("Button.arc",                8);
                UIManager.put("Component.arc",             6);
                UIManager.put("TextComponent.arc",         6);
                UIManager.put("CheckBox.icon.arc",         4);
                UIManager.put("ProgressBar.arc",           999);
                UIManager.put("ProgressBar.background",    C_BG3);
                UIManager.put("ProgressBar.foreground",    C_ACCENT);
                UIManager.put("ToolTip.background",        C_BG2);
                UIManager.put("PopupMenu.background",      C_BG2);
                UIManager.put("MenuItem.background",       C_BG2);
                UIManager.put("MenuItem.selectionBackground", new Color(0x1F3A5F));

                com.formdev.flatlaf.util.FontUtils.getCompositeFont("Segoe UI", java.awt.Font.PLAIN, 13);

            } catch (Exception ex) {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
                catch (Exception ignored) {}
            }
            new MainWindow().setVisible(true);
        });
    }
}
