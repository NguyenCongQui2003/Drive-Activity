package driverecovery;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.Set;

import static driverecovery.ConfigPanel.*;


/**
 * â­ MAIN WINDOW â€” Left sidebar | Right (Users top / Run bottom)
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
            runPanel.appendLog("â•”â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•—", ProgressTracker.LogLevel.HEADER);
            runPanel.appendLog("â•‘     DRIVE RECOVERY TOOL  v2.0  â€”  GUI MODE              â•‘", ProgressTracker.LogLevel.HEADER);
            runPanel.appendLog("â• â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•£", ProgressTracker.LogLevel.HEADER);
            runPanel.appendLog("â•‘  1.  Chá»n TÃ i khoáº£n Dá»‹ch vá»¥ JSON  (thanh bÃªn trÃ¡i)       â•‘", ProgressTracker.LogLevel.INFO);
            runPanel.appendLog("â•‘  2.  TÃ­ch chá»n ngÆ°á»i dÃ¹ng cáº§n xá»­ lÃ½  (báº£ng phÃ­a trÃªn)    â•‘", ProgressTracker.LogLevel.INFO);
            runPanel.appendLog("â•‘  3.  Chá»n cháº¿ Ä‘á»™ vÃ  nháº¥n  CHáº Y  (phÃ­a dÆ°á»›i)           â•‘", ProgressTracker.LogLevel.INFO);
            runPanel.appendLog("â•šâ•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•", ProgressTracker.LogLevel.HEADER);
        });
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // LAYOUT
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(C_BG0);

        root.add(buildTitleBar(), BorderLayout.NORTH);

        // â”€â”€ Left sidebar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        JPanel sidebar = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                // Subtle gradient from dark to slightly lighter
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
                // Outer blue glow strip (3px)
                g2.setPaint(new GradientPaint(w-4, 0, new Color(0x58A6FF44, true), w, 0, new Color(0x00000000, true)));
                g2.fillRect(w-4, 0, 4, h);
                // Hard 1px line
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

        // â”€â”€ Right: Users (top) + Run (bottom) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
                        // Dotted line in center
                        g2.setColor(C_BORDER2);
                        float[] dash = {4f, 6f};
                        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, dash, 0));
                        g2.drawLine(20, getHeight()/2, getWidth()-20, getHeight()/2);
                        g2.dispose();
                    }
                };
            }
        });

        // â”€â”€ Wrap right in a subtle bg â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        JPanel rightWrapper = new JPanel(new BorderLayout());
        rightWrapper.setBackground(C_BG0);
        rightWrapper.add(rightSplit, BorderLayout.CENTER);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, rightWrapper);
        mainSplit.setDividerLocation(360);
        mainSplit.setDividerSize(0); // invisible â€” sidebar paints its own border
        mainSplit.setBorder(null);
        mainSplit.setBackground(C_BG0);

        root.add(mainSplit, BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
        return root;
    }

    // â”€â”€ Title Bar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private JPanel buildTitleBar() {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                // Rich deep gradient left-to-right
                g2.setPaint(new GradientPaint(
                    0, 0, new Color(0x05080D),
                    getWidth(), 0, new Color(0x0D1117)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                // Subtle blue glow at top-left behind icon
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

        // â”€â”€ Left: logo area
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);

        // Icon block â€” accent square with gradient
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

        // App name area
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

        // â”€â”€ Right: badges
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
                // Background with subtle tint
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

        // Pulsing green dot
        JLabel dot = new JLabel("\u25CF");
        dot.setForeground(C_GREEN);
        dot.setFont(new Font(F_SMALL.getFamily(), Font.PLAIN, 7));

        JLabel lbl = new JLabel("S\u1eb5n s\u00e0ng");
        lbl.setForeground(C_TEXT3);
        lbl.setFont(new Font(F_SMALL.getFamily(), Font.PLAIN, 10));

        left.add(dot); left.add(lbl);
        bar.add(left, BorderLayout.WEST);

        // Right: version hint
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        right.setOpaque(false);
        JLabel ver = new JLabel("Drive Recovery Tool  v2.0");
        ver.setForeground(new Color(0x3D444D));
        ver.setFont(new Font(F_SMALL.getFamily(), Font.PLAIN, 10));
        right.add(ver);
        bar.add(right, BorderLayout.EAST);

        return bar;
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // RUN / STOP
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private void onRun() {
        AppConfig cfg   = cfgPanel.buildAppConfig();
        List<String> users    = userPanel.getSelectedUsers();
        List<String> allUsers = userPanel.getAllUsersForSearch();
        cfg.selectedUsers     = users;
        cfg.allUsersForSearch = allUsers.isEmpty() ? users : allUsers;
        cfg.runMode           = runPanel.getSelectedMode();
        cfg.folderIdMode2     = runPanel.getFolderId();

        // Mode 2: khÃ´ng cáº§n user list â€” dÃ¹ng adminEmail náº¿u khÃ´ng cÃ³ ai Ä‘Æ°á»£c tick
        if ("2".equals(cfg.runMode) && users.isEmpty() && !cfg.adminEmail.isBlank()) {
            users = List.of(cfg.adminEmail);
            cfg.selectedUsers = users;
        }

        // Mode 2: allUsersForSearch = TOÃ€N Bá»˜ users Ä‘Ã£ load trong báº£ng (ká»ƒ cáº£ khÃ´ng tÃ­ch)
        // Giá»‘ng Mode 1 dÃ¹ng allForSearch 14 users tá»« Admin SDK fetch
        if ("2".equals(cfg.runMode)) {
            List<String> allLoaded = userPanel.getAllLoadedUsers();
            List<String> mode2Search = new java.util.ArrayList<>(allLoaded.isEmpty() ? allUsers : allLoaded);
            if (!cfg.adminEmail.isBlank() && !mode2Search.contains(cfg.adminEmail))
                mode2Search.add(cfg.adminEmail);
            cfg.allUsersForSearch = mode2Search;
            runPanel.appendLog("â„¹ï¸  Mode 2: Sáº½ quÃ©t " + cfg.allUsersForSearch.size()
                    + " users trong tá»• chá»©c khi tÃ¬m file/folder bá»‹ thiáº¿u.",
                    ProgressTracker.LogLevel.INFO);
        }

        // Cáº£nh bÃ¡o allUsersForSearch nhá» (chá»‰ cáº£nh bÃ¡o khi mode 1/3/4 cÃ³ user)
        if (!"2".equals(cfg.runMode) && !users.isEmpty()
                && cfg.allUsersForSearch.size() <= users.size()) {
            runPanel.appendLog("âš ï¸  Danh sÃ¡ch quÃ©t (allUsersForSearch) = " + cfg.allUsersForSearch.size()
                    + " user â€” báº±ng vá»›i danh sÃ¡ch cháº¡y.",
                    ProgressTracker.LogLevel.WARNING);
            runPanel.appendLog("   Äá»ƒ tÃ¬m file/folder á»Ÿ Drive ngÆ°á»i khÃ¡c trong tá»• chá»©c, hÃ£y Fetch Admin SDK trÆ°á»›c!",
                    ProgressTracker.LogLevel.WARNING);
        } else if (!"2".equals(cfg.runMode) && !users.isEmpty()) {
            runPanel.appendLog("â„¹ï¸  Sáº½ quÃ©t " + cfg.allUsersForSearch.size()
                    + " users trong tá»• chá»©c khi tÃ¬m file/folder bá»‹ thiáº¿u.",
                    ProgressTracker.LogLevel.INFO);
        }


        String err = cfg.validate();
        if (err != null) {
            JOptionPane.showMessageDialog(this, "âŒ  " + err, "Cáº¥u hÃ¬nh chÆ°a Ä‘Ãºng", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Checkpoint chá»‰ dÃ¹ng cho Mode 1 (nhiá»u user) â€” Mode 2/3/4 khÃ´ng cáº§n
        if ("1".equals(cfg.runMode) && TaskRunner.checkpointExists(cfg.outputDirectory)) {
            Set<String> done = TaskRunner.loadCompletedUsers(cfg.outputDirectory);
            if (!done.isEmpty()) {
                int choice = JOptionPane.showOptionDialog(this,
                        String.format("ðŸ”–  TÃ¬m tháº¥y Ä‘iá»ƒm kiá»ƒm tra!\n  ÄÃ£ xong: %d ngÆ°á»i dÃ¹ng\n  Tá»•ng: %d ngÆ°á»i dÃ¹ng\n\nTiáº¿p tá»¥c hay lÃ m láº¡i?",
                                done.size(), users.size()),
                        "Äiá»ƒm kiá»ƒm tra", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                        new String[]{"â©  Tiáº¿p tá»¥c", "ðŸ”„  LÃ m láº¡i", "Há»§y"}, "â©  Tiáº¿p tá»¥c");
                if (choice == 2 || choice < 0) return;
                cfg.resumeFromCheckpoint = (choice == 0);
            }
        }

        ProgressTracker.getInstance().reset();
        runPanel.resetProgress();
        runPanel.setRunning(true);
        runPanel.appendLog("ðŸš€  Báº¯t Ä‘áº§u  Cháº¿ Ä‘á»™ " + cfg.runMode + "  |  " + users.size() + " ngÆ°á»i dÃ¹ng",
                ProgressTracker.LogLevel.HEADER);

        currentTask = new TaskRunner(cfg, () -> SwingUtilities.invokeLater(() -> runPanel.setRunning(false)));
        currentTask.execute();
    }

    private void onStop() {
        if (currentTask != null && !currentTask.isDone()) {
            int r = JOptionPane.showConfirmDialog(this,
                    "â›”  Dá»«ng sau khi hoÃ n thÃ nh thÆ° má»¥c hiá»‡n táº¡i?",
                    "XÃ¡c nháº­n dá»«ng", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r == JOptionPane.YES_OPTION) {
                ProgressTracker.getInstance().requestStop();
                runPanel.appendLog("â›”  ÄÃ£ gá»­i lá»‡nh dá»«ng...", ProgressTracker.LogLevel.WARNING);
            }
        }
    }

    private void confirmExit() {
        if (currentTask != null && !currentTask.isDone()) {
            int r = JOptionPane.showConfirmDialog(this, "âš ï¸  Äang cháº¡y! ThoÃ¡t?", "XÃ¡c nháº­n",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
        }
        dispose(); System.exit(0);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // GLOBAL UI DEFAULTS
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private void applyUIDefaults() {
        // Use "Dialog" logical font â€” Java maps this to the best Unicode-capable system font
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

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // LAUNCH
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    public static void launch() {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        // FlatLaf â€” enable custom decorations
        System.setProperty("flatlaf.useWindowDecorations", "false");

        SwingUtilities.invokeLater(() -> {
            try {
                // Use FlatLaf Darcula (IntelliJ-style) as base, then override with our colors
                com.formdev.flatlaf.FlatDarculaLaf.setup();

                // Override specific colors to match our palette
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

                // Set modern Inter/Segoe UI font via FlatLaf
                com.formdev.flatlaf.util.FontUtils.getCompositeFont("Segoe UI", java.awt.Font.PLAIN, 13);

            } catch (Exception ex) {
                // Fallback to system L&F
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
                catch (Exception ignored) {}
            }
            new MainWindow().setVisible(true);
        });
    }
}
