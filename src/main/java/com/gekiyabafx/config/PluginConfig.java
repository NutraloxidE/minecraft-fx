package com.gekiyabafx.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * {@code config.yml} の全設定値をメモリ上に保持するイミュータブルなデータクラス。
 *
 * <p>インスタンスは {@link #load(FileConfiguration)} で生成する。
 * {@code GekiyabaFXPlugin#onEnable()} および {@code /fx reload} の両方から呼び出される。
 * フィールドはすべて {@code final} で、生成後は一切変更できない。
 * リロード時はこのクラスの新規インスタンスで旧インスタンスを置き換える。</p>
 */
public final class PluginConfig {

    // ─── Webサーバー ──────────────────────────────────────────────────────────

    /** 内蔵WebサーバーがリッスンするTCPポート番号。デフォルト: {@code 3010} */
    private final int webPort;

    /**
     * 開発モードフラグ。{@code true} の場合、Javalin の CORS を {@code anyHost()} で開放する。
     * 本番環境では {@code false} にすること。デフォルト: {@code false}
     */
    private final boolean devMode;

    // ─── 認証 ─────────────────────────────────────────────────────────────────

    /**
     * OTP の有効期限（秒）。プレイヤー・管理者双方に共通で適用される。
     * デフォルト: {@code 300}（5分）
     */
    private final long otpExpireSeconds;

    /**
     * セッショントークンの有効期限（秒）。
     * デフォルト: {@code 1800}（30分）
     */
    private final long sessionExpireSeconds;

    // ─── 履歴保持件数 ─────────────────────────────────────────────────────────

    /**
     * {@code executions}（約定履歴）の最大保持件数（ペアごと）。
     * 超過分は古い順に削除される。デフォルト: {@code 10000}
     */
    private final int executionsMaxPerPair;

    /**
     * {@code order_history}（注文履歴）の最大保持件数（ペアごと）。
     * 超過分は古い順に削除される。デフォルト: {@code 500}
     */
    private final int orderHistoryMaxPerPair;

    // ─── コンストラクタ（private — ファクトリメソッド経由でのみ生成） ──────────

    private PluginConfig(
            int webPort,
            boolean devMode,
            long otpExpireSeconds,
            long sessionExpireSeconds,
            int executionsMaxPerPair,
            int orderHistoryMaxPerPair
    ) {
        this.webPort                = webPort;
        this.devMode                = devMode;
        this.otpExpireSeconds       = otpExpireSeconds;
        this.sessionExpireSeconds   = sessionExpireSeconds;
        this.executionsMaxPerPair   = executionsMaxPerPair;
        this.orderHistoryMaxPerPair = orderHistoryMaxPerPair;
    }

    // ─── ファクトリメソッド ────────────────────────────────────────────────────

    /**
     * {@link FileConfiguration}（Bukkit の {@code config.yml} ラッパー）から
     * {@link PluginConfig} を生成する。
     *
     * <p>設定ファイルに存在しないキーはデフォルト値にフォールバックする。
     * デフォルト値は {@code src/main/resources/config.yml} の値と一致させること。</p>
     *
     * @param cfg Bukkit が読み込んだ {@link FileConfiguration}。{@code null} 不可。
     * @return 設定値を保持する {@link PluginConfig} インスタンス
     * @throws IllegalArgumentException 設定値が不正な場合（ポート範囲外・負数など）
     */
    public static PluginConfig load(FileConfiguration cfg) {
        int webPort = cfg.getInt("web-port", 3010);
        if (webPort < 1 || webPort > 65535) {
            throw new IllegalArgumentException(
                    "config.yml: web-port の値が不正です（有効範囲: 1–65535）: " + webPort);
        }

        boolean devMode = cfg.getBoolean("dev-mode", false);

        long otpExpireSeconds = cfg.getLong("otp-expire-seconds", 300L);
        if (otpExpireSeconds < 1) {
            throw new IllegalArgumentException(
                    "config.yml: otp-expire-seconds は 1 以上にしてください: " + otpExpireSeconds);
        }

        long sessionExpireSeconds = cfg.getLong("session-expire-seconds", 1800L);
        if (sessionExpireSeconds < 1) {
            throw new IllegalArgumentException(
                    "config.yml: session-expire-seconds は 1 以上にしてください: " + sessionExpireSeconds);
        }

        int executionsMaxPerPair = cfg.getInt("executions-max-per-pair", 10000);
        if (executionsMaxPerPair < 1) {
            throw new IllegalArgumentException(
                    "config.yml: executions-max-per-pair は 1 以上にしてください: " + executionsMaxPerPair);
        }

        int orderHistoryMaxPerPair = cfg.getInt("order-history-max-per-pair", 500);
        if (orderHistoryMaxPerPair < 1) {
            throw new IllegalArgumentException(
                    "config.yml: order-history-max-per-pair は 1 以上にしてください: " + orderHistoryMaxPerPair);
        }

        return new PluginConfig(
                webPort,
                devMode,
                otpExpireSeconds,
                sessionExpireSeconds,
                executionsMaxPerPair,
                orderHistoryMaxPerPair
        );
    }

    // ─── ゲッター ──────────────────────────────────────────────────────────────

    /** @return 内蔵WebサーバーのTCPポート番号 */
    public int getWebPort() {
        return webPort;
    }

    /** @return 開発モードが有効かどうか */
    public boolean isDevMode() {
        return devMode;
    }

    /** @return OTP の有効期限（秒） */
    public long getOtpExpireSeconds() {
        return otpExpireSeconds;
    }

    /** @return セッショントークンの有効期限（秒） */
    public long getSessionExpireSeconds() {
        return sessionExpireSeconds;
    }

    /** @return executions の最大保持件数（ペアごと） */
    public int getExecutionsMaxPerPair() {
        return executionsMaxPerPair;
    }

    /** @return order_history の最大保持件数（ペアごと） */
    public int getOrderHistoryMaxPerPair() {
        return orderHistoryMaxPerPair;
    }

    // ─── デバッグ用 toString ───────────────────────────────────────────────────

    @Override
    public String toString() {
        return "PluginConfig{"
                + "webPort=" + webPort
                + ", devMode=" + devMode
                + ", otpExpireSeconds=" + otpExpireSeconds
                + ", sessionExpireSeconds=" + sessionExpireSeconds
                + ", executionsMaxPerPair=" + executionsMaxPerPair
                + ", orderHistoryMaxPerPair=" + orderHistoryMaxPerPair
                + '}';
    }
}
