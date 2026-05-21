package com.gekiyabafx.config;

import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** ログインURLに使用するサーバーのIPアドレスまたはドメイン名。デフォルト: {@code "127.0.0.1"} */
    private final String serverIp;

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

    /**
     * Maker（板残り注文側）の手数料率。
     * config.yml の {@code fee.maker}。デフォルト: 0.0010 (0.10%)
     */
    private final BigDecimal feeMaker;

    /**
     * Taker（新規注文側）の手数料率。
     * config.yml の {@code fee.taker}。デフォルト: 0.0012 (0.12%)
     */
    private final BigDecimal feeTaker;

    /**
     * 通貨キーごとの手数料率オーバーライド。
     * キー: アイテム名（例: {@code "TempKey"}）、値: 手数料率。
     * config.yml の {@code feeOverrides} マップに対応する。
     */
    private final Map<String, BigDecimal> feeOverrides;

    /**
     * /fx login-as で使用できる Service アカウント名の許可リスト（svc: プレフィックスなし）。
     * config.yml の {@code serviceAccounts} リストに対応する。
     */
    private final List<String> serviceAccounts;

    // ─── コンストラクタ（private — ファクトリメソッド経由でのみ生成） ──────────

    private PluginConfig(
            String serverIp,
            int webPort,
            boolean devMode,
            long otpExpireSeconds,
            long sessionExpireSeconds,
            int executionsMaxPerPair,
            int orderHistoryMaxPerPair,
            BigDecimal feeMaker,
            BigDecimal feeTaker,
            Map<String, BigDecimal> feeOverrides,
            List<String> serviceAccounts
    ) {
        this.serverIp              = serverIp;
        this.webPort                = webPort;
        this.devMode                = devMode;
        this.otpExpireSeconds       = otpExpireSeconds;
        this.sessionExpireSeconds   = sessionExpireSeconds;
        this.executionsMaxPerPair   = executionsMaxPerPair;
        this.orderHistoryMaxPerPair = orderHistoryMaxPerPair;
        this.feeMaker               = feeMaker;
        this.feeTaker               = feeTaker;
        this.feeOverrides           = Collections.unmodifiableMap(feeOverrides);
        this.serviceAccounts        = Collections.unmodifiableList(serviceAccounts);
    }

    // ─── テスト用ファクトリ ────────────────────────────────────────────────────

    /**
     * テスト専用ファクトリ。Paper API に依存せず直接パラメータを指定して生成する。
     */
    public static PluginConfig forTest(
            String serverIp,
            int webPort,
            boolean devMode,
            long otpExpireSeconds,
            long sessionExpireSeconds,
            int executionsMaxPerPair,
            int orderHistoryMaxPerPair
    ) {
        return new PluginConfig(serverIp, webPort, devMode,
                otpExpireSeconds, sessionExpireSeconds,
                executionsMaxPerPair, orderHistoryMaxPerPair,
                new BigDecimal("0.0010"), new BigDecimal("0.0012"),
                Collections.emptyMap(), Collections.emptyList());
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
    public static PluginConfig load(ConfigurationSection cfg) {
        String serverIp = cfg.getString("server-ip", "127.0.0.1");
        if (serverIp == null || serverIp.isBlank()) {
            throw new IllegalArgumentException(
                    "config.yml: server-ip が空です。IPアドレスまたはドメイン名を設定してください。");
        }

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

        List<String> serviceAccounts = cfg.getStringList("serviceAccounts");

        // fee.maker / fee.taker
        ConfigurationSection feeSection = cfg.getConfigurationSection("fee");
        BigDecimal feeMaker = new BigDecimal(feeSection != null
                ? feeSection.getString("maker", "0.0010") : "0.0010");
        BigDecimal feeTaker = new BigDecimal(feeSection != null
                ? feeSection.getString("taker", "0.0012") : "0.0012");

        // feeOverrides
        Map<String, BigDecimal> feeOverrides = new HashMap<>();
        ConfigurationSection overridesSection = cfg.getConfigurationSection("feeOverrides");
        if (overridesSection != null) {
            for (String key : overridesSection.getKeys(false)) {
                String val = overridesSection.getString(key);
                if (val != null) {
                    feeOverrides.put(key, new BigDecimal(val));
                }
            }
        }

        return new PluginConfig(
                serverIp,
                webPort,
                devMode,
                otpExpireSeconds,
                sessionExpireSeconds,
                executionsMaxPerPair,
                orderHistoryMaxPerPair,
                feeMaker,
                feeTaker,
                feeOverrides,
                serviceAccounts
        );
    }

    // ─── ゲッター ──────────────────────────────────────────────────────────────

    /** @return ログインURLに使用するサーバーのIPアドレスまたはドメイン名 */
    public String getServerIp() {
        return serverIp;
    }

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

    /** @return /fx login-as で許可された Service アカウント名リスト（svc: プレフィックスなし） */
    public List<String> getServiceAccounts() {
        return serviceAccounts;
    }

    /** @return Maker 手数料率 */
    public BigDecimal getFeeMaker() {
        return feeMaker;
    }

    /** @return Taker 手数料率 */
    public BigDecimal getFeeTaker() {
        return feeTaker;
    }

    /**
     * 指定通貨の手数料率を返す。
     * {@code feeOverrides} に登録があればその値、なければ {@code globalRate} を返す。
     *
     * @param currencyKey 通貨キー（例: {@code "diamond"}, {@code "TempKey"})
     * @param globalRate  フォールバックに使うグローバルレート（maker または taker）
     * @return 適用する手数料率
     */
    public BigDecimal resolveFeeRate(String currencyKey, BigDecimal globalRate) {
        return feeOverrides.getOrDefault(currencyKey, globalRate);
    }

    /** @return feeOverrides マップ（読み取り専用） */
    public Map<String, BigDecimal> getFeeOverrides() {
        return feeOverrides;
    }

    // ─── デバッグ用 toString ───────────────────────────────────────────────────

    @Override
    public String toString() {
        return "PluginConfig{"
                + "serverIp='" + serverIp + "'"
                + ", webPort=" + webPort
                + ", devMode=" + devMode
                + ", otpExpireSeconds=" + otpExpireSeconds
                + ", sessionExpireSeconds=" + sessionExpireSeconds
                + ", executionsMaxPerPair=" + executionsMaxPerPair
                + ", orderHistoryMaxPerPair=" + orderHistoryMaxPerPair
                + ", feeMaker=" + feeMaker
                + ", feeTaker=" + feeTaker
                + ", feeOverrides=" + feeOverrides
                + ", serviceAccounts=" + serviceAccounts
                + '}';
    }
}
