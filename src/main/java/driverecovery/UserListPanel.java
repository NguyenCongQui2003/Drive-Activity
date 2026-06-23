package driverecovery;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.directory.Directory;
import com.google.api.services.directory.model.User;
import com.google.api.services.directory.model.Users;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.ServiceAccountCredentials;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

import static driverecovery.ConfigPanel.*;

/**
 * ⭐ USER LIST PANEL — Top section of the right area
 */
public class UserListPanel extends JPanel {

    private JTabbedPane tabs;

    // SDK tab
    private DefaultTableModel sdkModel;
    private JTable            sdkTable;
    private JLabel            lblSdkCount;
    private JButton           btnFetch;
    private JProgressBar      sdkBar;
    private JTextField        tfSdkSearch;

    // CSV tab
    private DefaultTableModel csvModel;
    private JTable            csvTable;
    private JLabel            lblCsvCount;
    private JTextField        tfCsvPath;

    // Manual tab
    private JTextArea taManual;

    private List<String>       allForSearch = new ArrayList<>();
    private JLabel             lblSelected;
    private Supplier<AppConfig> cfgSupplier;

    public UserListPanel() {
        setBackground(C_BG0);
        setLayout(new BorderLayout());
        add(buildHeader(), BorderLayout.NORTH);
        add(buildTabs(),   BorderLayout.CENTER);
    }

    // ══════════════════════════════════════════════════════
    // HEADER
    // ══════════════════════════════════════════════════════

    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout(0, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                // Richer gradient: almost-black left → dark blue-grey right
                g2.setPaint(new GradientPaint(0, 0, new Color(0x0D1117), getWidth(), 0, new Color(0x161B22)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                // Subtle top shimmer line
                g2.setPaint(new GradientPaint(0, 0, new Color(0x58A6FF30, true),
                        getWidth(), 0, new Color(0x00000000, true)));
                g2.fillRect(0, 0, getWidth(), 1);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
                BorderFactory.createEmptyBorder(11, 16, 11, 14)));

        // Left: accent dot + title
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);

