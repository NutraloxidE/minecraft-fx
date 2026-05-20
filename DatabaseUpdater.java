import com.google.gson.*;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * DatabaseUpdater.java — storage.json の executions を H2 DB へ移行するスクリプト。
 *
 * 実行方法 (Java 11+, --source モード):
 *   java -cp "h2-*.jar;gson-*.jar" --source 21 DatabaseUpdater.java [storageJson] [dbPrefix]
 *
 *   storageJson — storage.json のパス（省略時: paper-server/plugins/GekiyabaFX/storage.json）
 *   dbPrefix    — H2 DB プレフィックス（省略時: paper-server/plugins/GekiyabaFX/executions）
 *
 * ※ Minecraft サーバーを停止してから実行すること。
 */
public class DatabaseUpdater {

    public static void main(String[] args) throws Exception {
        String storagePath = args.length > 0 ? args[0]
                : "paper-server/plugins/GekiyabaFX/storage.json";
        String dbPrefix    = args.length > 1 ? args[1]
                : "paper-server/plugins/GekiyabaFX/executions";

        File storageFile = new File(storagePath).getAbsoluteFile();
        if (!storageFile.exists()) {
            System.err.println("[ERROR] storage.json が見つかりません: " + storageFile);
            System.exit(1);
        }

        // ── storage.json を読み込む ──────────────────────────────────────────
        String json      = Files.readString(storageFile.toPath());
        JsonObject root  = JsonParser.parseString(json).getAsJsonObject();
        JsonObject pairs = root.has("pairs") ? root.getAsJsonObject("pairs") : new JsonObject();

        record ExecRow(String pairId, long ts, String price, String amount) {}
        List<ExecRow> rows = new ArrayList<>();

        for (Map.Entry<String, JsonElement> entry : pairs.entrySet()) {
            String pairId      = entry.getKey();
            JsonObject pairObj = entry.getValue().getAsJsonObject();
            if (!pairObj.has("executions")) continue;
            for (JsonElement el : pairObj.getAsJsonArray("executions")) {
                JsonObject ex = el.getAsJsonObject();
                long   ts     = ex.get("timestamp").getAsLong();
                String price  = normalize(ex.get("price").getAsString());
                String amount = normalize(ex.get("amount").getAsString());
                rows.add(new ExecRow(pairId, ts, price, amount));
            }
        }

        if (rows.isEmpty()) {
            System.out.println("[INFO] 移行するデータがありません。");
            return;
        }
        System.out.println("[INFO] 移行対象: " + rows.size() + " 件");

        // ── H2 に接続 ────────────────────────────────────────────────────────
        String dbPath = new File(dbPrefix).getAbsolutePath();
        String url    = "jdbc:h2:file:" + dbPath + ";AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=TRUE";
        System.out.println("[INFO] H2 に接続: " + url);

        Class.forName("org.h2.Driver");
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            conn.setAutoCommit(false);

            try (Statement st = conn.createStatement()) {
                st.execute(
                    "CREATE TABLE IF NOT EXISTS executions (" +
                    "  id      BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "  pair_id VARCHAR(128) NOT NULL," +
                    "  ts      BIGINT       NOT NULL," +
                    "  price   VARCHAR(32)  NOT NULL," +
                    "  amount  VARCHAR(32)  NOT NULL" +
                    ")"
                );
                st.execute(
                    "CREATE INDEX IF NOT EXISTS idx_exec_pair_ts ON executions(pair_id, ts)"
                );
            }

            // 既存レコードセット（重複スキップ用）
            Set<String> existing = new HashSet<>();
            try (Statement st  = conn.createStatement();
                 ResultSet rs  = st.executeQuery(
                         "SELECT pair_id, ts, price, amount FROM executions")) {
                while (rs.next()) {
                    existing.add(
                        rs.getString(1) + "|" + rs.getLong(2) + "|" +
                        rs.getString(3) + "|" + rs.getString(4)
                    );
                }
            }
            System.out.println("[INFO] 既存レコード: " + existing.size() + " 件（重複はスキップ）");

            int inserted = 0, skipped = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO executions (pair_id, ts, price, amount) VALUES (?, ?, ?, ?)")) {
                for (ExecRow r : rows) {
                    String key = r.pairId() + "|" + r.ts() + "|" + r.price() + "|" + r.amount();
                    if (existing.contains(key)) { skipped++; continue; }
                    ps.setString(1, r.pairId());
                    ps.setLong(2, r.ts());
                    ps.setString(3, r.price());
                    ps.setString(4, r.amount());
                    ps.executeUpdate();
                    existing.add(key);
                    inserted++;
                }
            }
            conn.commit();
            System.out.println("[INFO] 完了: INSERT " + inserted + " 件 / スキップ " + skipped + " 件");
        }

        System.out.println();
        System.out.println("─".repeat(60));
        System.out.println("移行が完了しました。サーバーを起動してください。");
        System.out.println("storage.json の executions フィールドはそのまま残りますが、");
        System.out.println("プラグインは今後 H2 のみを参照します。");
        System.out.println("─".repeat(60));
    }

    /** BigDecimal 正規化（scale=4, HALF_UP） */
    static String normalize(String s) {
        return new BigDecimal(s).setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
