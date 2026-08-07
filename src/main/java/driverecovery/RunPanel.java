package driverecovery;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

import static driverecovery.ConfigPanel.*;

/**
 * ⭐ RUN PANEL — Mode picker + progress + log + action buttons
 */
public class RunPanel extends JPanel implements ProgressTracker.ProgressListener {

    private static final Color GREEN_BTN = new Color(0x1A7F37);
    private static final Color GREEN_HOV = new Color(0x2DA44E);
    private static final Color RED_BTN   = new Color(0xB62324);
    private static final Color RED_HOV   = new Color(0xCF222E);

    // Mode radios
    private JRadioButton rb1, rb2, rb3, rb4;
    private JPanel[]     modeCards = new JPanel[4];
    private JPanel       rowFolderId;
    private JTextField   tfFolderId;

    // Progress
    private JLabel       lblStatus, lblCurrentItem, lblUserCnt, lblFolderCnt;
    private JProgressBar barUser, barFolder;

    // Log
    private JTextPane    logPane;
    private StyledDocument logDoc;
    private SimpleAttributeSet sInfo, sOk, sWarn, sErr, sDetail, sHead;

    // Buttons
    private JButton btnRun, btnStop, btnClearLog, btnSaveLog;

    private Runnable onRun, onStop;

    public RunPanel() {
        setBackground(C_BG0);
        setLayout(new BorderLayout());

        initStyles();

        JPanel topArea = new JPanel(new BorderLayout());
        topArea.setBackground(C_BG0);
        topArea.add(buildModeBar(),     BorderLayout.NORTH);
        topArea.add(buildProgressBar(), BorderLayout.SOUTH);
        add(topArea, BorderLayout.NORTH);

        add(buildLog(),       BorderLayout.CENTER);
        add(buildActionBar(), BorderLayout.SOUTH);

        ProgressTracker.getInstance().addListener(this);
    }

    // ══════════════════════════════════════════════════════
    // STYLES
    // ══════════════════════════════════════════════════════

    private void initStyles() {
        sInfo   = sty(C_TEXT,    false);
        sOk     = sty(C_GREEN,   false);
        sWarn   = sty(C_YELLOW,  false);
        sErr    = sty(C_RED,     false);
        sDetail = sty(C_TEXT3,   false);
        sHead   = sty(C_ACCENT2, true);
    }

    private SimpleAttributeSet sty(Color c, boolean bold) {
        var s = new SimpleAttributeSet();
        StyleConstants.setForeground(s, c);
        // F_MONO.getFamily() = Segoe UI → hỗ trợ tiếng Việt + emoji đầy đủ
        StyleConstants.setFontFamily(s, F_MONO.getFamily());
        StyleConstants.setFontSize(s, 13);
        StyleConstants.setBold(s, bold);
        return s;
    }

    // ══════════════════════════════════════════════════════
    // MODE BAR
    // ══════════════════════════════════════════════════════