        JLabel dot = new JLabel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(C_ACCENT);
                g2.fillOval(0, 3, 7, 7);
                // Glow
                g2.setColor(new Color(C_ACCENT.getRed(), C_ACCENT.getGreen(), C_ACCENT.getBlue(), 40));
                g2.fillOval(-2, 1, 11, 11);
                g2.dispose();
            }
        };
        dot.setPreferredSize(new Dimension(10, 14));
        dot.setOpaque(false);

        JLabel title = new JLabel("DANH S\u00c1CH NG\u01af\u1edaI D\u00d9NG");
        title.setForeground(C_TEXT);
        title.setFont(new Font(F_TITLE.getFamily(), Font.BOLD, 12));

        left.add(dot);
        left.add(title);

        // Right: count pill badge
        lblSelected = new JLabel("0 ng\u01b0\u1eddi d\u00f9ng") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color accent = getForeground();
                g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 22));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 70));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        lblSelected.setForeground(C_TEXT3);
        lblSelected.setFont(new Font(F_LABEL.getFamily(), Font.PLAIN, 11));
        lblSelected.setBorder(BorderFactory.createEmptyBorder(3, 12, 3, 12));
        lblSelected.setOpaque(false);

        p.add(left,        BorderLayout.WEST);
        p.add(lblSelected, BorderLayout.EAST);
        return p;
    }

    // ══════════════════════════════════════════════════════
    // TABS
    // ══════════════════════════════════════════════════════

    private JTabbedPane buildTabs() {
        JTabbedPane tp = new JTabbedPane(JTabbedPane.TOP);
        tp.setBackground(C_BG0);
        tp.setForeground(C_TEXT2);
        tp.setFont(new Font(F_LABEL.getFamily(), Font.PLAIN, 12));
        tp.setBorder(null);
        tp.setFocusable(false);

        tp.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {
            @Override protected void installDefaults() {
                super.installDefaults();
                contentBorderInsets  = new Insets(0, 0, 0, 0);
                tabInsets            = new Insets(8, 18, 8, 18);
                selectedTabPadInsets = new Insets(0, 0, 0, 0);
                tabAreaInsets        = new Insets(0, 0, 0, 0);
            }
            @Override protected void paintTabArea(Graphics g, int tp, int si) {
                g.setColor(C_BG2);
                g.fillRect(0, 0, tabPane.getWidth(), maxTabHeight + 4);
                // Bottom separator line
                g.setColor(C_BORDER);
                g.drawLine(0, maxTabHeight + 3, tabPane.getWidth(), maxTabHeight + 3);
                super.paintTabArea(g, tp, si);
            }
            @Override protected void paintTab(Graphics g, int tp, Rectangle[] rects, int ti, Rectangle ir, Rectangle tr) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Rectangle r  = rects[ti];
                boolean   sel = ti == tabPane.getSelectedIndex();
                boolean   hov = false; // hover tracking below

                g2.setColor(sel ? C_BG0 : C_BG2);
                g2.fillRect(r.x, r.y, r.width, r.height);

                // Active tab: blue underline bar
                if (sel) {
                    g2.setColor(C_ACCENT);
                    g2.fillRoundRect(r.x + 8, r.y + r.height - 3, r.width - 16, 3, 3, 3);
                }
                g2.dispose();
                super.paintTab(g, tp, rects, ti, ir, tr);
            }
            @Override protected void paintFocusIndicator(Graphics g, int tp, Rectangle[] r, int i, Rectangle ir, Rectangle tr, boolean sel) {}
            @Override protected void paintContentBorder(Graphics g, int tp, int si) {}
            @Override protected int calculateTabAreaHeight(int tp, int runCount, int maxTabHeight) {
                return maxTabHeight + 4;
            }
        });

        tp.addTab("Admin SDK", buildSdkTab());
        tp.addTab("CSV",       buildCsvTab());
        tp.addTab("Nhập tay",  buildManualTab());

        for (int i = 0; i < tp.getTabCount(); i++) {
            tp.setBackgroundAt(i, C_BG2);
            tp.setForegroundAt(i, C_TEXT2);
        }
        this.tabs = tp;
        return tp;
    }

    // ── SDK Tab ───────────────────────────────────────────

    private JPanel buildSdkTab() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_BG0);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 8));
        toolbar.setBackground(new Color(0x161B22));
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
                BorderFactory.createEmptyBorder(0, 4, 0, 4)));

        btnFetch = mkOutlineBtn("L\u1ea5y t\u1eeb Admin SDK");
        btnFetch.addActionListener(e -> fetchUsers());
        toolbar.add(btnFetch);
        toolbar.add(mkVSep());

        JButton btnAll  = mkGhostBtn("\u2713  T\u1ea5t c\u1ea3");
        JButton btnNone = mkGhostBtn("\u25a1  B\u1ecf ch\u1ecdn");
        btnAll.addActionListener(e  -> checkAll(sdkModel, true));
        btnNone.addActionListener(e -> checkAll(sdkModel, false));
        toolbar.add(btnAll);
        toolbar.add(btnNone);

        sdkBar = new JProgressBar();
        sdkBar.setIndeterminate(true); sdkBar.setVisible(false);
        sdkBar.setForeground(C_ACCENT); sdkBar.setBackground(C_BG3);
        sdkBar.setBorderPainted(false); sdkBar.setPreferredSize(new Dimension(90, 5));
        toolbar.add(sdkBar);

        lblSdkCount = mkCountPill("0 ng\u01b0\u1eddi d\u00f9ng");
        toolbar.add(lblSdkCount);

        // Search bar
        JPanel searchRow = new JPanel(new BorderLayout(6, 0));
        searchRow.setBackground(C_BG1);
        searchRow.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));
        JLabel searchIcon = new JLabel("  ");
        searchIcon.setForeground(C_TEXT3);
        tfSdkSearch = mkInput();
        tfSdkSearch.setToolTipText("Lọc theo email hoặc tên người dùng...");
        searchRow.add(searchIcon,  BorderLayout.WEST);
        searchRow.add(tfSdkSearch, BorderLayout.CENTER);

        sdkModel = mkBoolModel("Email", "Tên");
        sdkModel.addTableModelListener(e -> refreshCount());
        sdkTable = mkTable(sdkModel);
        sdkTable.getColumnModel().getColumn(0).setMinWidth(34); sdkTable.getColumnModel().getColumn(0).setMaxWidth(34);
        sdkTable.getColumnModel().getColumn(1).setPreferredWidth(200);

        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(sdkModel);
        sdkTable.setRowSorter(sorter);
        tfSdkSearch.getDocument().addDocumentListener(dl(() -> {
            String t = tfSdkSearch.getText().trim();
            sorter.setRowFilter(t.isEmpty() ? null : RowFilter.regexFilter("(?i)" + t, 1, 2));
        }));

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(C_BG2);
        topBar.add(toolbar,    BorderLayout.NORTH);
        topBar.add(searchRow,  BorderLayout.SOUTH);

        p.add(topBar,           BorderLayout.NORTH);
        p.add(mkScroll(sdkTable), BorderLayout.CENTER);
        return p;
    }

    // ── CSV Tab ───────────────────────────────────────────

    private JPanel buildCsvTab() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_BG0);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 8));
        toolbar.setBackground(new Color(0x161B22));
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
                BorderFactory.createEmptyBorder(0, 4, 0, 4)));

        tfCsvPath = mkInput();
        tfCsvPath.setEditable(false);
        tfCsvPath.setPreferredSize(new Dimension(170, 28));

        JButton btnBrowse   = mkGhostBtn("📂  Mở file");
        JButton btnTemplate = mkGhostBtn("⬇ Mẫu");
        JButton btnLoad     = mkOutlineBtn("📥 Tải");
        JButton btnAll      = mkGhostBtn("✅ Tất cả");
        JButton btnNone     = mkGhostBtn("☐ Bỏ");

        btnBrowse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("CSV/TXT","csv","txt"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                tfCsvPath.setText(fc.getSelectedFile().getAbsolutePath()); loadCsv();
            }
        });
        btnTemplate.addActionListener(e -> saveTemplate());
        btnLoad.addActionListener(e    -> loadCsv());
        btnAll.addActionListener(e     -> checkAll(csvModel, true));
        btnNone.addActionListener(e    -> checkAll(csvModel, false));

        lblCsvCount = mkCountPill("0 người dùng");

        toolbar.add(tfCsvPath); toolbar.add(btnBrowse); toolbar.add(btnTemplate);
        toolbar.add(btnLoad); toolbar.add(mkVSep());
        toolbar.add(btnAll); toolbar.add(btnNone); toolbar.add(lblCsvCount);

        csvModel = mkBoolModel("Email");
        csvModel.addTableModelListener(e -> refreshCount());
        csvTable = mkTable(csvModel);
        csvTable.getColumnModel().getColumn(0).setMinWidth(34); csvTable.getColumnModel().getColumn(0).setMaxWidth(34);

        p.add(toolbar,            BorderLayout.NORTH);
        p.add(mkScroll(csvTable), BorderLayout.CENTER);
        return p;
    }

    // ── Manual Tab ────────────────────────────────────────

    private JPanel buildManualTab() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_BG0);

        JPanel hint = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        hint.setBackground(C_BG2);
        hint.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER));
        JLabel h = new JLabel("Nhập mỗi dòng 1 email — tất cả sẽ được dùng");
        h.setForeground(C_TEXT2); h.setFont(new Font(F_LABEL.getFamily(), Font.PLAIN, 11));
        hint.add(h);

        taManual = new JTextArea();
        taManual.setBackground(new Color(0x0D1117));
        taManual.setForeground(C_TEXT);
        taManual.setCaretColor(C_ACCENT);
        taManual.setFont(new Font(F_MONO.getFamily(), Font.PLAIN, 13));
        taManual.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        taManual.getDocument().addDocumentListener(dl(() -> refreshCount()));

        JScrollPane sp = new JScrollPane(taManual);
        sp.setBorder(null); sp.getViewport().setBackground(new Color(0x0D1117));
        p.add(hint, BorderLayout.NORTH);
        p.add(sp,   BorderLayout.CENTER);
        return p;
    }

    // ══════════════════════════════════════════════════════
    // ACTIONS
    // ══════════════════════════════════════════════════════

    private void fetchUsers() {
        if (cfgSupplier == null) { err("Chưa có cấu hình!"); return; }
        AppConfig cfg = cfgSupplier.get();
        if (cfg.adminEmail.isBlank())             { err("Nhập Email quản trị trước!"); return; }
        if (cfg.serviceAccountJsonPath.isBlank()) { err("Chọn file JSON trước!");  return; }

        btnFetch.setEnabled(false); sdkBar.setVisible(true); lblSdkCount.setText("Đang tải...");
        sdkModel.setRowCount(0);

        new SwingWorker<List<String[]>, Void>() {
            @Override protected List<String[]> doInBackground() throws Exception { return doFetch(cfg); }
            @Override protected void done() {
                sdkBar.setVisible(false); btnFetch.setEnabled(true);
                try {
                    List<String[]> list = get();
                    allForSearch.clear();
                    for (String[] u : list) {
                        sdkModel.addRow(new Object[]{true, u[0], u[1]});
                        allForSearch.add(u[0]);
                    }
                    lblSdkCount.setText(list.size() + " người dùng");
                    refreshCount();
                } catch (Exception e) { err("Tải lỗi: " + e.getMessage()); lblSdkCount.setText("Lỗi!"); }
            }
        }.execute();
    }

    private List<String[]> doFetch(AppConfig cfg) throws Exception {
        var creds = ServiceAccountCredentials
                .fromStream(new FileInputStream(cfg.serviceAccountJsonPath))
                .createScoped(List.of("https://www.googleapis.com/auth/admin.directory.user.readonly"))
                .createDelegated(cfg.adminEmail);
        var dir = new Directory.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(creds))
                .setApplicationName("Drive Recovery Tool v2.0").build();

        List<String[]> result = new ArrayList<>();
        String token = null;
        do {
            // "my_customer" = lấy toàn bộ user của TẤT CẢ domain trong Workspace (tối ưu nhất)
            var req = dir.users().list()
                    .setCustomer("my_customer")
                    .setMaxResults(500)
                    .setOrderBy("email");
            if (token != null) req.setPageToken(token);
            Users resp = req.execute();
            if (resp.getUsers() != null)
                for (User u : resp.getUsers())
                    result.add(new String[]{
                            u.getPrimaryEmail() != null ? u.getPrimaryEmail() : "",
                            u.getName() != null && u.getName().getFullName() != null ? u.getName().getFullName() : ""});
            token = resp.getNextPageToken();
        } while (token != null);
        return result;
    }

    private void loadCsv() {
        String path = tfCsvPath.getText().trim();
        if (path.isBlank()) return;
        try (var br = new BufferedReader(new FileReader(path, StandardCharsets.UTF_8))) {
            csvModel.setRowCount(0); int c = 0; String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("#") && line.contains("@")) {
                    csvModel.addRow(new Object[]{true, line}); c++;
                }
            }
            lblCsvCount.setText(c + " người dùng"); refreshCount();
        } catch (Exception e) { err("Lỗi CSV: " + e.getMessage()); }
    }

    private void saveTemplate() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("users_template.csv"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try (var fw = new FileWriter(fc.getSelectedFile(), StandardCharsets.UTF_8)) {
                fw.write("# Drive Recovery Tool - User list template\n");
                fw.write("# Each line = 1 email address\n\nuser1@domain.com\nuser2@domain.com\n");
                JOptionPane.showMessageDialog(this, "✅ Đã tạo!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) { err(e.getMessage()); }
        }
    }

    // ══════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════

    public List<String> getSelectedUsers() {
        List<String> r = new ArrayList<>();
        if (tabs == null) return r;
        int t = tabs.getSelectedIndex();
        if (t == 0) {
            for (int i = 0; i < sdkModel.getRowCount(); i++)
                if (Boolean.TRUE.equals(sdkModel.getValueAt(i, 0))) r.add((String) sdkModel.getValueAt(i, 1));
        } else if (t == 1) {
            for (int i = 0; i < csvModel.getRowCount(); i++)
                if (Boolean.TRUE.equals(csvModel.getValueAt(i, 0))) r.add((String) csvModel.getValueAt(i, 1));
        } else if (taManual != null) {
            for (String line : taManual.getText().split("\n")) {
                line = line.trim(); if (line.contains("@")) r.add(line);
            }
        }
        return r;
    }

    public List<String> getAllUsersForSearch() { return allForSearch.isEmpty() ? getSelectedUsers() : allForSearch; }
    public void setConfigSupplier(Supplier<AppConfig> s) { cfgSupplier = s; }

    /** Trả về TẤT CẢ email đang có trong mọi tab (kể cả không tích) — dùng cho Mode 2 search list */
    public List<String> getAllLoadedUsers() {
        // Ưu tiên 1: allForSearch (đã fetch từ Admin SDK) — đây là danh sách toàn tổ chức
        if (!allForSearch.isEmpty()) return new ArrayList<>(allForSearch);

        // Ưu tiên 2: gom từ TẤT CẢ tabs (không phụ thuộc tab đang chọn)
        List<String> r = new ArrayList<>();
        // SDK table
        for (int i = 0; i < sdkModel.getRowCount(); i++) {
            String email = (String) sdkModel.getValueAt(i, 1);
            if (email != null && !email.isBlank() && !r.contains(email)) r.add(email);
        }
        // CSV table
        for (int i = 0; i < csvModel.getRowCount(); i++) {
            String email = (String) csvModel.getValueAt(i, 1);
            if (email != null && !email.isBlank() && !r.contains(email)) r.add(email);
        }
        // Manual textarea
        if (taManual != null) {
            for (String line : taManual.getText().split("\n")) {
                line = line.trim();
                if (line.contains("@") && !r.contains(line)) r.add(line);
            }
        }
        return r;
    }

    public void loadDefaultUsers(List<String> users) {
        csvModel.setRowCount(0);
        for (String u : users) csvModel.addRow(new Object[]{true, u});
        lblCsvCount.setText(users.size() + " người dùng");
        tabs.setSelectedIndex(1);
        refreshCount();
    }

    // ══════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════

    private void checkAll(DefaultTableModel m, boolean v) {
        for (int i = 0; i < m.getRowCount(); i++) m.setValueAt(v, i, 0);
        refreshCount();
    }

    private void refreshCount() {
        int n = getSelectedUsers().size();
        SwingUtilities.invokeLater(() -> {
            if (n > 0) {
                lblSelected.setText("  \u2713  " + n + " ng\u01b0\u1eddi d\u00f9ng  ");
                lblSelected.setForeground(C_GREEN);
            } else {
                lblSelected.setText("  0 ng\u01b0\u1eddi d\u00f9ng  ");
                lblSelected.setForeground(C_TEXT3);
            }
            lblSelected.repaint();
        });
    }

    private DefaultTableModel mkBoolModel(String... cols) {
        String[] headers = new String[cols.length + 1];
        headers[0] = "";
        System.arraycopy(cols, 0, headers, 1, cols.length);
        return new DefaultTableModel(headers, 0) {
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int r, int c) { return c == 0; }
        };
    }

    private JTable mkTable(DefaultTableModel m) {
        JTable t = new JTable(m) {
            @Override public Component prepareRenderer(TableCellRenderer r, int row, int col) {
                Component c = super.prepareRenderer(r, row, col);
                if (!isRowSelected(row)) {
                    c.setBackground(row % 2 == 0 ? C_BG0 : new Color(0x13181F));
                    c.setForeground(C_TEXT);
                } else {
                    c.setBackground(new Color(0x1F3A5F));
                    c.setForeground(C_TEXT);
                }
                return c;
            }
        };
        t.setBackground(C_BG0); t.setForeground(C_TEXT);
        t.setGridColor(new Color(0x1C2128));
        t.setSelectionBackground(new Color(0x1F3A5F));
        t.setSelectionForeground(C_TEXT);
        t.setFont(new Font(F_MONO.getFamily(), Font.PLAIN, 12));
        t.setRowHeight(30);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setFillsViewportHeight(true);

        JTableHeader h = t.getTableHeader();
        h.setBackground(C_BG2); h.setForeground(C_TEXT2);
        h.setFont(new Font(F_TITLE.getFamily(), Font.BOLD, 11));
        h.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER));
        h.setReorderingAllowed(false);
        h.setPreferredSize(new Dimension(0, 32));
        return t;
    }

    private JScrollPane mkScroll(JTable t) {
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(null); sp.setBackground(C_BG0);
        sp.getViewport().setBackground(C_BG0);
        sp.getVerticalScrollBar().setUnitIncrement(12);
        return sp;
    }

    /** Small count label styled as a pill */
    private JLabel mkCountPill(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(C_TEXT3);
        l.setFont(new Font(F_LABEL.getFamily(), Font.PLAIN, 11));
        return l;
    }

    private JSeparator mkVSep() {
        JSeparator s = new JSeparator(JSeparator.VERTICAL);
        s.setForeground(C_BORDER); s.setPreferredSize(new Dimension(1, 18));
        return s;
    }

    private javax.swing.event.DocumentListener dl(Runnable r) {
        return new javax.swing.event.DocumentListener() {
            public void insertUpdate (javax.swing.event.DocumentEvent e) { r.run(); }
            public void removeUpdate (javax.swing.event.DocumentEvent e) { r.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        };
    }

    private void err(String m) { JOptionPane.showMessageDialog(this, m, "Lỗi", JOptionPane.ERROR_MESSAGE); }
}
