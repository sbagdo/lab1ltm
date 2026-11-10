import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;

/**
 * CHAT CLIENT (Swing) - giao diện mới: gọn, sang, hiện đại
 *  - Sidebar tối: hồ sơ, danh sách online (avatar), chọn chế độ gửi dạng segmented
 *  - Khung chat: bong bóng bo tròn trái/phải, ảnh hiển thị trực tiếp, file dạng thẻ
 *  - Thanh công cụ icon tròn: emoji, đính kèm, ghi âm, gọi thoại, video live
 *  - Text/File/Tin nhắn thoại : TCP | Gọi thoại + Video live : UDP qua server relay
 *
 * Biên dịch : javac -encoding UTF-8 ChatClient.java
 * Chạy      : java ChatClient
 */
public class ChatClient extends JFrame {
    // ---------- media
    static final AudioFormat CALL_FMT = new AudioFormat(8000f, 16, 1, true, false);
    static final AudioFormat REC_FMT = new AudioFormat(16000f, 16, 1, true, false);
    static final int MAX_FILE = 20 * 1024 * 1024;
    static final int[] EMOJIS = {0x1F600, 0x1F601, 0x1F602, 0x1F923, 0x1F603, 0x1F604, 0x1F605, 0x1F606,
            0x1F609, 0x1F60A, 0x1F60D, 0x1F618, 0x1F60E, 0x1F914, 0x1F62D, 0x1F621,
            0x1F44D, 0x1F44E, 0x1F44F, 0x1F64F, 0x1F4AA, 0x2764, 0x1F494, 0x1F525,
            0x1F389, 0x1F38A, 0x1F381, 0x1F355, 0x1F37A, 0x2615, 0x1F436, 0x1F431,
            0x1F31E, 0x1F319, 0x2B50, 0x1F4A9, 0x1F631, 0x1F634, 0x1F644, 0x1F4AF};

    // ---------- bảng màu
    static final Color ACCENT = new Color(0x5B6CFF), ACCENT_DARK = new Color(0x4857E0);
    static final Color BG = new Color(0xF3F5F9), LINE = new Color(0xE3E7EF);
    static final Color TEXT = new Color(0x1F2430), MUTED = new Color(0x8A93A6);
    static final Color SIDE = new Color(0x151A2B), SIDE_SEL = new Color(0x252C48), SIDE_MUTED = new Color(0x7F8AA8);
    static final Color GREEN = new Color(0x2ECC71), RED = new Color(0xE5484D), CALL_GREEN = new Color(0x2EAA6B);
    static final int[] AVATAR_COLORS = {0x5B6CFF, 0xEF6C8F, 0x2EAA6B, 0xF2A33A, 0x3FA7D6, 0x9B6BDF, 0xE5604D};

    static Font f(int style, int size) {
        return new Font("Dialog", style, size);
    }

    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    static Color avatarColor(String n) {
        return new Color(AVATAR_COLORS[(n.hashCode() & 0x7fffffff) % AVATAR_COLORS.length]);
    }

    static void avatar(Graphics2D g, String n, int x, int y, int d) {
        g.setColor(avatarColor(n));
        g.fillOval(x, y, d, d);
        g.setColor(Color.WHITE);
        g.setFont(f(Font.BOLD, d / 2));
        FontMetrics fm = g.getFontMetrics();
        String s = n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase();
        g.drawString(s, x + (d - fm.stringWidth(s)) / 2, y + (d - fm.getHeight()) / 2 + fm.getAscent());
    }

    static String em(int cp) {
        return new String(Character.toChars(cp));
    }

