import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CHAT SERVER (Client-Server)
 *  - TCP 5000 : đăng nhập, tin nhắn text, gửi file (ảnh / audio / video / tài liệu), danh sách online
 *  - UDP 5001 : relay luồng thoại (audio) và video realtime giữa các client
 *  - Database : SQLite (tùy chọn). Nếu có sqlite-jdbc.jar trong classpath -> lưu user + lịch sử chat.
 *
 * Biên dịch : javac -encoding UTF-8 ChatServer.java
 * Chạy      : java ChatServer                       (không DB)
 *             java -cp ".;sqlite-jdbc.jar" ChatServer (có DB; Linux/Mac dùng dấu ':' thay ';')
 */
public class ChatServer {
    static final int TCP_PORT = 5000, UDP_PORT = 5001;
    static final int MAX_FILE = 20 * 1024 * 1024; // 20MB

    static final Map<String, Handler> clients = new ConcurrentHashMap<>();
    static final Map<String, SocketAddress> udpAddrs = new ConcurrentHashMap<>();
    static final Map<String, Set<String>> callTargets = new ConcurrentHashMap<>();
    static Connection db;

    public static void main(String[] args) throws Exception {
        initDb();
        new File("server_files").mkdirs();
        Thread udp = new Thread(ChatServer::udpLoop, "udp-relay");
        udp.setDaemon(true);
        udp.start();
        try (ServerSocket ss = new ServerSocket(TCP_PORT)) {
            log("Server chạy: TCP " + TCP_PORT + " | UDP " + UDP_PORT);
            while (true) {
                Socket s = ss.accept();
                new Handler(s).start();
            }
        }
    }

    // ------------------------------------------------------------------ DATABASE
    static void initDb() {
        try {
            Class.forName("org.sqlite.JDBC");
            db = DriverManager.getConnection("jdbc:sqlite:chat.db");
            try (Statement st = db.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS users(username TEXT PRIMARY KEY, password TEXT)");
                st.execute("CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "sender TEXT, mode TEXT, targets TEXT, content TEXT, time TEXT)");
            }
            log("Database SQLite: BẬT (chat.db)");
        } catch (Throwable e) {
            db = null;
            log("Không tìm thấy sqlite-jdbc -> chạy KHÔNG database (" + e.getClass().getSimpleName() + ")");
        }
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }

