package driverecovery;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.dnd.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import com.google.gson.*;

public class ConfigPanel extends JPanel {

    // ── Design tokens ──────────────────────────────────────
    public static final Color C_BG0     = new Color(0x0D1117);
    public static final Color C_BG1     = new Color(0x161B22);
    public static final Color C_BG2     = new Color(0x21262D);
    public static final Color C_BG3     = new Color(0x2D333B);
    public static final Color C_BORDER  = new Color(0x30363D);
    public static final Color C_BORDER2 = new Color(0x444C56);
    public static final Color C_ACCENT  = new Color(0x58A6FF);
    public static final Color C_ACCENT2 = new Color(0x79C0FF);
    public static final Color C_GREEN   = new Color(0x3FB950);
    public static final Color C_YELLOW  = new Color(0xE3B341);
    public static final Color C_RED     = new Color(0xF85149);
    public static final Color C_PURPLE  = new Color(0xBC8CFF);
    public static final Color C_ORANGE  = new Color(0xF0883E);
    public static final Color C_TEXT    = new Color(0xE6EDF3);
    public static final Color C_TEXT2   = new Color(0x8B949E);
    public static final Color C_TEXT3   = new Color(0x6E7681);

    // ── Font detection ─────────────────────────────────────
    public static final Font F_TITLE;
    public static final Font F_LABEL;
    public static final Font F_INPUT;
    public static final Font F_SMALL;
    public static final Font F_MONO;

    static {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Set<String> av = new HashSet<>(Arrays.asList(ge.getAvailableFontFamilyNames()));
        String ui   = av.contains("Segoe UI")   ? "Segoe UI"   : av.contains("Tahoma") ? "Tahoma" : "Dialog";
        String mono = av.contains("Consolas")    ? "Consolas"   : av.contains("Courier New") ? "Courier New" : "Monospaced";
        F_TITLE = new Font(ui,   Font.BOLD,  15);
        F_LABEL = new Font(ui,   Font.PLAIN, 13);
        F_INPUT = new Font(mono, Font.PLAIN, 13);
        F_SMALL = new Font(ui,   Font.PLAIN, 12);
        F_MONO  = new Font(mono, Font.PLAIN, 13);
    }

    private JTextField tfJsonPath;
    private JTextField tfAdminEmail;
    private JLabel     lblStatus, lblEmail, lblProject;
    private String domain;
    private JTextField tfOutputDir;
    private JSpinner   spDays;
    private JTextField tfEndDate;
    private JCheckBox  cbFolders, cbFiles;
    private JWindow    calWindow;