    static String emojiFont() {
        Set<String> names = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String n : new String[]{"Segoe UI Emoji", "Apple Color Emoji", "Noto Color Emoji"})
            if (names.contains(n)) return n;
        return "Dialog";
    }

    // =================================================================== THÀNH PHẦN GIAO DIỆN TÙY BIẾN
    /** Panel nền bo góc. */
    static class Round extends JPanel {
        Color fill, line;
        int arc;

        Round(Color fill, int arc, Color line) {
            this.fill = fill;
            this.arc = arc;
            this.line = line;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            if (line != null) {
                g.setColor(line);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            g.dispose();
        }
    }

    /** Nút phẳng bo tròn (dùng cho icon, segmented, gửi, emoji). */
    static class Btn extends JToggleButton {
        final Color bg, hover, fg, selBg, selFg;
        final int arc;
        final boolean momentary;

        Btn(String t, Font font, int arc, Color bg, Color hover, Color fg, Color selBg, Color selFg, boolean momentary) {
            super(t);
            this.arc = arc;
            this.bg = bg;
            this.hover = hover;
            this.fg = fg;
            this.selBg = selBg;
            this.selFg = selFg;
            this.momentary = momentary;
            setFont(font);
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setMargin(new Insets(0, 0, 0, 0));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            boolean sel = !momentary && isSelected();
            boolean hov = getModel().isRollover() || getModel().isArmed();
            Color b = sel ? selBg : hov ? hover : bg;
            if (b != null) {
                g.setColor(b);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            }
            g.setColor(sel ? selFg : fg);
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            String t = getText();
            g.drawString(t, (getWidth() - fm.stringWidth(t)) / 2, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    /** Ô nhập bo tròn có gợi ý. */
    static class RoundField extends JTextField {
        RoundField() {
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
            setFont(f(Font.PLAIN, 15));
            setForeground(TEXT);
            setCaretColor(ACCENT);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setColor(BG);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g.dispose();
            super.paintComponent(g0);
            if (getText().isEmpty()) {
                Graphics2D h = (Graphics2D) g0.create();
                aa(h);
                h.setColor(MUTED);
                h.setFont(getFont());
                FontMetrics fm = h.getFontMetrics();
                h.drawString("Nhập tin nhắn...", getInsets().left, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                h.dispose();
            }
        }
    }

    /** Hàng trong khung chat: cao đúng bằng nội dung. */
    static class Row extends JPanel {
        Row(LayoutManager lm) {
            super(lm);
            setOpaque(false);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** Vùng chứa tin nhắn, bám theo chiều rộng viewport. */
    static class ChatView extends JPanel implements Scrollable {
        ChatView() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(BG);
            setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));
        }

        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(400, 400);
        }

        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 24;
        }

        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return r.height;
        }

        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** Hồ sơ người dùng ở đầu sidebar. */
    static class Profile extends JPanel {
        final String name;

        Profile(String name) {
            this.name = name;
            setOpaque(false);
            setPreferredSize(new Dimension(0, 84));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            avatar(g, name, 20, 18, 46);
            g.setFont(f(Font.BOLD, 16));
            g.setColor(Color.WHITE);
            g.drawString(name, 78, 40);
            g.setColor(GREEN);
            g.fillOval(78, 50, 8, 8);
            g.setFont(f(Font.PLAIN, 12));
            g.setColor(SIDE_MUTED);
            g.drawString("Đang hoạt động", 92, 58);
            g.dispose();
        }
    }

    /** Dòng người dùng online trong danh sách. */
    static class UserCell extends JPanel implements ListCellRenderer<String> {
        String name = "";
        boolean sel;

        UserCell() {
            setOpaque(false);
        }

        public Component getListCellRendererComponent(JList<? extends String> l, String v, int i, boolean s, boolean fo) {
            name = v;
            sel = s;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), h = getHeight();
            if (sel) {
                g.setColor(SIDE_SEL);
                g.fillRoundRect(10, 3, w - 20, h - 6, 16, 16);
                g.setColor(ACCENT);
                g.fillRoundRect(10, 15, 3, h - 30, 3, 3);
            }
            int ay = (h - 38) / 2;
            avatar(g, name, 24, ay, 38);
            g.setColor(sel ? SIDE_SEL : SIDE);
            g.fillOval(50, ay + 26, 15, 15);
            g.setColor(GREEN);
            g.fillOval(52, ay + 28, 11, 11);
            g.setFont(f(Font.BOLD, 14));
            g.setColor(new Color(0xF2F4FA));
            g.drawString(name, 76, h / 2 - 1);
            g.setFont(f(Font.PLAIN, 11));
            g.setColor(SIDE_MUTED);
            g.drawString("Online", 76, h / 2 + 14);
            g.dispose();
        }
    }

    // ---- kết nối
    final String host, user;
    final int tcpPort, udpPort;
    Socket sock;
    DataInputStream in;
    DataOutputStream out;
    final Object wlock = new Object();
    DatagramSocket udp;
    InetAddress serverAddr;
    final File dlDir;

    // ---- UI
    DefaultListModel<String> userModel = new DefaultListModel<>();
    JList<String> userList = new JList<>(userModel);
    final ChatView view = new ChatView();
    JScrollPane scroll;
    JTextField input = new RoundField();
    JToggleButton rbAll, rbSel;
    Btn tbRec, tbVoice, tbVideo;
    JLabel subLbl = new JLabel(), onlineLbl = new JLabel("ĐANG ONLINE");
    final String emoFont = emojiFont();

    // ---- media
    volatile boolean voiceOn, videoOn, recording;
    SourceDataLine speaker;
    final Map<String, JFrame> videoWins = new HashMap<>();

    // =================================================================== KHỞI TẠO
    ChatClient(String host, int tcpPort, String user, String pass) throws IOException {
        this.host = host;
        this.tcpPort = tcpPort;
        this.udpPort = tcpPort + 1;
        this.user = user;
        dlDir = new File("downloads/" + user);
        dlDir.mkdirs();

        sock = new Socket(host, tcpPort);
        in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
        out.writeUTF("LOGIN");
        out.writeUTF(user);
        out.writeUTF(pass);
        out.flush();
        String r = in.readUTF();
        if (r.equals("LOGIN_FAIL")) {
            String why = in.readUTF();
            sock.close();
            throw new IOException(why);
        }
        in.readUTF(); // DB / NODB
        udp = new DatagramSocket();
        serverAddr = InetAddress.getByName(host);

        buildUi();
        startThread(this::readLoop, "tcp-read");
        startThread(this::udpLoop, "udp-read");
        startThread(() -> {
            while (true) { // đăng ký + keep-alive địa chỉ UDP
                try {
                    udpSend(pkt(0, new byte[0], 0));
                    Thread.sleep(15000);
                } catch (Exception e) {
                    return;
                }
            }
        }, "udp-register");
    }

    static void startThread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    Btn tool(String icon, String tip, boolean toggle, Color on) {
        Btn b = new Btn(icon, new Font(emoFont, Font.PLAIN, 19), 40, null, new Color(0xEBEEF5),
                new Color(0x5A6378), on, Color.WHITE, !toggle);
        b.setToolTipText(tip);
        b.setPreferredSize(new Dimension(40, 40));
        return b;
    }

    Btn seg(String text) {
        Btn b = new Btn(text, f(Font.BOLD, 12), 18, new Color(0x232A42), new Color(0x2D3556),
                new Color(0xA9B1C9), ACCENT, Color.WHITE, false);
        return b;
    }

    void buildUi() {
        setTitle("Chat - " + user);
        setSize(1040, 700);
        setMinimumSize(new Dimension(760, 520));
        setLocationByPlatform(true);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // ================= SIDEBAR (trái)
        JPanel left = new JPanel(new BorderLayout());
        left.setBackground(SIDE);
        left.setPreferredSize(new Dimension(268, 0));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(new Profile(user), BorderLayout.NORTH);
        onlineLbl.setFont(f(Font.BOLD, 11));
        onlineLbl.setForeground(SIDE_MUTED);
        onlineLbl.setBorder(BorderFactory.createEmptyBorder(6, 24, 6, 0));
        top.add(onlineLbl, BorderLayout.SOUTH);
        left.add(top, BorderLayout.NORTH);

        userList.setBackground(SIDE);
        userList.setOpaque(true);
        userList.setFixedCellHeight(56);
        userList.setCellRenderer(new UserCell());
        userList.addListSelectionListener(e -> {
            if (!userList.isSelectionEmpty()) rbSel.setSelected(true);
            updateTarget();
        });
        JScrollPane ls = new JScrollPane(userList);
        ls.setBorder(null);
        ls.getViewport().setBackground(SIDE);
        left.add(ls, BorderLayout.CENTER);

        rbAll = seg("Tất cả");
        rbSel = seg("Đã chọn");
        rbAll.setSelected(true);
        ButtonGroup bg = new ButtonGroup();
        bg.add(rbAll);
        bg.add(rbSel);
        rbAll.addItemListener(e -> updateTarget());
        rbSel.addItemListener(e -> updateTarget());
        JPanel segPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        segPanel.setOpaque(false);
        segPanel.setPreferredSize(new Dimension(0, 36));
        segPanel.add(rbAll);
        segPanel.add(rbSel);
        JLabel modeLbl = new JLabel("GỬI TỚI");
        modeLbl.setFont(f(Font.BOLD, 11));
        modeLbl.setForeground(SIDE_MUTED);
        JLabel hint = new JLabel("Giữ Ctrl/Shift để chọn nhiều người");
        hint.setFont(f(Font.PLAIN, 11));
        hint.setForeground(SIDE_MUTED);
        JPanel mode = new JPanel(new BorderLayout(0, 8));
        mode.setOpaque(false);
        mode.setBorder(BorderFactory.createEmptyBorder(12, 20, 18, 20));
        mode.add(modeLbl, BorderLayout.NORTH);
        mode.add(segPanel, BorderLayout.CENTER);
        mode.add(hint, BorderLayout.SOUTH);
        left.add(mode, BorderLayout.SOUTH);
        add(left, BorderLayout.WEST);

        // ================= KHUNG GIỮA
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(BG);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Color.WHITE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, LINE),
                BorderFactory.createEmptyBorder(14, 24, 14, 24)));
        JLabel title = new JLabel("Trò chuyện");
        title.setFont(f(Font.BOLD, 18));
        title.setForeground(TEXT);
        subLbl.setFont(f(Font.PLAIN, 12));
        subLbl.setForeground(MUTED);
        header.add(title, BorderLayout.NORTH);
        header.add(subLbl, BorderLayout.SOUTH);
        center.add(header, BorderLayout.NORTH);

        scroll = new JScrollPane(view, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        center.add(scroll, BorderLayout.CENTER);

        // --- thanh nhập
        Btn bEmoji = tool(em(0x1F60A), "Emoji", false, null);
        Btn bFile = tool(em(0x1F4CE), "Đính kèm file / ảnh", false, null);
        tbRec = tool(em(0x1F3A4), "Ghi âm tin nhắn thoại", true, RED);
        tbVoice = tool(em(0x1F4DE), "Gọi thoại (UDP)", true, CALL_GREEN);
        tbVideo = tool(em(0x1F4F9), "Video live (UDP)", true, ACCENT);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tools.setOpaque(false);
        tools.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        for (Btn b : new Btn[]{bEmoji, bFile, tbRec, tbVoice, tbVideo}) tools.add(b);
        bEmoji.addActionListener(e -> showEmojiPicker(bEmoji));
        bFile.addActionListener(e -> chooseAndSendFile());
        tbRec.addActionListener(e -> toggleRecord());
        tbVoice.addActionListener(e -> toggleVoice());
        tbVideo.addActionListener(e -> toggleVideo());

        input.setPreferredSize(new Dimension(100, 46));
        input.addActionListener(e -> sendText());
        Btn bSend = new Btn("Gửi", f(Font.BOLD, 14), 46, ACCENT, ACCENT_DARK, Color.WHITE, ACCENT, Color.WHITE, true);
        bSend.setPreferredSize(new Dimension(88, 46));
        bSend.addActionListener(e -> sendText());
        JPanel inputRow = new JPanel(new BorderLayout(10, 0));
        inputRow.setOpaque(false);
        inputRow.add(input, BorderLayout.CENTER);
        inputRow.add(bSend, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(Color.WHITE);
        bottom.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, LINE),
                BorderFactory.createEmptyBorder(10, 20, 14, 20)));
        bottom.add(tools, BorderLayout.NORTH);
        bottom.add(inputRow, BorderLayout.CENTER);
        center.add(bottom, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                voiceOn = false;
                videoOn = false;
                recording = false;
                try {
                    sock.close();
                } catch (IOException ignored) {
                }
            }
        });
        updateTarget();
        sys("Xin chào " + user + "! Chọn người nhận ở bên trái, hoặc để \"Tất cả\" để gửi cho mọi người.");
    }

    void updateTarget() {
        if (rbAll == null || rbSel == null) return;
        if (rbAll.isSelected()) {
            subLbl.setText("Đang gửi tới: tất cả mọi người (Broadcast)");
        } else {
            List<String> sel = userList.getSelectedValuesList();
            subLbl.setText(sel.isEmpty() ? "Chưa chọn người nhận"
                    : sel.size() == 1 ? "Đang nhắn riêng tới: " + sel.get(0)
                    : "Đang gửi nhóm (Multicast) tới: " + String.join(", ", sel));
        }
    }

    // =================================================================== NHẬN DỮ LIỆU (TCP)
    void readLoop() {
        try {
            while (true) {
                String cmd = in.readUTF();
                switch (cmd) {
                    case "USERS":
                        updateUsers(in.readUTF());
                        break;
                    case "SYS":
                        sys(in.readUTF());
                        break;
                    case "HIST":
                        hist(in.readUTF());
                        break;
                    case "MSG": {
                        String from = in.readUTF(), label = in.readUTF(), tg = in.readUTF(), text = in.readUTF(), time = in.readUTF();
                        showMsg(from, label, tg, text, time, false);
                        break;
                    }
                    case "FILE": {
                        String from = in.readUTF(), label = in.readUTF(), tg = in.readUTF(), name = in.readUTF();
                        String kind = in.readUTF(), time = in.readUTF();
                        byte[] d = new byte[in.readInt()];
                        in.readFully(d);
                        File f = new File(dlDir, System.currentTimeMillis() + "_" + new File(name).getName());
                        Files.write(f.toPath(), d);
                        showFile(from, label, tg, name, kind, f, time, false);
                        break;
                    }
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            sys("Mất kết nối tới server.");
        }
    }

    void updateUsers(String csv) {
        SwingUtilities.invokeLater(() -> {
            List<String> sel = userList.getSelectedValuesList();
            userModel.clear();
            for (String n : csv.split(",")) if (!n.isEmpty() && !n.equals(user)) userModel.addElement(n);
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < userModel.size(); i++) if (sel.contains(userModel.get(i))) idx.add(i);
            userList.setSelectedIndices(idx.stream().mapToInt(Integer::intValue).toArray());
            onlineLbl.setText("ĐANG ONLINE  ·  " + userModel.size());
            updateTarget();
        });
    }

    // =================================================================== HIỂN THỊ
    static String now() {
        return new SimpleDateFormat("HH:mm").format(new Date());
    }

    void addRow(JComponent c) {
        view.add(c);
        view.revalidate();
        view.repaint();
        SwingUtilities.invokeLater(() -> {
            JScrollBar sb = scroll.getVerticalScrollBar();
            sb.setValue(sb.getMaximum());
        });
    }

    void note(String s, boolean pill) {
        SwingUtilities.invokeLater(() -> {
            JLabel l = new JLabel(s);
            l.setFont(f(Font.PLAIN, pill ? 12 : 11));
            l.setForeground(MUTED);
            Row r = new Row(new FlowLayout(FlowLayout.CENTER, 0, 0));
            r.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
            if (pill) {
                Round p = new Round(new Color(0xE6E9F2), 16, null);
                p.setLayout(new FlowLayout(FlowLayout.CENTER, 14, 4));
                p.add(l);
                r.add(p);
            } else r.add(l);
            addRow(r);
        });
    }

    void sys(String s) {
        note(s, true);
    }

    void hist(String s) {
        note(s, false);
    }

    String tag(String label, String tg, boolean mine) {
        switch (label) {
            case "BROADCAST":
                return "Tất cả";
            case "MULTICAST":
                return "Nhóm: " + tg;
            default:
                return mine ? "Riêng → " + tg : "Riêng tư";
        }
    }

    JTextArea textArea(String s, Color fg) {
        JTextArea t = new JTextArea(s);
        t.setFont(f(Font.PLAIN, 15));
        t.setForeground(fg);
        t.setOpaque(false);
        t.setEditable(false);
        t.setBorder(null);
        t.setLineWrap(false);
        Dimension d = t.getPreferredSize();
        int max = 420;
        if (d.width > max) {
            t.setLineWrap(true);
            t.setWrapStyleWord(true);
            t.setSize(max, Short.MAX_VALUE);
            d = t.getPreferredSize();
        }
        t.setPreferredSize(d);
        t.setMaximumSize(d);
        return t;
    }

    JComponent bubble(String from, String label, String tg, String time, boolean mine, JComponent content) {
        Round b = new Round(mine ? ACCENT : Color.WHITE, 22, mine ? null : LINE);
        b.setLayout(new BoxLayout(b, BoxLayout.Y_AXIS));
        b.setBorder(BorderFactory.createEmptyBorder(9, 15, 8, 15));
        if (!mine) {
            JLabel n = new JLabel(from);
            n.setFont(f(Font.BOLD, 12));
            n.setForeground(avatarColor(from));
            n.setAlignmentX(0f);
            b.add(n);
            b.add(Box.createVerticalStrut(3));
        }
        content.setAlignmentX(0f);
        b.add(content);
        b.add(Box.createVerticalStrut(4));
        JLabel m = new JLabel(time + "  ·  " + tag(label, tg, mine));
        m.setFont(f(Font.PLAIN, 10));
        m.setForeground(mine ? new Color(255, 255, 255, 190) : MUTED);
        m.setAlignmentX(0f);
        b.add(m);
        return b;
    }

    JComponent row(boolean right, JComponent bubble) {
        Row r = new Row(new BorderLayout());
        r.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        r.add(bubble, right ? BorderLayout.EAST : BorderLayout.WEST);
        return r;
    }

    void showMsg(String from, String label, String tg, String text, String time, boolean mine) {
        SwingUtilities.invokeLater(() ->
                addRow(row(mine, bubble(from, label, tg, time, mine, textArea(text, mine ? Color.WHITE : TEXT)))));
    }

    void showFile(String from, String label, String tg, String name, String kind, File f, String time, boolean mine) {
        SwingUtilities.invokeLater(() -> {
            JComponent content;
            ImageIcon ic = kind.equals("image") ? scaledIcon(f) : null;
            if (ic != null) {
                JLabel lb = new JLabel(ic);
                lb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                lb.setToolTipText("Bấm để mở ảnh");
                lb.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        openFile(f, kind);
                    }
                });
                content = lb;
            } else {
                String icon = kind.equals("audio") ? em(0x1F50A) : kind.equals("video") ? em(0x1F3AC) : em(0x1F4C4);
                String shown = name.length() > 30 ? name.substring(0, 27) + "..." : name;
                Round chip = new Round(mine ? new Color(255, 255, 255, 45) : new Color(0xF0F2F8), 16, null);
                chip.setLayout(new FlowLayout(FlowLayout.LEFT, 10, 8));
                JLabel il = new JLabel(icon);
                il.setFont(new Font(emoFont, Font.PLAIN, 24));
                il.setForeground(mine ? Color.WHITE : ACCENT);
                JLabel nl = new JLabel(shown);
                nl.setFont(f(Font.BOLD, 13));
                nl.setForeground(mine ? Color.WHITE : TEXT);
                JLabel sl = new JLabel(kind.equals("audio") ? "Bấm để nghe" : "Bấm để mở");
                sl.setFont(f(Font.PLAIN, 11));
                sl.setForeground(mine ? new Color(255, 255, 255, 190) : MUTED);
                JPanel txt = new JPanel();
                txt.setOpaque(false);
                txt.setLayout(new BoxLayout(txt, BoxLayout.Y_AXIS));
                txt.add(nl);
                txt.add(sl);
                chip.add(il);
                chip.add(txt);
                chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                chip.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        openFile(f, kind);
                    }
                });
                content = chip;
            }
            addRow(row(mine, bubble(from, label, tg, time, mine, content)));
        });
    }

    ImageIcon scaledIcon(File f) {
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) return null;
            int w = img.getWidth(), h = img.getHeight(), max = 320;
            if (w > max) {
                h = h * max / w;
                w = max;
            }
            return new ImageIcon(img.getScaledInstance(w, h, Image.SCALE_SMOOTH));
        } catch (IOException e) {
            return null;
        }
    }

    void openFile(File f, String kind) {
        if (kind.equals("audio") && f.getName().toLowerCase().endsWith(".wav")) {
            startThread(() -> {
                try (AudioInputStream ais = AudioSystem.getAudioInputStream(f)) {
                    Clip c = AudioSystem.getClip();
                    c.open(ais);
                    c.start();
                    Thread.sleep(c.getMicrosecondLength() / 1000 + 300);
                    c.close();
                } catch (Exception e) {
                    desktopOpen(f);
                }
            }, "play-wav");
        } else desktopOpen(f);
    }

    void desktopOpen(File f) {
        try {
            Desktop.getDesktop().open(f);
        } catch (Exception e) {
            sys("Không mở được file. Đường dẫn: " + f.getAbsolutePath());
        }
    }

    // =================================================================== GỬI
    String[] target() {
        if (rbAll.isSelected()) return new String[]{"ALL", ""};
        List<String> sel = new ArrayList<>(userList.getSelectedValuesList());
        sel.remove(user);
        if (sel.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Hãy chọn ít nhất 1 người ở danh sách bên trái\n(chọn nhiều người = Multicast).");
            return null;
        }
        return new String[]{"SEL", String.join(",", sel)};
    }

    String labelOf(String[] t) {
        return t[0].equals("ALL") ? "BROADCAST" : t[1].contains(",") ? "MULTICAST" : "PRIVATE";
    }

    void tcpSend(String cmd, String... f) throws IOException {
        synchronized (wlock) {
            out.writeUTF(cmd);
            for (String s : f) out.writeUTF(s);
            out.flush();
        }
    }

    static String emoticons(String t) {
        String[][] m = {{":)", em(0x1F60A)}, {":D", em(0x1F604)}, {":(", em(0x1F622)}, {"<3", em(0x2764)},
                {":P", em(0x1F61B)}, {";)", em(0x1F609)}, {":o", em(0x1F62E)}};
        for (String[] e : m) t = t.replace(e[0], e[1]);
        return t;
    }

    void sendText() {
        String t = input.getText().trim();
        if (t.isEmpty()) return;
        if (t.length() > 15000) t = t.substring(0, 15000);
        String[] tg = target();
        if (tg == null) return;
        t = emoticons(t);
        try {
            tcpSend("MSG", tg[0], tg[1], t);
            showMsg(user, labelOf(tg), tg[1], t, now(), true);
            input.setText("");
        } catch (IOException e) {
            sys("Gửi thất bại: " + e.getMessage());
        }
    }

    static String kindOf(String name) {
        String n = name.toLowerCase();
        if (n.matches(".*\\.(png|jpg|jpeg|gif|bmp)$")) return "image";
        if (n.matches(".*\\.(wav|mp3|aac|ogg|m4a|flac)$")) return "audio";
        if (n.matches(".*\\.(mp4|avi|mkv|mov|webm|wmv)$")) return "video";
        return "file";
    }

    void chooseAndSendFile() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            sendFile(f, kindOf(f.getName()));
        }
    }

    void sendFile(File f, String kind) {
        String[] tg = target();
        if (tg == null) return;
        if (f.length() > MAX_FILE) {
            sys("File quá lớn (tối đa 20MB): " + f.getName());
            return;
        }
        startThread(() -> {
            try {
                byte[] d = Files.readAllBytes(f.toPath());
                synchronized (wlock) {
                    out.writeUTF("FILE");
                    out.writeUTF(tg[0]);
                    out.writeUTF(tg[1]);
                    out.writeUTF(f.getName());
                    out.writeUTF(kind);
                    out.writeInt(d.length);
                    out.write(d);
                    out.flush();
                }
                showFile(user, labelOf(tg), tg[1], f.getName(), kind, f, now(), true);
            } catch (IOException e) {
                sys("Gửi file thất bại: " + e.getMessage());
            }
        }, "send-file");
    }

    // =================================================================== EMOJI
    void showEmojiPicker(Component anchor) {
        JPopupMenu pm = new JPopupMenu();
        pm.setBorder(BorderFactory.createLineBorder(LINE));
        JPanel grid = new JPanel(new GridLayout(0, 8, 2, 2));
        grid.setBackground(Color.WHITE);
        grid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        for (int cp : EMOJIS) {
            String s = em(cp);
            Btn b = new Btn(s, new Font(emoFont, Font.PLAIN, 20), 12, null, new Color(0xEBEEF5), TEXT, ACCENT, Color.WHITE, true);
            b.setPreferredSize(new Dimension(40, 40));
            b.addActionListener(e -> {
                input.replaceSelection(s);
                input.requestFocus();
            });
            grid.add(b);
        }
        pm.add(grid);
        pm.show(anchor, 0, -pm.getPreferredSize().height - 6);
    }

    // =================================================================== TIN NHẮN THOẠI (ghi âm -> TCP)
    void toggleRecord() {
        if (!recording) {
            if (target() == null) {
                tbRec.setSelected(false);
                return;
            }
            recording = true;
            startThread(() -> {
                try {
                    TargetDataLine mic = AudioSystem.getTargetDataLine(REC_FMT);
                    mic.open(REC_FMT);
                    mic.start();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    while (recording) {
                        int n = mic.read(b, 0, b.length);
                        if (n > 0) bo.write(b, 0, n);
                    }
                    mic.stop();
                    mic.close();
                    byte[] pcm = bo.toByteArray();
                    File f = new File(dlDir, "voice_" + System.currentTimeMillis() + ".wav");
                    AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), REC_FMT,
                            pcm.length / REC_FMT.getFrameSize()), AudioFileFormat.Type.WAVE, f);
                    sendFile(f, "audio");
                } catch (Exception e) {
                    recording = false;
                    sys("Không ghi âm được: " + e.getMessage());
                    SwingUtilities.invokeLater(() -> tbRec.setSelected(false));
                }
            }, "record");
            sys("Đang ghi âm... bấm lại nút micro để dừng và gửi.");
        } else {
            recording = false;
        }
    }

    // =================================================================== UDP: GỌI THOẠI + VIDEO LIVE
    byte[] pkt(int type, byte[] payload, int plen) {
        byte[] nm = user.getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[2 + nm.length + plen];
        b[0] = (byte) type;
        b[1] = (byte) nm.length;
        System.arraycopy(nm, 0, b, 2, nm.length);
        System.arraycopy(payload, 0, b, 2 + nm.length, plen);
        return b;
    }

    void udpSend(byte[] b) throws IOException {
        udp.send(new DatagramPacket(b, b.length, serverAddr, udpPort));
    }

    void sendCall() throws IOException {
        String[] tg = target();
        tcpSend("CALL", tg == null || tg[0].equals("ALL") ? "" : tg[1]);
    }

    void stopCallIfIdle() {
        if (!voiceOn && !videoOn) {
            try {
                tcpSend("CALLSTOP");
            } catch (IOException ignored) {
            }
        }
    }

    void toggleVoice() {
        if (tbVoice.isSelected()) {
            if (target() == null) {
                tbVoice.setSelected(false);
                return;
            }
            try {
                sendCall();
            } catch (IOException e) {
                tbVoice.setSelected(false);
                return;
            }
            voiceOn = true;
            startThread(() -> {
                try {
                    TargetDataLine mic = AudioSystem.getTargetDataLine(CALL_FMT);
                    mic.open(CALL_FMT);
                    mic.start();
                    byte[] b = new byte[1024];
                    while (voiceOn) {
                        int n = mic.read(b, 0, b.length);
                        if (n > 0) udpSend(pkt(1, b, n));
                    }
                    mic.stop();
                    mic.close();
                } catch (Exception e) {
                    voiceOn = false;
                    sys("Không mở được micro: " + e.getMessage());
                    SwingUtilities.invokeLater(() -> tbVoice.setSelected(false));
                }
            }, "voice-send");
            sys("Đang gọi thoại (UDP). Bấm lại để tắt. Đổi người nhận thì tắt rồi bật lại.");
        } else {
            voiceOn = false;
            stopCallIfIdle();
        }
    }

    /** Nguồn video: hiện tại là chia sẻ màn hình (thuần JDK). Muốn dùng webcam -> thay bằng webcam.getImage() (thư viện webcam-capture). */
    BufferedImage captureFrame(Robot robot, Rectangle screen) {
        BufferedImage cap = robot.createScreenCapture(screen);
        int w = 480, h = Math.max(1, cap.getHeight() * w / cap.getWidth());
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(cap, 0, 0, w, h, null);
        g.dispose();
        return small;
    }

    static byte[] jpeg(BufferedImage img, float q) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam pr = w.getDefaultWriteParam();
        pr.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        pr.setCompressionQuality(q);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bo)) {
            w.setOutput(ios);
            w.write(null, new IIOImage(img, null, null), pr);
        }
        w.dispose();
        return bo.toByteArray();
    }

    void toggleVideo() {
        if (tbVideo.isSelected()) {
            if (target() == null) {
                tbVideo.setSelected(false);
                return;
            }
            try {
                sendCall();
            } catch (IOException e) {
                tbVideo.setSelected(false);
                return;
            }
            videoOn = true;
            startThread(() -> {
                try {
                    Robot robot = new Robot();
                    Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
                    while (videoOn) {
                        byte[] j = jpeg(captureFrame(robot, screen), 0.5f);
                        if (j.length < 60000) udpSend(pkt(2, j, j.length));
                        Thread.sleep(200);
                    }
                } catch (Exception e) {
                    videoOn = false;
                    sys("Không gửi được video: " + e.getMessage());
                    SwingUtilities.invokeLater(() -> tbVideo.setSelected(false));
                }
            }, "video-send");
            sys("Đang phát video live (chia sẻ màn hình, UDP). Bấm lại để tắt.");
        } else {
            videoOn = false;
            stopCallIfIdle();
        }
    }

    void udpLoop() {
        byte[] buf = new byte[65507];
        try {
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udp.receive(p);
                if (p.getLength() < 2) continue;
                int type = buf[0], nl = buf[1] & 0xff, off = 2 + nl, len = p.getLength() - off;
                if (len <= 0) continue;
                String from = new String(buf, 2, nl, StandardCharsets.UTF_8);
                if (type == 1) playAudio(buf, off, len);
                else if (type == 2) showVideo(from, ImageIO.read(new ByteArrayInputStream(buf, off, len)));
            }
        } catch (Exception ignored) {
        }
    }

    void playAudio(byte[] b, int off, int len) {
        try {
            if (speaker == null) {
                speaker = AudioSystem.getSourceDataLine(CALL_FMT);
                speaker.open(CALL_FMT, 16000);
                speaker.start();
            }
            speaker.write(b, off, len & ~1);
        } catch (Exception ignored) {
        }
    }

    void showVideo(String from, BufferedImage img) {
        if (img == null) return;
        SwingUtilities.invokeLater(() -> {
            JFrame f = videoWins.get(from);
            if (f == null) {
                f = new JFrame("Video từ " + from);
                JLabel lb = new JLabel();
                lb.setHorizontalAlignment(SwingConstants.CENTER);
                f.getContentPane().setBackground(Color.BLACK);
                f.add(lb);
                f.setSize(img.getWidth() + 20, img.getHeight() + 45);
                f.setLocationByPlatform(true);
                f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                f.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        videoWins.remove(from);
                    }
                });
                videoWins.put(from, f);
                f.setVisible(true);
            }
            ((JLabel) f.getContentPane().getComponent(0)).setIcon(new ImageIcon(img));
        });
    }

    // =================================================================== MAIN (hộp thoại đăng nhập)
    static JTextField styled(JTextField t) {
        t.setFont(f(Font.PLAIN, 14));
        t.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        return t;
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(() -> {
            JTextField h = styled(new JTextField("localhost", 22)), pt = styled(new JTextField("5000")),
                    u = styled(new JTextField());
            JPasswordField pw = (JPasswordField) styled(new JPasswordField());

            JPanel p = new JPanel(new GridBagLayout());
            p.setBorder(BorderFactory.createEmptyBorder(6, 10, 0, 10));
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            c.anchor = GridBagConstraints.WEST;

            JLabel t1 = new JLabel("Chat");
            t1.setFont(f(Font.BOLD, 28));
            t1.setForeground(ACCENT);
            JLabel t2 = new JLabel("Đăng nhập để bắt đầu trò chuyện");
            t2.setFont(f(Font.PLAIN, 13));
            t2.setForeground(MUTED);
            c.gridy = 0;
            p.add(t1, c);
            c.gridy = 1;
            c.insets = new Insets(0, 0, 14, 0);
            p.add(t2, c);

            String[] names = {"ĐỊA CHỈ SERVER (IP)", "PORT TCP", "TÊN ĐĂNG NHẬP", "MẬT KHẨU (khi server bật database)"};
            JTextField[] fs = {h, pt, u, pw};
            int y = 2;
            for (int i = 0; i < fs.length; i++) {
                JLabel l = new JLabel(names[i]);
                l.setFont(f(Font.BOLD, 10));
                l.setForeground(MUTED);
                c.gridy = y++;
                c.insets = new Insets(0, 0, 3, 0);
                p.add(l, c);
                c.gridy = y++;
                c.insets = new Insets(0, 0, 10, 0);
                p.add(fs[i], c);
            }

            while (true) {
                int r = JOptionPane.showConfirmDialog(null, p, "Đăng nhập Chat", JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE);
                if (r != JOptionPane.OK_OPTION) System.exit(0);
                try {
                    new ChatClient(h.getText().trim(), Integer.parseInt(pt.getText().trim()), u.getText().trim(),
                            new String(pw.getPassword())).setVisible(true);
                    return;
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(null, "Không đăng nhập được: " + e.getMessage(), "Lỗi",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });
    }
}