    /** Trả về null nếu OK, ngược lại là thông báo lỗi. Tên chưa tồn tại sẽ được tự đăng ký. */
    static synchronized String auth(String u, String p) {
        if (db == null) return null;
        try (PreparedStatement ps = db.prepareStatement("SELECT password FROM users WHERE username=?")) {
            ps.setString(1, u);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString(1).equals(hash(p)) ? null : "Sai mật khẩu";
        } catch (SQLException e) {
            return "Lỗi database";
        }
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO users VALUES(?,?)")) {
            ps.setString(1, u);
            ps.setString(2, hash(p));
            ps.executeUpdate();
            return null;
        } catch (SQLException e) {
            return "Lỗi database";
        }
    }

    static synchronized void saveMsg(String sender, String mode, String targets, String content, String time) {
        if (db == null) return;
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO messages(sender,mode,targets,content,time) VALUES(?,?,?,?,?)")) {
            ps.setString(1, sender);
            ps.setString(2, mode);
            ps.setString(3, "," + targets + ",");
            ps.setString(4, content);
            ps.setString(5, time);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("Lỗi lưu tin: " + e.getMessage());
        }
    }

    static synchronized List<String> history(String u) {
        List<String> r = new ArrayList<>();
        if (db == null) return r;
        String sql = "SELECT sender,mode,content,time FROM (SELECT * FROM messages WHERE mode='BROADCAST' "
                + "OR sender=? OR targets LIKE ? ORDER BY id DESC LIMIT 50) ORDER BY id";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, u);
            ps.setString(2, "%," + u + ",%");
            ResultSet rs = ps.executeQuery();
            while (rs.next())
                r.add("[" + rs.getString(4) + "] " + rs.getString(1) + " (" + rs.getString(2) + "): " + rs.getString(3));
        } catch (SQLException e) {
            log("Lỗi đọc lịch sử: " + e.getMessage());
        }
        return r;
    }

    // ------------------------------------------------------------------ UDP RELAY (audio/video)
    static void udpLoop() {
        try (DatagramSocket ds = new DatagramSocket(UDP_PORT)) {
            byte[] buf = new byte[65507];
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ds.receive(p);
                int len = p.getLength();
                if (len < 2) continue;
                int type = buf[0], nl = buf[1] & 0xff;
                if (len < 2 + nl) continue;
                String name = new String(buf, 2, nl, StandardCharsets.UTF_8);
                if (!clients.containsKey(name)) continue;
                if (type == 0) { // đăng ký / keep-alive địa chỉ UDP
                    udpAddrs.put(name, p.getSocketAddress());
                    continue;
                }
                Set<String> tg = callTargets.get(name);
                if (tg == null) continue;
                Collection<String> dest = tg.isEmpty() ? new ArrayList<>(udpAddrs.keySet()) : tg;
                for (String d : dest) {
                    if (d.equals(name)) continue;
                    SocketAddress a = udpAddrs.get(d);
                    if (a != null) ds.send(new DatagramPacket(buf, len, a));
                }
            }
        } catch (Exception e) {
            log("UDP dừng: " + e);
        }
    }

    // ------------------------------------------------------------------ HELPERS
    static void log(String s) {
        System.out.println("[" + new SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) + "] " + s);
    }

    static String now() {
        return new SimpleDateFormat("HH:mm").format(new java.util.Date());
    }

    static void broadcastUsers() {
        String csv = String.join(",", new TreeSet<>(clients.keySet()));
        for (Handler h : clients.values()) h.send("USERS", csv);
    }

    static void sysAll(String text) {
        for (Handler h : clients.values()) h.send("SYS", text);
    }

    // ------------------------------------------------------------------ CLIENT HANDLER (1 thread / client)
    static class Handler extends Thread {
        final Socket sock;
        DataInputStream in;
        DataOutputStream out;
        String user;

        Handler(Socket s) {
            sock = s;
            setDaemon(true);
        }

        synchronized void send(String cmd, String... f) {
            try {
                out.writeUTF(cmd);
                for (String s : f) out.writeUTF(s);
                out.flush();
            } catch (IOException ignored) {
            }
        }

        synchronized void sendFile(String from, String label, String tg, String name, String kind, String time,
                                   byte[] data) {
            try {
                out.writeUTF("FILE");
                out.writeUTF(from);
                out.writeUTF(label);
                out.writeUTF(tg);
                out.writeUTF(name);
                out.writeUTF(kind);
                out.writeUTF(time);
                out.writeInt(data.length);
                out.write(data);
                out.flush();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void run() {
            try {
                in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
                if (!in.readUTF().equals("LOGIN")) return;
                String u = in.readUTF().trim(), p = in.readUTF();
                String err;
                if (!u.matches("[\\p{L}\\p{N}_.-]{1,20}")) err = "Tên không hợp lệ (1-20 ký tự, không có dấu cách)";
                else if (clients.containsKey(u)) err = "Tên này đang đăng nhập ở nơi khác";
                else err = auth(u, p);
                if (err != null) {
                    send("LOGIN_FAIL", err);
                    return;
                }
                user = u;
                send("LOGIN_OK", db != null ? "DB" : "NODB");
                clients.put(u, this);
                log(u + " đã kết nối từ " + sock.getRemoteSocketAddress());
                for (String h : history(u)) send("HIST", h);
                broadcastUsers();
                sysAll(u + " đã tham gia");

                while (true) {
                    String cmd = in.readUTF();
                    switch (cmd) {
                        case "MSG": {
                            String mode = in.readUTF(), tg = in.readUTF(), text = in.readUTF();
                            handleMsg(mode, tg, text);
                            break;
                        }
                        case "FILE": {
                            String mode = in.readUTF(), tg = in.readUTF();
                            String name = new File(in.readUTF()).getName(), kind = in.readUTF();
                            int len = in.readInt();
                            if (len < 0 || len > MAX_FILE) {
                                long left = Math.max(len, 0);
                                while (left > 0) {
                                    long k = in.skip(left);
                                    if (k <= 0) break;
                                    left -= k;
                                }
                                send("SYS", "File quá lớn (tối đa 20MB)");
                                break;
                            }
                            byte[] data = new byte[len];
                            in.readFully(data);
                            handleFile(mode, tg, name, kind, data);
                            break;
                        }
                        case "CALL": { // bắt đầu stream UDP: "" = tất cả, hoặc danh sách người nhận
                            String tg = in.readUTF();
                            Set<String> set = ConcurrentHashMap.newKeySet();
                            for (String n : tg.split(",")) if (!n.isEmpty()) set.add(n);
                            callTargets.put(user, set);
                            break;
                        }
                        case "CALLSTOP":
                            callTargets.remove(user);
                            break;
                        default:
                            break;
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (user != null && clients.get(user) == this) {
                    clients.remove(user);
                    udpAddrs.remove(user);
                    callTargets.remove(user);
                    broadcastUsers();
                    sysAll(user + " đã thoát");
                    log(user + " đã thoát");
                }
                try {
                    sock.close();
                } catch (IOException ignored) {
                }
            }
        }

        /** Xác định danh sách người nhận + nhãn (BROADCAST / MULTICAST / PRIVATE). */
        private String resolve(String mode, String tg, List<Handler> rs) {
            if (mode.equals("ALL")) {
                for (Handler h : clients.values()) if (h != this) rs.add(h);
                return "BROADCAST";
            }
            List<String> names = new ArrayList<>();
            for (String n : tg.split(",")) if (!n.trim().isEmpty()) names.add(n.trim());
            for (String n : names) {
                Handler h = clients.get(n);
                if (h == null) send("SYS", "'" + n + "' đang offline");
                else if (h != this) rs.add(h);
            }
            return names.size() == 1 ? "PRIVATE" : "MULTICAST";
        }

        void handleMsg(String mode, String tg, String text) {
            List<Handler> rs = new ArrayList<>();
            String label = resolve(mode, tg, rs);
            String time = now();
            for (Handler h : rs) h.send("MSG", user, label, tg, text, time);
            saveMsg(user, label, tg, text, time);
            log(user + " [" + label + "] " + text);
        }

        void handleFile(String mode, String tg, String name, String kind, byte[] data) {
            List<Handler> rs = new ArrayList<>();
            String label = resolve(mode, tg, rs);
            String time = now();
            for (Handler h : rs) h.sendFile(user, label, tg, name, kind, time, data);
            try {
                java.nio.file.Files.write(new File("server_files", System.currentTimeMillis() + "_" + user + "_" + name).toPath(), data);
            } catch (IOException ignored) {
            }
            saveMsg(user, label, tg, "[" + kind + "] " + name, time);
            log(user + " [" + label + "] gửi " + kind + ": " + name + " (" + data.length / 1024 + " KB)");
        }
    }
}