    public ConfigPanel() {
        setBackground(C_BG1);
        setLayout(new BorderLayout());
        add(mkHeader(), BorderLayout.NORTH);

        // Auto-populate domain from Config defaults
        domain = Config.getDomain();

        // ScrollablePanel: getScrollableTracksViewportWidth()=true forces the
        // viewport to match child width to viewport — prevents BoxLayout right-side clipping.
        ScrollablePanel body = new ScrollablePanel();
        body.setBackground(C_BG1);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        body.add(buildJsonSection());
        body.add(vgap(14));
        body.add(buildOutputSection());
        body.add(vgap(14));
        body.add(buildFilterSection());
        body.add(vgap(14));
        body.add(buildOptionsSection());
        body.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(body,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(C_BG1);
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        add(scroll, BorderLayout.CENTER);
    }

    // ══════════════════════════════════════════════════════
    // HEADER
    // ══════════════════════════════════════════════════════

    private JPanel mkHeader() {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0,0, new Color(0x1C2128), getWidth(),0, C_BG1));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose(); super.paintComponent(g);
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0,0,1,0, C_BORDER),
                BorderFactory.createEmptyBorder(14,16,14,14)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);

        // Gear icon circle
        JLabel iconLbl = new JLabel("\u2699") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0x58A6FF22, true));
                g2.fillOval(0, 0, 26, 26);
                g2.setColor(new Color(0x58A6FF66, true));
                g2.drawOval(0, 0, 25, 25);
                g2.dispose(); super.paintComponent(g);
            }
        };
        iconLbl.setForeground(C_ACCENT);
        iconLbl.setFont(new Font(F_TITLE.getFamily(), Font.PLAIN, 14));
        iconLbl.setPreferredSize(new Dimension(28, 28));
        iconLbl.setHorizontalAlignment(SwingConstants.CENTER);
        iconLbl.setOpaque(false);

        JLabel title = new JLabel("CẤU HÌNH");
        title.setForeground(C_ACCENT);
        title.setFont(new Font(F_TITLE.getFamily(), Font.BOLD, 13));

        left.add(iconLbl);
        left.add(title);
        p.add(left, BorderLayout.WEST);

        // Version badge
        JLabel ver = new JLabel("v2.0") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(C_BG3); g2.fillRoundRect(0,0,getWidth(),getHeight(),10,10);
                g2.setColor(C_BORDER); g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,10,10);
                g2.dispose(); super.paintComponent(g);
            }
        };
        ver.setForeground(C_TEXT3);
        ver.setFont(new Font(F_SMALL.getFamily(), Font.PLAIN, 10));
        ver.setBorder(BorderFactory.createEmptyBorder(3,8,3,8));
        ver.setOpaque(false);
        p.add(ver, BorderLayout.EAST);
        return p;
    }

    // ══════════════════════════════════════════════════════
    // SECTIONS
    // ══════════════════════════════════════════════════════

    private JPanel buildJsonSection() {
        JPanel sec = mkSection("T\u00e0i Kho\u1ea3n D\u1ecbch V\u1ee5", C_YELLOW);

        // ── Drop zone
        JPanel drop = new JPanel(new BorderLayout(8, 0)) {
            boolean hover = false;
            { addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true;  repaint(); }
                public void mouseExited (MouseEvent e) { hover = false; repaint(); }
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hover ? new Color(0xE3B34115, true) : C_BG0);
                g2.fillRoundRect(0,0,getWidth()-1,getHeight()-1,10,10);
                float[] dash = {6f,4f};
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,0,dash,0));
                g2.setColor(hover ? C_YELLOW : C_BORDER2);
                g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,10,10);
                g2.dispose(); super.paintComponent(g);
            }
        };
        drop.setOpaque(false);
        drop.setBorder(BorderFactory.createEmptyBorder(12,16,12,12));
        drop.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));

        JLabel dropLbl = new JLabel("K\u00e9o th\u1ea3 file JSON v\u00e0o \u0111\u00e2y");
        dropLbl.setForeground(C_TEXT3); dropLbl.setFont(F_LABEL);
        JButton btnBrowse = mkOutlineBtn("Duy\u1ec7t t\u00ecm");
        btnBrowse.addActionListener(e -> browseJson());
        drop.add(dropLbl, BorderLayout.CENTER);
        drop.add(btnBrowse, BorderLayout.EAST);

        new DropTarget(drop, new DropTargetAdapter() {
            @Override public void drop(DropTargetDropEvent e) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    var list = (java.util.List<?>) e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!list.isEmpty()) {
                        File f = (File) list.get(0);
                        if (f.getName().endsWith(".json")) loadJson(f.getAbsolutePath());
                        else err("C\u1ea7n file .json!");
                    }
                } catch (Exception ex) { err(ex.getMessage()); }
            }
        });

        tfJsonPath = mkInput(); tfJsonPath.setEditable(false);
        tfJsonPath.setFont(new Font(F_MONO.getFamily(), Font.PLAIN, 10));

        lblStatus  = mkInfoLbl("\u2014", C_TEXT3);
        lblEmail   = mkInfoLbl("", C_TEXT2);
        lblProject = mkInfoLbl("", C_TEXT2);

        // ── Info panel (no emoji — use reliable Unicode only)
        JPanel infoBox = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(C_BG0);
                g2.fillRoundRect(0,0,getWidth()-1,getHeight()-1,10,10);
                g2.setColor(new Color(C_BORDER.getRed(), C_BORDER.getGreen(), C_BORDER.getBlue(), 160));
                g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,10,10);
                // yellow top highlight
                g2.setColor(new Color(C_YELLOW.getRed(), C_YELLOW.getGreen(), C_YELLOW.getBlue(), 160));
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(14,0,getWidth()-14,0);
                g2.dispose();
            }
        };
        infoBox.setOpaque(false);
        infoBox.setLayout(new BoxLayout(infoBox, BoxLayout.Y_AXIS));
        infoBox.setBorder(BorderFactory.createEmptyBorder(10,14,10,12));
        infoBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        infoBox.setAlignmentX(LEFT_ALIGNMENT);

        // Status dot that updates colour on load
        JLabel statusDot = new JLabel("\u25CF");
        statusDot.setForeground(C_TEXT3);
        statusDot.setFont(new Font(F_SMALL.getFamily(), Font.PLAIN, 14));
        lblStatus.addPropertyChangeListener("text", evt -> {
            String t = lblStatus.getText();
            if (t.startsWith("\u0110\u00e3 t\u1ea3i")) { statusDot.setForeground(C_GREEN);  lblEmail.setForeground(C_GREEN); }
            else if (t.startsWith("L\u1ed7i"))         { statusDot.setForeground(C_RED);    lblEmail.setForeground(C_RED); }
            else                                       { statusDot.setForeground(C_TEXT3); lblEmail.setForeground(C_TEXT2); }
        });

        infoBox.add(mkInfoRow(statusDot, lblStatus));
        infoBox.add(vgap(5));
        infoBox.add(mkInfoRow(mkPfx("@"), lblEmail));
        infoBox.add(vgap(5));
        infoBox.add(mkInfoRow(mkPfx("#"), lblProject));

        // ── Scopes panel ─────────────────────────────────────
        String scopeText = String.join(",\n", Config.SCOPES);
        JPanel scopeBox = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(C_BG0);
                g2.fillRoundRect(0,0,getWidth()-1,getHeight()-1,10,10);
                g2.setColor(new Color(C_ACCENT.getRed(),C_ACCENT.getGreen(),C_ACCENT.getBlue(),80));
                g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,10,10);
                // blue top accent
                g2.setColor(new Color(C_ACCENT.getRed(),C_ACCENT.getGreen(),C_ACCENT.getBlue(),180));
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(14,0,getWidth()-14,0);
                g2.dispose();
            }
        };
        scopeBox.setOpaque(false);
        scopeBox.setLayout(new BorderLayout(6, 0));
        scopeBox.setBorder(BorderFactory.createEmptyBorder(10,14,10,10));
        // No maxHeight — let the content breathe
        scopeBox.setAlignmentX(LEFT_ALIGNMENT);

        JPanel scopeLeft = new JPanel();
        scopeLeft.setLayout(new BoxLayout(scopeLeft, BoxLayout.Y_AXIS));
        scopeLeft.setOpaque(false);

        JLabel scopeTitle = new JLabel("Scopes c\u1ea7n c\u1ea5p (Domain-wide)");
        scopeTitle.setForeground(C_ACCENT);
        scopeTitle.setFont(new Font(F_SMALL.getFamily(), Font.BOLD, 11));
        scopeLeft.add(scopeTitle);
        scopeLeft.add(vgap(5));
        for (String s : Config.SCOPES) {
            // Shorten label: show only the last path segment
            String shortName = s.contains("/") ? "..." + s.substring(s.lastIndexOf('/')) : s;
            JLabel sl = new JLabel(shortName);
            sl.setForeground(C_TEXT2);
            sl.setFont(new Font(F_MONO.getFamily(), Font.PLAIN, 10));
            sl.setToolTipText(s);
            scopeLeft.add(sl);
            scopeLeft.add(vgap(2));
        }

        // Copy button
        JButton btnCopy = new JButton("Copy") {
            boolean hover = false;
            { addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover=true;  repaint(); }
                public void mouseExited (MouseEvent e) { hover=false; repaint(); }
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hover ? new Color(C_ACCENT.getRed(),C_ACCENT.getGreen(),C_ACCENT.getBlue(),40)
                                  : new Color(C_ACCENT.getRed(),C_ACCENT.getGreen(),C_ACCENT.getBlue(),15));
                g2.fillRoundRect(0,0,getWidth(),getHeight(),8,8);
                g2.setColor(hover ? C_ACCENT : new Color(C_ACCENT.getRed(),C_ACCENT.getGreen(),C_ACCENT.getBlue(),120));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,8,8);
                g2.dispose(); super.paintComponent(g);
            }
        };
        btnCopy.setForeground(C_ACCENT);
        btnCopy.setFont(new Font(F_SMALL.getFamily(), Font.BOLD, 10));
        btnCopy.setFocusPainted(false); btnCopy.setBorderPainted(false); btnCopy.setContentAreaFilled(false);
        btnCopy.setBorder(BorderFactory.createEmptyBorder(4,10,4,10));
        btnCopy.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnCopy.setToolTipText("Copy danh s\u00e1ch scope v\u00e0o clipboard");
        btnCopy.addActionListener(e -> {
            java.awt.datatransfer.StringSelection sel =
                new java.awt.datatransfer.StringSelection(String.join(",", Config.SCOPES));
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
            btnCopy.setText("\u2713 Copied!");
            btnCopy.setForeground(C_GREEN);
            javax.swing.Timer t = new javax.swing.Timer(1500, ev -> { btnCopy.setText("Copy"); btnCopy.setForeground(C_ACCENT); });
            t.setRepeats(false); t.start();
        });

        scopeBox.add(scopeLeft, BorderLayout.CENTER);
        scopeBox.add(btnCopy, BorderLayout.EAST);

        // ── Admin email input
        tfAdminEmail = mkInput();
        tfAdminEmail.setText(Config.getAdminEmail());
        tfAdminEmail.setToolTipText("Email của Admin trong Google Workspace dùng để impersonate");

        sec.add(drop);
        sec.add(vgap(8));
        sec.add(mkField("\u0110\u01b0\u1eddng d\u1eabn t\u1ec7p", tfJsonPath));
        sec.add(vgap(10));
        sec.add(infoBox);
        sec.add(vgap(10));
        sec.add(mkField("Email Admin (Workspace)", tfAdminEmail));
        sec.add(vgap(10));
        sec.add(scopeBox);
        return sec;
    }

    private JPanel buildOutputSection() {
        JPanel sec = mkSection("Th\u01b0 M\u1ee5c Xu\u1ea5t", C_ORANGE);

        tfOutputDir = mkInput();
        tfOutputDir.setText(Config.getOutputDirectory());
        tfOutputDir.setEditable(false);
        tfOutputDir.setFont(new Font(F_MONO.getFamily(), Font.PLAIN, 10));
        tfOutputDir.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        JButton btnBrowseOut = mkOutlineBtn("Ch\u1ecdn");
        btnBrowseOut.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            String cur = tfOutputDir.getText().trim();
            if (!cur.isBlank()) fc.setCurrentDirectory(new java.io.File(cur));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                tfOutputDir.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        JPanel dirRow = new JPanel(new BorderLayout(4, 0));
        dirRow.setOpaque(false);
        dirRow.setAlignmentX(LEFT_ALIGNMENT);
        dirRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        dirRow.add(tfOutputDir, BorderLayout.CENTER);
        dirRow.add(btnBrowseOut, BorderLayout.EAST);

        JLabel hint = new JLabel("  File Excel s\u1ebd \u0111\u01b0\u1ee3c l\u01b0u v\u00e0o th\u01b0 m\u1ee5c n\u00e0y");
        hint.setForeground(C_TEXT3); hint.setFont(new Font(F_SMALL.getFamily(), Font.ITALIC, 10));
        hint.setAlignmentX(LEFT_ALIGNMENT);

        sec.add(mkField("\u0110\u01b0\u1eddng d\u1eabn", dirRow));
        sec.add(vgap(5));
        sec.add(hint);
        return sec;
    }

    private JPanel mkInfoRow(JLabel prefix, JLabel val) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        p.setOpaque(false); p.setAlignmentX(LEFT_ALIGNMENT);
        p.add(prefix); p.add(val);
        return p;
    }
    private JLabel mkPfx(String t) {
        JLabel l = new JLabel(t); l.setForeground(C_TEXT3); l.setFont(F_SMALL); return l;
    }

    // Output & Admin section removed — values auto-populated from Config defaults

    private JPanel buildFilterSection() {
        JPanel sec = mkSection("Bộ Lọc Thời Gian", C_PURPLE);

        spDays = new JSpinner(new SpinnerNumberModel(0,0,9999,1));
        JSpinner.NumberEditor ed = new JSpinner.NumberEditor(spDays, "#");
        spDays.setEditor(ed);
        ed.getTextField().setBackground(C_BG3);
        ed.getTextField().setForeground(C_TEXT);
        ed.getTextField().setFont(F_INPUT);
        ed.getTextField().setBorder(BorderFactory.createEmptyBorder(3,6,3,6));
        spDays.setBorder(BorderFactory.createLineBorder(C_BORDER));
        spDays.setPreferredSize(new Dimension(72, 28));

        JLabel hintD = new JLabel("  ngày  (0 = không giới hạn)");
        hintD.setForeground(C_TEXT3); hintD.setFont(F_SMALL);
        JPanel daysRow = new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));
        daysRow.setOpaque(false);
        daysRow.add(spDays); daysRow.add(hintD);

        tfEndDate = mkInput();
        tfEndDate.setText(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        tfEndDate.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        // Calendar picker button
        JButton btnCal = new JButton("\uD83D\uDCC5") {
            boolean hover = false;
            { addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover=true;  repaint(); }
                public void mouseExited (MouseEvent e) { hover=false; repaint(); }
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hover ? new Color(0xBC8CFF22, true) : new Color(0x1C2128));
                g2.fillRoundRect(0,0,getWidth(),getHeight(),8,8);
                g2.setColor(hover ? C_PURPLE : C_BORDER2);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,8,8);
                g2.dispose(); super.paintComponent(g);
            }
        };
        btnCal.setForeground(C_PURPLE);
        btnCal.setFont(new Font(F_LABEL.getFamily(), Font.PLAIN, 14));
        btnCal.setFocusPainted(false); btnCal.setBorderPainted(false); btnCal.setContentAreaFilled(false);
        btnCal.setBorder(BorderFactory.createEmptyBorder(3,8,3,8));
        btnCal.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnCal.setPreferredSize(new Dimension(34, 32));
        btnCal.setToolTipText("Chọn ngày từ lịch");
        btnCal.addActionListener(e -> showCalendarPopup(btnCal));

        JPanel dateRow = new JPanel(new BorderLayout(4, 0));
        dateRow.setOpaque(false);
        dateRow.setAlignmentX(LEFT_ALIGNMENT);
        dateRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        dateRow.add(tfEndDate, BorderLayout.CENTER);
        dateRow.add(btnCal, BorderLayout.EAST);

        sec.add(mkField("Từ N ngày trước", daysRow)); sec.add(vgap(8));
        sec.add(mkField("Đến ngày (yyyy-MM-dd)", dateRow)); sec.add(vgap(4));
        JLabel h2 = new JLabel("  Để trống = đọc đến hiện tại");
        h2.setForeground(C_TEXT3); h2.setFont(F_SMALL); h2.setAlignmentX(LEFT_ALIGNMENT);
        sec.add(h2);
        return sec;
    }

    private JPanel buildOptionsSection() {
        JPanel sec = mkSection("T\u00f9y Ch\u1ecdn Kh\u00f4i Ph\u1ee5c", C_GREEN);
        cbFolders = mkCheckBox("Move FOLDERS b\u1ecb thi\u1ebfu (t\u00ecm & move v\u1ec1 \u0111\u00fang v\u1ecb tr\u00ed)");
        cbFolders.setSelected(true);
        cbFiles   = mkCheckBox("Move FILES b\u1ecb thi\u1ebfu (t\u00ecm & move v\u1ec1 \u0111\u00fang v\u1ecb tr\u00ed)");
        cbFiles.setSelected(true);

        JLabel note = new JLabel("  Kh\u00f4ng t\u00ecm th\u1ea5y \u2192 b\u00e1o \"Kh\u00f4ng t\u00ecm th\u1ea5y\", kh\u00f4ng t\u1ea1o m\u1edbi");
        note.setForeground(C_TEXT3);
        note.setFont(new Font(F_SMALL.getFamily(), Font.ITALIC, 10));
        note.setAlignmentX(LEFT_ALIGNMENT);

        sec.add(cbFolders);
        sec.add(vgap(6));
        sec.add(cbFiles);
        sec.add(vgap(6));
        sec.add(note);
        return sec;
    }

    // ══════════════════════════════════════════════════════
    // CALENDAR POPUP
    // ══════════════════════════════════════════════════════

    private void showCalendarPopup(JComponent anchor) {
        // Toggle off if already open
        if (calWindow != null && calWindow.isVisible()) {
            calWindow.dispose(); calWindow = null; return;
        }
        String cur = tfEndDate.getText().trim();
        LocalDate init;
        try { init = LocalDate.parse(cur, DateTimeFormatter.ofPattern("yyyy-MM-dd")); }
        catch (Exception ex) { init = LocalDate.now(); }

        Window owner = SwingUtilities.getWindowAncestor(anchor);
        JWindow win = new JWindow(owner);
        calWindow = win;
        try { win.setBackground(new Color(0,0,0,0)); } catch (Exception ignored) {}

        // Rounded outer shell (draws shadow + dark card)
        JPanel shell = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // drop shadow
                g2.setColor(new Color(0,0,0,90));
                g2.fillRoundRect(5,5,getWidth()-5,getHeight()-5,16,16);
                // card
                g2.setColor(C_BG2);
                g2.fillRoundRect(0,0,getWidth()-6,getHeight()-6,14,14);
                // border
                g2.setColor(C_BORDER2);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0,0,getWidth()-6,getHeight()-6,14,14);
                // purple top accent
                g2.setColor(new Color(C_PURPLE.getRed(),C_PURPLE.getGreen(),C_PURPLE.getBlue(),180));
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(16,0,getWidth()-22,0);
                g2.dispose();
            }
        };
        shell.setOpaque(false);
        shell.setBorder(BorderFactory.createEmptyBorder(10,10,16,16)); // room for shadow

        JPanel calInner = buildCalPanel(init, selected -> {
            tfEndDate.setText(selected.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            win.dispose(); calWindow = null;
        });
        shell.add(calInner);
        win.setContentPane(shell);
        win.pack();

        // Position below the anchor button
        try {
            Point loc = anchor.getLocationOnScreen();
            int x = loc.x - win.getWidth() + anchor.getWidth();
            int y = loc.y + anchor.getHeight() + 4;
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            if (x < 0) x = 0;
            if (x + win.getWidth() > screen.width) x = screen.width - win.getWidth();
            if (y + win.getHeight() > screen.height) y = loc.y - win.getHeight() - 2;
            win.setLocation(x, y);
        } catch (Exception e) { win.setLocationRelativeTo(anchor); }

        win.setVisible(true);

        // Dismiss on click outside
        final AWTEventListener[] listenerRef = {null};
        listenerRef[0] = event -> {
            if (event instanceof MouseEvent me && me.getID() == MouseEvent.MOUSE_PRESSED) {
                if (calWindow != null && !calWindow.getBounds().contains(me.getLocationOnScreen())) {
                    calWindow.dispose(); calWindow = null;
                    Toolkit.getDefaultToolkit().removeAWTEventListener(listenerRef[0]);
                }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listenerRef[0], AWTEvent.MOUSE_EVENT_MASK);
    }

    @FunctionalInterface interface DateCallback { void onSelect(LocalDate d); }

    private JPanel buildCalPanel(LocalDate initDate, DateCallback cb) {
        final LocalDate[] cursor  = { YearMonth.from(initDate).atDay(1) };
        final LocalDate[] selDate = { initDate };
        final JPanel[]    holder  = { null };

        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setOpaque(false);
        root.setPreferredSize(new Dimension(248, 236));

        // ── Nav bar
        JPanel nav = new JPanel(new BorderLayout(0, 0));
        nav.setOpaque(false);
        nav.setBorder(BorderFactory.createEmptyBorder(0,0,4,0));

        JButton prev = mkCalNavBtn("\u25C4");
        JButton next = mkCalNavBtn("\u25BA");
        JLabel monthLbl = new JLabel("", SwingConstants.CENTER);
        monthLbl.setForeground(C_TEXT);
        monthLbl.setFont(new Font(F_TITLE.getFamily(), Font.BOLD, 13));

        holder[0] = renderCalGrid(cursor[0], selDate[0], d -> { selDate[0]=d; cb.onSelect(d); });

        Runnable refresh = () -> {
            monthLbl.setText(cursor[0].getMonth().getDisplayName(
                    java.time.format.TextStyle.FULL, new Locale("vi","VN")) + " " + cursor[0].getYear());
            root.remove(holder[0]);
            holder[0] = renderCalGrid(cursor[0], selDate[0], d -> { selDate[0]=d; cb.onSelect(d); });
            root.add(holder[0], BorderLayout.CENTER);
            root.revalidate(); root.repaint();
        };
        prev.addActionListener(e -> { cursor[0] = cursor[0].minusMonths(1); refresh.run(); });
        next.addActionListener(e -> { cursor[0] = cursor[0].plusMonths(1); refresh.run(); });
        monthLbl.setText(cursor[0].getMonth().getDisplayName(
                java.time.format.TextStyle.FULL, new Locale("vi","VN")) + " " + cursor[0].getYear());

        nav.add(prev, BorderLayout.WEST);
        nav.add(monthLbl, BorderLayout.CENTER);
        nav.add(next, BorderLayout.EAST);

        root.add(nav, BorderLayout.NORTH);
        root.add(holder[0], BorderLayout.CENTER);
        return root;
    }

    private JPanel renderCalGrid(LocalDate monthStart, LocalDate selected, DateCallback cb) {
        JPanel grid = new JPanel(new GridLayout(7, 7, 3, 2));
        grid.setOpaque(false);
        // Day-of-week headers: Sun first
        String[] dow = {"CN","T2","T3","T4","T5","T6","T7"};
        for (String d : dow) {
            JLabel h = new JLabel(d, SwingConstants.CENTER);
            h.setForeground(C_TEXT3);
            h.setFont(new Font(F_SMALL.getFamily(), Font.BOLD, 9));
            grid.add(h);
        }
        int firstDow = monthStart.getDayOfWeek().getValue() % 7; // Mon=1..Sun=7 -> Sun=0
        for (int i = 0; i < firstDow; i++) grid.add(new JLabel());
        int days = monthStart.getMonth().length(monthStart.isLeapYear());
        LocalDate today = LocalDate.now();
        for (int d = 1; d <= days; d++) {
            LocalDate date = monthStart.withDayOfMonth(d);
            boolean isSel   = date.equals(selected);
            boolean isToday = date.equals(today);
            JButton btn = new JButton(String.valueOf(d)) {
                boolean hover = false;
                { addMouseListener(new MouseAdapter() {
                    public void mouseEntered(MouseEvent e) { hover=true;  repaint(); }
                    public void mouseExited (MouseEvent e) { hover=false; repaint(); }
                }); }
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int s = Math.min(getWidth(), getHeight()) - 4;
                    int ox = (getWidth()-s)/2, oy = (getHeight()-s)/2;
                    if (isSel) {
                        g2.setColor(C_PURPLE);
                        g2.fillRoundRect(ox, oy, s, s, s, s);
                    } else if (isToday) {
                        g2.setColor(new Color(C_PURPLE.getRed(),C_PURPLE.getGreen(),C_PURPLE.getBlue(),30));
                        g2.fillRoundRect(ox, oy, s, s, s, s);
                        g2.setColor(C_PURPLE);
                        g2.setStroke(new BasicStroke(1.2f));
                        g2.drawRoundRect(ox, oy, s, s, s, s);
                    } else if (hover) {
                        g2.setColor(C_BG3);
                        g2.fillRoundRect(ox, oy, s, s, s, s);
                    }
                    g2.dispose(); super.paintComponent(g);
                }
            };
            btn.setForeground(isSel ? Color.WHITE : isToday ? C_PURPLE : C_TEXT);
            btn.setFont(new Font(F_SMALL.getFamily(), isSel ? Font.BOLD : Font.PLAIN, 11));
            btn.setFocusPainted(false); btn.setBorderPainted(false); btn.setContentAreaFilled(false);
            btn.setBorder(BorderFactory.createEmptyBorder());
            btn.setHorizontalAlignment(SwingConstants.CENTER);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            final LocalDate fd = date;
            btn.addActionListener(e -> cb.onSelect(fd));
            grid.add(btn);
        }
        return grid;
    }

    private JButton mkCalNavBtn(String text) {
        JButton b = new JButton(text) {
            boolean hover = false;
            { addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover=true;  repaint(); }
                public void mouseExited (MouseEvent e) { hover=false; repaint(); }
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (hover) {
                    g2.setColor(C_BG3);
                    g2.fillRoundRect(0,0,getWidth(),getHeight(),8,8);
                }
                g2.dispose(); super.paintComponent(g);
            }
        };
        b.setForeground(C_TEXT2);
        b.setFont(new Font(F_LABEL.getFamily(), Font.BOLD, 12));
        b.setFocusPainted(false); b.setBorderPainted(false); b.setContentAreaFilled(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(BorderFactory.createEmptyBorder(3,12,3,12));
        b.setPreferredSize(new Dimension(36, 26));
        return b;
    }

    // ══════════════════════════════════════════════════════
    // JSON LOADING
    // ══════════════════════════════════════════════════════

    private void browseJson() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON","json"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            loadJson(fc.getSelectedFile().getAbsolutePath());
    }

    private void loadJson(String path) {
        try {
            tfJsonPath.setText(path);
            String txt = Files.readString(new File(path).toPath(), StandardCharsets.UTF_8);
            JsonObject o = new Gson().fromJson(txt, JsonObject.class);
            String email = o.has("client_email") ? o.get("client_email").getAsString() : "";
            String proj  = o.has("project_id")   ? o.get("project_id").getAsString()   : "";
            lblStatus.setText("Đã tải thành công"); lblStatus.setForeground(C_GREEN);
            lblEmail.setText(email.isEmpty() ? "(không tìm thấy)" : email);
            lblProject.setText(proj);
            // domain KHÔNG lấy từ service account email — phải lấy từ admin email (tfAdminEmail)
        } catch (Exception e) {
            lblStatus.setText("Lỗi: " + e.getMessage());
            lblStatus.setForeground(C_RED);
        }
    }

    // ══════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════

    public AppConfig buildAppConfig() {
        AppConfig cfg = new AppConfig();
        cfg.useJsonFile            = !tfJsonPath.getText().isBlank();
        cfg.serviceAccountJsonPath = tfJsonPath.getText().trim();
        String adminEm = tfAdminEmail.getText().trim();
        cfg.adminEmail = adminEm;
        // Luôn lấy domain từ email admin người dùng nhập (vd: admin@company.com → company.com)
        if (adminEm.contains("@")) {
            cfg.domain = adminEm.split("@")[1];
        } else {
            cfg.domain = Config.getDomain();
        }
        // Output directory: use picker value, fall back to Config default
        String outDir = tfOutputDir != null ? tfOutputDir.getText().trim() : "";
        cfg.outputDirectory        = outDir.isBlank() ? Config.getOutputDirectory() : outDir;
        cfg.activityDays           = (int)(Integer) spDays.getValue();
        cfg.activityEndDate        = tfEndDate.getText().trim();
        cfg.searchFolders          = cbFolders.isSelected();
        cfg.searchFiles            = cbFiles.isSelected();
        return cfg;
    }

    // ══════════════════════════════════════════════════════
    // WIDGET BUILDERS
    // ══════════════════════════════════════════════════════

    /** Card section with colored left accent bar */
    private JPanel mkSection(String title, Color accent) {
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Card background
                g2.setColor(C_BG2);
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14);
                // Top gradient glow
                g2.setPaint(new GradientPaint(0, 0,
                        new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 25),
                        0, 50, new Color(0, 0, 0, 0)));
                g2.fillRoundRect(0, 0, getWidth()-1, 50, 14, 14);
                // Border
                g2.setColor(new Color(C_BORDER.getRed(), C_BORDER.getGreen(), C_BORDER.getBlue(), 180));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14);
                // Left accent bar (thicker)
                g2.setColor(accent);
                g2.fillRoundRect(0, 12, 4, getHeight()-24, 4, 4);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        p.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 14));

        JLabel lbl = new JLabel(title);
        lbl.setForeground(accent.brighter());
        lbl.setFont(new Font(F_TITLE.getFamily(), Font.BOLD, F_TITLE.getSize()));
        lbl.setAlignmentX(LEFT_ALIGNMENT);
        p.add(lbl);
        p.add(vgap(12));
        return p;
    }

    static JPanel mkHeader(String text) {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0,0, new Color(0x1C2128), getWidth(),0, C_BG1));
                g2.fillRect(0,0,getWidth(),getHeight());
                g2.dispose(); super.paintComponent(g);
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0,0,1,0, C_BORDER),
                BorderFactory.createEmptyBorder(12,16,12,14)));
        JLabel l = new JLabel(text);
        l.setForeground(C_ACCENT);
        l.setFont(new Font(F_TITLE.getFamily(), Font.BOLD, 12));
        p.add(l, BorderLayout.WEST);
        return p;
    }

    private JPanel mkField(String label, JComponent input) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel lbl = new JLabel(label);
        lbl.setForeground(C_ACCENT2);
        lbl.setFont(new Font(F_LABEL.getFamily(), Font.BOLD, 11));
        p.add(lbl, BorderLayout.NORTH);
        if (!(input instanceof JPanel)) {
            input.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        }
        p.add(input, BorderLayout.CENTER);
        return p;
    }

    private JPanel mkStatusRow(String label, JLabel val) {
        JPanel p = new JPanel(new BorderLayout(6,0));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        JLabel l = new JLabel(label + ": ");
        l.setForeground(C_TEXT3); l.setFont(F_SMALL);
        l.setPreferredSize(new Dimension(82, 16));
        val.setFont(new Font(F_MONO.getFamily(), Font.PLAIN, 10));
        p.add(l, BorderLayout.WEST);
        p.add(val, BorderLayout.CENTER);
        return p;
    }

    static JTextField mkInput() {
        JTextField tf = new JTextField() {
            boolean focused = false;
            { addFocusListener(new FocusAdapter() {
                public void focusGained(FocusEvent e) { focused = true;  repaint(); }
                public void focusLost (FocusEvent e)  { focused = false; repaint(); }
            }); }
            @Override protected void paintBorder(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (focused) {
                    g2.setColor(new Color(0x58A6FF33, true));
                    g2.setStroke(new BasicStroke(3.5f));
                    g2.drawRoundRect(1,1,getWidth()-3,getHeight()-3,9,9);
                }
                g2.setColor(focused ? C_ACCENT : C_BORDER2);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,8,8);
                g2.dispose();
            }
        };
        tf.setBackground(new Color(0x1C2128)); tf.setForeground(C_TEXT); tf.setCaretColor(C_ACCENT);
        tf.setFont(F_INPUT); tf.setOpaque(true);
        tf.setBorder(BorderFactory.createEmptyBorder(6,10,6,10));
        return tf;
    }

    static JButton mkOutlineBtn(String text) {
        JButton b = new JButton(text) {
            boolean hover = false;
            { addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover=true;  repaint(); }
                public void mouseExited (MouseEvent e) { hover=false; repaint(); }
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hover ? new Color(0x58A6FF1A, true) : new Color(0x1A1F27));
                g2.fillRoundRect(0,0,getWidth(),getHeight(),10,10);
                g2.setColor(hover ? C_ACCENT : C_BORDER2);
                g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,10,10);
                g2.dispose(); super.paintComponent(g);
            }
        };
        b.setForeground(C_ACCENT); b.setFont(new Font(F_LABEL.getFamily(), Font.PLAIN, 11));
        b.setFocusPainted(false); b.setBorderPainted(false); b.setContentAreaFilled(false);
        b.setBorder(BorderFactory.createEmptyBorder(5,14,5,14));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    static JButton mkGhostBtn(String text) {
        JButton b = new JButton(text) {
            boolean hover = false;
            { addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover=true;  repaint(); }
                public void mouseExited (MouseEvent e) { hover=false; repaint(); }
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hover ? C_BG3 : C_BG2);
                g2.fillRoundRect(0,0,getWidth(),getHeight(),10,10);
                g2.setColor(hover ? C_BORDER2 : C_BORDER);
                g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,10,10);
                g2.dispose(); super.paintComponent(g);
            }
        };
        b.setForeground(C_TEXT2); b.setFont(new Font(F_LABEL.getFamily(), Font.PLAIN, 11));
        b.setFocusPainted(false); b.setBorderPainted(false); b.setContentAreaFilled(false);
        b.setBorder(BorderFactory.createEmptyBorder(5,12,5,12));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private JButton mkIconBtn(String icon) {
        JButton b = mkGhostBtn(icon);
        b.setPreferredSize(new Dimension(32,30));
        return b;
    }

    private JCheckBox mkCheckBox(String text) {
        JCheckBox cb = new JCheckBox(text);
        cb.setOpaque(false); cb.setForeground(C_TEXT);
        cb.setFont(new Font(F_LABEL.getFamily(), Font.PLAIN, 12));
        cb.setFocusPainted(false); cb.setAlignmentX(LEFT_ALIGNMENT);
        cb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return cb;
    }

    private JLabel mkInfoLbl(String text, Color c) {
        JLabel l = new JLabel(text);
        l.setForeground(c);
        l.setFont(new Font(F_MONO.getFamily(), Font.PLAIN, 11));
        return l;
    }

    static Component vgap(int h) { return Box.createVerticalStrut(h); }
    private void err(String m) { JOptionPane.showMessageDialog(this, m, "Lỗi", JOptionPane.ERROR_MESSAGE); }

    // ══════════════════════════════════════════════════════
    // SCROLLABLE BODY PANEL
    // getScrollableTracksViewportWidth()=true is the key:
    // it tells JScrollPane to constrain child width = viewport width,
    // so BoxLayout Y_AXIS never overflows sideways and content is never clipped.
    // ══════════════════════════════════════════════════════
    private static class ScrollablePanel extends JPanel implements javax.swing.Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d)  { return 14; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 100; }
        @Override public boolean getScrollableTracksViewportWidth()  { return true; }  // ★ key
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