    private JPanel buildModeBar() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(C_BG1);
        outer.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER));

        // Section header
        JPanel hdr = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, new Color(0x0D1117), getWidth(), 0, new Color(0x161B22)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                // shimmer line at top
                g2.setColor(new Color(0x58A6FF22, true));
                g2.fillRect(0, 0, getWidth(), 1);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        hdr.setOpaque(false);
        hdr.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
                BorderFactory.createEmptyBorder(10, 16, 10, 14)));

        JPanel hdrLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        hdrLeft.setOpaque(false);

        // Purple accent dot for RunPanel
        JLabel dot2 = new JLabel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(C_PURPLE);
                g2.fillOval(0, 3, 7, 7);
                g2.setColor(new Color(C_PURPLE.getRed(), C_PURPLE.getGreen(), C_PURPLE.getBlue(), 40));
                g2.fillOval(-2, 1, 11, 11);
                g2.dispose();
            }
        };
        dot2.setPreferredSize(new Dimension(10, 14));
        dot2.setOpaque(false);

        JLabel t = new JLabel("CH\u1ebeĐỘ CH\u1ea0Y");
        t.setForeground(C_TEXT); t.setFont(new Font(F_TITLE.getFamily(), Font.BOLD, 12));
        hdrLeft.add(dot2); hdrLeft.add(t);
        hdr.add(hdrLeft, BorderLayout.WEST);
        outer.add(hdr, BorderLayout.NORTH);

        // 4 mode cards
        JPanel modesRow = new JPanel(new GridLayout(1, 4, 1, 0));
        modesRow.setBackground(C_BORDER);

        ButtonGroup bg = new ButtonGroup();
        JRadioButton[] rbs = new JRadioButton[4];

        Color[]  badgeColors = {C_ACCENT, C_PURPLE, C_GREEN, C_YELLOW};
        String[] modeNums    = {"1", "2", "3", "4"};
        String[] modeLabels  = {
            "<html><b>Chế độ 1</b><br><font color='#8B949E' size='2'>Khôi phục & di chuyển file</font></html>",
            "<html><b>Chế độ 2</b><br><font color='#8B949E' size='2'>Phân tích 1 thư mục (ID)</font></html>",
            "<html><b>Chế độ 3</b><br><font color='#8B949E' size='2'>Phân tích tất cả thư mục</font></html>",
            "<html><b>Chế độ 4</b><br><font color='#8B949E' size='2'>Phân tích toàn bộ người dùng</font></html>"
        };

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            modeCards[i] = buildModeCard(modeNums[i], modeLabels[i], badgeColors[i], bg, rbs, i);
            modesRow.add(modeCards[i]);
        }
        rb1 = rbs[0]; rb2 = rbs[1]; rb3 = rbs[2]; rb4 = rbs[3];
        rb1.setSelected(true);
        highlightSelectedCard(0);

        // Sync highlight on change
        for (int i = 0; i < rbs.length; i++) {
            final int fi = i;
            rbs[i].addActionListener(e -> highlightSelectedCard(fi));
        }

        outer.add(modesRow, BorderLayout.CENTER);

        // Folder ID row (mode 2)
        rowFolderId = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 7));
        rowFolderId.setBackground(C_BG1);
        rowFolderId.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER));
        JLabel lf = new JLabel("Mã thư mục:");
        lf.setForeground(C_TEXT2); lf.setFont(F_LABEL);
        tfFolderId = mkInput();
        tfFolderId.setPreferredSize(new Dimension(360, 28));
        rowFolderId.add(lf); rowFolderId.add(tfFolderId);
        rowFolderId.setVisible(false);
        outer.add(rowFolderId, BorderLayout.SOUTH);

        rb2.addActionListener(e -> rowFolderId.setVisible(true));
        rb1.addActionListener(e -> rowFolderId.setVisible(false));
        rb3.addActionListener(e -> rowFolderId.setVisible(false));
        rb4.addActionListener(e -> rowFolderId.setVisible(false));

        return outer;
    }

    private void highlightSelectedCard(int selectedIdx) {
        for (int i = 0; i < modeCards.length; i++) {
            final boolean sel = i == selectedIdx;
            JPanel card = modeCards[i];
            card.setBackground(sel ? new Color(0x1C2A3A) : C_BG1);
            // Force repaint with updated selection state
            card.putClientProperty("selected", sel);
            card.repaint();
            for (Component ch : card.getComponents()) {
                ch.setBackground(sel ? new Color(0x1C2A3A) : C_BG1);
            }
        }
    }

    private JPanel buildModeCard(String num, String label, Color badge, ButtonGroup bg, JRadioButton[] out, int idx) {
        JPanel card = new JPanel(new BorderLayout(0, 4)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean sel = Boolean.TRUE.equals(getClientProperty("selected"));
                if (sel) {
                    // Selected: deep tinted bg + top accent bar
                    g2.setPaint(new GradientPaint(0, 0,
                            new Color(badge.getRed()/4, badge.getGreen()/4, badge.getBlue()/4, 200),
                            0, getHeight(), new Color(0x161B22)));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    // Top accent bar (3px)
                    g2.setColor(badge);
                    g2.fillRect(0, 0, getWidth(), 3);
                    // Subtle side glow
                    g2.setPaint(new GradientPaint(0, 0,
                            new Color(badge.getRed(), badge.getGreen(), badge.getBlue(), 35),
                            getWidth()/2, 0, new Color(0, 0, 0, 0)));
                    g2.fillRect(0, 3, getWidth(), getHeight()-3);
                } else {
                    g2.setColor(getBackground());
                    g2.fillRect(0, 0, getWidth(), getHeight());
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        card.setBackground(C_BG1);
        card.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Badge circle
        JLabel badgeLbl = new JLabel(num, SwingConstants.CENTER) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(badge);
                g2.fillOval(0, 0, 28, 28);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        badgeLbl.setForeground(new Color(0x0D1117));
        badgeLbl.setFont(new Font(F_TITLE.getFamily(), Font.BOLD, 12));
        badgeLbl.setOpaque(false);
        badgeLbl.setPreferredSize(new Dimension(30, 30));

        JRadioButton rb = new JRadioButton();
        rb.setOpaque(false); rb.setFocusPainted(false);
        bg.add(rb); out[idx] = rb;

        JLabel text = new JLabel(label);
        text.setForeground(C_TEXT2);
        text.setFont(new Font(F_LABEL.getFamily(), Font.PLAIN, 11));

        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        topRow.setOpaque(false);
        topRow.add(badgeLbl); topRow.add(rb);

        card.add(topRow, BorderLayout.NORTH);
        card.add(text,   BorderLayout.CENTER);

        card.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e)  { rb.doClick(); }
            @Override public void mouseEntered(MouseEvent e)  {
                if (!Boolean.TRUE.equals(card.getClientProperty("selected")))
                    card.setBackground(C_BG2);
            }
            @Override public void mouseExited(MouseEvent e)   {
                if (!Boolean.TRUE.equals(card.getClientProperty("selected")))
                    card.setBackground(C_BG1);
            }
        });

        return card;
    }

    // ══════════════════════════════════════════════════════
    // PROGRESS STRIP
    // ══════════════════════════════════════════════════════

    private JPanel buildProgressBar() {
        JPanel p = new JPanel(new GridBagLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(0x0B0F14));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
                BorderFactory.createEmptyBorder(10, 18, 10, 18)));

        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL; g.insets = new Insets(2, 0, 2, 8);

        lblStatus = new JLabel("\u0110ang ch\u1edd...");
        lblStatus.setForeground(C_TEXT3);
        lblStatus.setFont(new Font(F_TITLE.getFamily(), Font.BOLD, 22));
        g.gridx=0; g.gridy=0; g.gridwidth=3; g.weightx=1;
        p.add(lblStatus, g);

        lblCurrentItem = new JLabel(" ");
        lblCurrentItem.setForeground(C_TEXT2);
        lblCurrentItem.setFont(new Font(F_MONO.getFamily(), Font.PLAIN, 13));
        g.gridy=1; g.insets = new Insets(1, 0, 4, 8);
        p.add(lblCurrentItem, g);

        // Users bar
        g.gridy=2; g.gridwidth=1; g.weightx=0; g.insets = new Insets(2, 0, 2, 8);
        p.add(mkBarLabel("Ng\u01b0\u1eddi d\u00f9ng"),  g);
        barUser = mkBar(C_ACCENT);
        g.gridx=1; g.weightx=1; p.add(barUser, g);
        lblUserCnt = mkCntLabel();
        g.gridx=2; g.weightx=0; p.add(lblUserCnt, g);

        // Folders bar
        g.gridx=0; g.gridy=3; g.weightx=0;
        p.add(mkBarLabel("Th\u01b0 m\u1ee5c"), g);
        barFolder = mkBar(C_PURPLE);
        g.gridx=1; g.weightx=1; p.add(barFolder, g);
        lblFolderCnt = mkCntLabel();
        g.gridx=2; g.weightx=0; p.add(lblFolderCnt, g);

        return p;
    }

    // ══════════════════════════════════════════════════════
    // LOG TERMINAL
    // ══════════════════════════════════════════════════════

    private JPanel buildLog() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(0x0D1117));

        JPanel lhdr = new JPanel(new BorderLayout());
        lhdr.setBackground(C_BG2);
        lhdr.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
                BorderFactory.createEmptyBorder(7, 16, 7, 10)));

        JPanel leftHdr = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftHdr.setBackground(C_BG2);

        // Traffic light dots
        leftHdr.add(mkDot(new Color(0xFF5F57)));
        leftHdr.add(mkDot(new Color(0xFFBD2E)));
        leftHdr.add(mkDot(new Color(0x28CA41)));

        JLabel lt = new JLabel("  Nhật ký hoạt động");
        lt.setForeground(C_TEXT2); lt.setFont(new Font(F_TITLE.getFamily(), Font.BOLD, 11));
        leftHdr.add(lt);

        JPanel logBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        logBtns.setBackground(C_BG2);
        btnClearLog = mkGhostBtn("Xoá");
        btnSaveLog  = mkGhostBtn("Lưu");
        btnClearLog.addActionListener(e -> clearLog());
        btnSaveLog.addActionListener(e  -> saveLog());
        logBtns.add(btnClearLog); logBtns.add(btnSaveLog);

        lhdr.add(leftHdr, BorderLayout.WEST);
        lhdr.add(logBtns, BorderLayout.EAST);
        p.add(lhdr, BorderLayout.NORTH);

        logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setBackground(new Color(0x0D1117));
        logPane.setForeground(C_TEXT);
        logPane.setFont(new Font(F_MONO.getFamily(), Font.PLAIN, 13));
        logPane.setCaretColor(C_ACCENT);
        logPane.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        logDoc = logPane.getStyledDocument();

        JScrollPane sp = new JScrollPane(logPane);
        sp.setBorder(null);
        sp.getViewport().setBackground(new Color(0x0D1117));
        sp.getVerticalScrollBar().setUnitIncrement(12);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private JLabel mkDot(Color c) {
        JLabel l = new JLabel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c); g2.fillOval(0, 2, 10, 10);
                g2.dispose();
            }
        };
        l.setPreferredSize(new Dimension(12, 14));
        l.setOpaque(false);
        return l;
    }

    // ══════════════════════════════════════════════════════
    // ACTION BAR — RUN / STOP
    // ══════════════════════════════════════════════════════

    private JPanel buildActionBar() {
        JPanel p = new JPanel(new BorderLayout(0, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, new Color(0x080C10), 0, getHeight(), new Color(0x0D1117)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                // subtle top shimmer
                g2.setColor(new Color(0x58A6FF18, true));
                g2.fillRect(0, 0, getWidth(), 1);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER),
                BorderFactory.createEmptyBorder(14, 24, 14, 24)));

        btnRun  = mkBigBtn("\u25b6  CH\u1ea0Y",  GREEN_BTN, GREEN_HOV, C_GREEN);
        btnStop = mkBigBtn("\u23f9  D\u1eebNG", RED_BTN,   RED_HOV,   C_RED);
        btnStop.setEnabled(false);

        btnRun.addActionListener(e  -> { if (onRun  != null) onRun.run(); });
        btnStop.addActionListener(e -> { if (onStop != null) onStop.run(); });

        // Layout: equal-width buttons filling the bar
        JPanel btns = new JPanel(new GridLayout(1, 2, 14, 0));
        btns.setOpaque(false);
        btns.add(btnRun);
        btns.add(btnStop);
        p.add(btns, BorderLayout.CENTER);
        return p;
    }

    // ══════════════════════════════════════════════════════
    // LOG METHODS
    // ══════════════════════════════════════════════════════

    public void appendLog(String msg, ProgressTracker.LogLevel lvl) {
        SwingUtilities.invokeLater(() -> {
            try {
                String ts = new SimpleDateFormat("HH:mm:ss").format(new Date());
                var style = switch (lvl) {
                    case SUCCESS -> sOk; case WARNING -> sWarn; case ERROR -> sErr;
                    case DETAIL  -> sDetail; case HEADER -> sHead; default -> sInfo;
                };
                // Timestamp in muted color, message in style color
                SimpleAttributeSet tsStyle = new SimpleAttributeSet();
                StyleConstants.setForeground(tsStyle, C_TEXT3);
                StyleConstants.setFontFamily(tsStyle, F_MONO.getFamily());
                StyleConstants.setFontSize(tsStyle, 12);
                logDoc.insertString(logDoc.getLength(), "[" + ts + "] ", tsStyle);
                logDoc.insertString(logDoc.getLength(), msg + "\n", style);
                logPane.setCaretPosition(logDoc.getLength());
            } catch (BadLocationException ignored) {}
        });
    }

    private void clearLog() {
        try { logDoc.remove(0, logDoc.getLength()); } catch (BadLocationException ignored) {}
    }

    private void saveLog() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("log-" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".txt"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try (var fw = new FileWriter(fc.getSelectedFile(), StandardCharsets.UTF_8)) {
                fw.write(logPane.getText());
                JOptionPane.showMessageDialog(this, "✅  Đã lưu!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Lỗi: " + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ══════════════════════════════════════════════════════
    // PROGRESS LISTENER
    // ══════════════════════════════════════════════════════

    @Override public void onLog(String m, ProgressTracker.LogLevel l) { appendLog(m, l); }

    @Override public void onUserStart(String email, int cur, int total) {
        SwingUtilities.invokeLater(() -> {
            lblStatus.setText("Đang xử lý: " + email);
            lblStatus.setForeground(C_ACCENT);
            barUser.setValue(total > 0 ? cur * 100 / total : 0);
            lblUserCnt.setText(cur + " / " + total);
        });
    }

    @Override public void onFolderStart(String path, int cur, int total) {
        SwingUtilities.invokeLater(() -> {
            String s = path.length() > 60 ? "..." + path.substring(path.length()-57) : path;
            lblCurrentItem.setText("  " + s);
            barFolder.setValue(total > 0 ? cur * 100 / total : 0);
            lblFolderCnt.setText(cur + " / " + total);
        });
    }

    @Override public void onFileProcessed(String name) {
        SwingUtilities.invokeLater(() -> lblCurrentItem.setText(
                "  " + (name.length() > 65 ? name.substring(0,62) + "..." : name)));
    }

    @Override public void onProgressUpdate(int uc, int ut, int fc, int ft) {
        SwingUtilities.invokeLater(() -> {
            if (ut > 0) { barUser.setValue(uc*100/ut);   lblUserCnt.setText(uc + " / " + ut);   }
            if (ft > 0) { barFolder.setValue(fc*100/ft); lblFolderCnt.setText(fc + " / " + ft); }
        });
    }

    @Override public void onComplete() {
        SwingUtilities.invokeLater(() -> {
            lblStatus.setText("✅  Hoàn thành!"); lblStatus.setForeground(C_GREEN);
            barUser.setValue(100); barFolder.setValue(100);
            setRunning(false);
        });
    }

    // ══════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════

    public void setRunning(boolean v) {
        SwingUtilities.invokeLater(() -> {
            btnRun.setEnabled(!v); btnStop.setEnabled(v);
            if (v) { lblStatus.setText("⏳  Đang chạy..."); lblStatus.setForeground(C_ACCENT); }
        });
    }

    public void resetProgress() {
        SwingUtilities.invokeLater(() -> {
            barUser.setValue(0); barFolder.setValue(0);
            lblUserCnt.setText("0 / 0"); lblFolderCnt.setText("0 / 0");
            lblCurrentItem.setText(" ");
            lblStatus.setText("Đang chờ..."); lblStatus.setForeground(C_TEXT3);
        });
    }

    public String getSelectedMode() {
        if (rb2 != null && rb2.isSelected()) return "2";
        if (rb3 != null && rb3.isSelected()) return "3";
        if (rb4 != null && rb4.isSelected()) return "4";
        return "1";
    }

    public String  getFolderId()          { return tfFolderId != null ? tfFolderId.getText().trim() : ""; }
    public void    setOnRunAction(Runnable r)  { onRun  = r; }
    public void    setOnStopAction(Runnable r) { onStop = r; }

    // ══════════════════════════════════════════════════════
    // WIDGET HELPERS
    // ══════════════════════════════════════════════════════

    /** Rounded progress bar */
    private JProgressBar mkBar(Color c) {
        JProgressBar b = new JProgressBar(0, 100) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(C_BG3);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                int filled = (int)((double)getValue() / getMaximum() * getWidth());
                if (filled > 0) {
                    g2.setPaint(new GradientPaint(0, 0, c.brighter(), filled, 0, c));
                    g2.fillRoundRect(0, 0, filled, getHeight(), getHeight(), getHeight());
                }
                g2.dispose();
            }
        };
        b.setOpaque(false);
        b.setBorderPainted(false);
        b.setStringPainted(false);
        b.setPreferredSize(new Dimension(0, 18));
        return b;
    }

    private JLabel mkBarLabel(String t) {
        JLabel l = new JLabel(t);
        l.setForeground(C_TEXT2);
        l.setFont(new Font(F_SMALL.getFamily(), Font.BOLD, 11));
        l.setPreferredSize(new Dimension(66, 16));
        return l;
    }

    private JLabel mkCntLabel() {
        JLabel l = new JLabel("0 / 0");
        l.setForeground(C_ACCENT2);
        l.setFont(new Font(F_MONO.getFamily(), Font.BOLD, 11));
        l.setPreferredSize(new Dimension(70, 16));
        return l;
    }

    /** Big gradient RUN / STOP button with hover animation */
    private JButton mkBigBtn(String text, Color bg, Color hover, Color accent) {
        JButton b = new JButton(text) {
            boolean isHover = false;
            {
                addMouseListener(new MouseAdapter() {
                    public void mouseEntered(MouseEvent e) { isHover = true;  repaint(); }
                    public void mouseExited (MouseEvent e) { isHover = false; repaint(); }
                });
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color base = isEnabled() ? (isHover ? hover : bg) : new Color(0x30363D);
                if (isEnabled() && isHover) {
                    // Glow effect
                    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 40));
                    g2.fillRoundRect(-3, -3, getWidth()+6, getHeight()+6, 14, 14);
                }
                g2.setPaint(new GradientPaint(0, 0, base.brighter(), 0, getHeight(), base));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                // Top highlight
                g2.setColor(new Color(255, 255, 255, 20));
                g2.fillRoundRect(2, 1, getWidth()-4, getHeight()/2, 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setForeground(Color.WHITE);
        b.setFont(new Font(F_TITLE.getFamily(), Font.BOLD, 16));
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setPreferredSize(new Dimension(220, 52));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private JTextField mkInput()       { return ConfigPanel.mkInput(); }
    private JButton    mkGhostBtn(String t) { return ConfigPanel.mkGhostBtn(t); }
}
