package com.gekiyabafx.storage;

import com.gekiyabafx.model.Execution;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * H2 ファイルDBを使った約定履歴リポジトリ。
 *
 * <h3>テーブル定義</h3>
 * <pre>{@code
 * CREATE TABLE IF NOT EXISTS executions (
 *   id       BIGINT AUTO_INCREMENT PRIMARY KEY,
 *   pair_id  VARCHAR(128) NOT NULL,
 *   ts       BIGINT       NOT NULL,
 *   price    VARCHAR(32)  NOT NULL,
 *   amount   VARCHAR(32)  NOT NULL
 * );
 * CREATE INDEX IF NOT EXISTS idx_exec_pair_ts ON executions(pair_id, ts);
 * }</pre>
 *
 * <p>H2 はファイルベースで動作し、JVM 内で完結するため外部デーモン不要。
 * テスト時は {@code "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1"} に差し替えることで
 * インメモリ動作が可能。</p>
 *
 * <p>本クラスはスレッドセーフ。{@link StorageManager} のロック内外どちらから呼ばれても
 * 問題ない（H2 の内部ロックで排他制御される）。</p>
 */
public final class H2ExecutionRepository implements ExecutionRepository {

    private final Connection conn;
    private final Logger logger;

    /**
     * H2 データベースに接続し、テーブルを初期化する。
     *
     * @param dbPath  DB ファイルパス（拡張子 {@code .mv.db} は H2 が自動付与）
     *                例: {@code "/path/to/plugins/GekiyabaFX/executions"}
     * @param logger  プラグインロガー
     * @throws SQLException 接続またはテーブル作成に失敗した場合
     */
    public H2ExecutionRepository(String dbPath, Logger logger) throws SQLException {
        this.logger = logger;
        // Paper のプラグインクラスローダーでは DriverManager が H2 を自動検出できないため、
        // Driver を直接インスタンス化して接続する（relocation も不要）
        String url = "jdbc:h2:file:" + dbPath + ";AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE";
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", "sa");
        props.setProperty("password", "");
        org.h2.Driver h2Driver = new org.h2.Driver();
        this.conn = h2Driver.connect(url, props);
        if (this.conn == null) {
            throw new SQLException("H2 Driver が URL を受け付けませんでした: " + url);
        }
        initSchema();
        logger.info("H2ExecutionRepository を初期化しました: " + dbPath);
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS executions (" +
                "  id      BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  pair_id VARCHAR(128) NOT NULL," +
                "  ts      BIGINT       NOT NULL," +
                "  price   VARCHAR(32)  NOT NULL," +
                "  amount  VARCHAR(32)  NOT NULL," +
                "  atm_id  VARCHAR(64)," +
                "  atm_grade VARCHAR(20)" +
                ")"
            );
            try {
                st.execute("ALTER TABLE executions ADD COLUMN IF NOT EXISTS atm_id VARCHAR(64)");
                st.execute("ALTER TABLE executions ADD COLUMN IF NOT EXISTS atm_grade VARCHAR(20)");
            } catch (SQLException ignored) {
                // 既存DB互換用。CREATE時に定義済みなら無視する。
            }
            st.execute(
                "CREATE INDEX IF NOT EXISTS idx_exec_pair_ts ON executions(pair_id, ts)"
            );
        }
    }

    /**
     * 1件の約定を INSERT する。
     *
     * @param pairId ペアID
     * @param ex     約定レコード
     */
    @Override
    public synchronized void insert(String pairId, Execution ex) {
        String sql = "INSERT INTO executions (pair_id, ts, price, amount, atm_id, atm_grade) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pairId);
            ps.setLong(2, ex.getTimestamp());
            ps.setString(3, ex.getPrice().setScale(4, java.math.RoundingMode.HALF_UP).toPlainString());
            ps.setString(4, ex.getAmount().setScale(4, java.math.RoundingMode.HALF_UP).toPlainString());
            ps.setString(5, ex.getAtmId());
            ps.setString(6, ex.getAtmGrade());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("約定履歴の INSERT に失敗しました [pair=" + pairId + "]: " + e.getMessage());
        }
    }

    /**
     * 指定ペアの約定履歴を {@code since} より新しい順で返す。
     *
     * @param pairId ペアID
     * @param since  この Unix 秒より大きいタイムスタンプのみ（0 で全件）
     * @return タイムスタンプ昇順の約定リスト
     */
    @Override
    public synchronized List<Execution> findByPairSince(String pairId, long since) {
        String sql = "SELECT ts, price, amount, atm_id, atm_grade FROM executions " +
                     "WHERE pair_id = ? AND ts > ? ORDER BY ts ASC, id ASC";
        List<Execution> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pairId);
            ps.setLong(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ts       = rs.getLong("ts");
                    BigDecimal p  = new BigDecimal(rs.getString("price"));
                    BigDecimal a  = new BigDecimal(rs.getString("amount"));
                    String atmId = rs.getString("atm_id");
                    String atmGrade = rs.getString("atm_grade");
                    result.add(new Execution(ts, p, a, atmId, atmGrade));
                }
            }
        } catch (SQLException e) {
            logger.severe("約定履歴の SELECT に失敗しました [pair=" + pairId + "]: " + e.getMessage());
        }
        return result;
    }

    /**
     * DB 接続を閉じる。{@code onDisable()} から呼ぶこと。
     */
    @Override
    public synchronized void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
                logger.info("H2ExecutionRepository の接続を閉じました。");
            }
        } catch (SQLException e) {
            logger.warning("H2ExecutionRepository のクローズに失敗しました: " + e.getMessage());
        }
    }
}
