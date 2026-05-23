package com.gekiyabafx;

import com.gekiyabafx.arbitrage.ArbitrageService;
import com.gekiyabafx.atm.AtmSessionManager;
import com.gekiyabafx.auth.OtpManager;
import com.gekiyabafx.auth.SessionManager;
import com.gekiyabafx.command.FxCommandExecutor;
import com.gekiyabafx.config.PluginConfig;
import com.gekiyabafx.listener.AtmSignListener;
import com.gekiyabafx.listener.PlayerJoinListener;
import com.gekiyabafx.storage.ExecutionRepository;
import com.gekiyabafx.storage.H2ExecutionRepository;
import com.gekiyabafx.storage.StorageManager;
import com.gekiyabafx.web.AuthRouter;
import com.gekiyabafx.web.AdminApiRouter;
import com.gekiyabafx.web.DepositWithdrawRouter;
import com.gekiyabafx.web.PlayerApiRouter;
import com.gekiyabafx.web.PublicApiRouter;
import com.gekiyabafx.web.WebServer;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * GekiyabaFX のエントリポイント。
 *
 * <p>責務:</p>
 * <ol>
 *   <li>{@code onEnable()} で {@code config.yml} をロードし {@link PluginConfig} を初期化する。</li>
 *   <li>{@code StorageManager} を初期化して {@code storage.json} をロードする。</li>
 *   <li>{@link OtpManager}（プレイヤー用）を生成し {@link FxCommandExecutor} に渡す。</li>
 *   <li>{@code /fx} コマンドに {@link FxCommandExecutor} を登録する。</li>
 *   <li>Step 9 以降で PlayerJoinListener・Javalin を追加する。</li>
 * </ol>
 */
public final class GekiyabaFXPlugin extends JavaPlugin {

    /** シングルトンインスタンス（他クラスから静的アクセス用）。 */
    private static GekiyabaFXPlugin instance;

    /** 現在有効な設定値。{@code /fx reload} で差し替えられる。 */
    private PluginConfig pluginConfig;

    /** プレイヤー用OTPマネージャー。コマンド実行クラスと認証APIで共有する。 */
    private OtpManager playerOtpManager;
    /** 管理者用OTPマネージャー。コマンド実行クラスと認証アピイで共有する。 */
    private OtpManager adminOtpManager;

    /** プレイヤー用セッションマネージャー。Step 11 の認証エンドポイントから参照する。 */
    private SessionManager playerSessionManager;

    /** ATM 起点 OTP と Web セッションを紐付けるマネージャー。 */
    private AtmSessionManager atmSessionManager;

    /** ATM 占有状態と看板表示を管理するリスナー。停止時の全解放に使用する。 */
    private AtmSignListener atmSignListener;

    /** 管理者用セッションマネージャー。Step 11 の認証エンドポイントから参照する。 */
    private SessionManager adminSessionManager;

    /** 組み込み Web サーバー。Step 11 以降でエンドポイントを追加する。 */
    private WebServer webServer;

    /** 約定履歴を H2 に永続化するリポジトリ。 */
    private ExecutionRepository executionRepo;

    /** 裁定取引監視サービス。 */
    private ArbitrageService arbitrageService;
    // ─────────────────────────────────────────────────────────────────────────
    //  静的アクセサ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * プラグインのシングルトンインスタンスを返す。
     *
     * @return {@link GekiyabaFXPlugin} のインスタンス
     * @throws IllegalStateException プラグインが初期化される前に呼ばれた場合
     */
    public static GekiyabaFXPlugin getInstance() {
        if (instance == null) {
            throw new IllegalStateException("GekiyabaFXPlugin はまだ初期化されていません。");
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ライフサイクル
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        instance = this;

        // ① config.yml をロードする（存在しない場合は jar 内のデフォルトをコピー）
        loadPluginConfig();
        getLogger().info("GekiyabaFX が有効化されました（v" + getDescription().getVersion() + "）。");
        getLogger().info("設定: " + pluginConfig);

        // ② StorageManager を初期化する（storage.json のロードを含む）
        StorageManager.initialize(getDataFolder(), getLogger());
        getLogger().info("StorageManager を初期化しました: " + StorageManager.getInstance().getData());

        // ③ H2ExecutionRepository を初期化する
        String dbPath = getDataFolder().getAbsolutePath() + "/executions";
        try {
            executionRepo = new H2ExecutionRepository(dbPath, getLogger());
        } catch (java.sql.SQLException e) {
            getLogger().severe("H2ExecutionRepository の初期化に失敗しました: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ④ OtpManager ・ SessionManager を生成する（config の expire 値を使用）
        playerOtpManager    = new OtpManager(pluginConfig.getOtpExpireSeconds());
        adminOtpManager     = new OtpManager(pluginConfig.getOtpExpireSeconds());
        playerSessionManager = new SessionManager(pluginConfig.getSessionExpireSeconds());
        adminSessionManager  = new SessionManager(pluginConfig.getSessionExpireSeconds());
        atmSessionManager    = new AtmSessionManager();

        // ⑤ /fx コマンドに FxCommandExecutor を登録する
        FxCommandExecutor executor = new FxCommandExecutor(this, playerOtpManager, adminOtpManager);
        var cmd = getCommand("fx");
        if (cmd != null) {
            cmd.setExecutor(executor);
        } else {
            getLogger().severe("plugin.yml に 'fx' コマンドが定義されていません！");
        }

        // ⑦ PlayerJoinListener を登録する（pending_deposit 回収・pending_withdraw 付与）
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        atmSignListener = new AtmSignListener(this, playerOtpManager, atmSessionManager);
        getServer().getPluginManager().registerEvents(atmSignListener, this);
        getServer().getScheduler().runTaskTimer(this, atmSignListener::releaseTimedOutOccupancy, 20L * 30L, 20L * 30L);

        // ⑧ Javalin Web サーバーを起動する（静的ファイル配信・SPA フォールバック・CORS）
        webServer = new WebServer(this);
        webServer.start();

        // ⑨ 認証エンドポイントを登録する（POST /api/auth ・ POST /api/admin/auth）
        new AuthRouter(
                playerOtpManager,
                adminOtpManager,
                playerSessionManager,
            adminSessionManager,
            atmSessionManager
        ).register(webServer.getApp());

        // ⑩ 公開 API エンドポイントを登録する（GET /api/pairs ・ /api/orderbook ・ /api/executions）
        new PublicApiRouter(executionRepo, pluginConfig).register(webServer.getApp());

        // ② プレイヤー API エンドポイントを登録する
        //    GET /api/state ・ POST /api/order ・ DELETE /api/order/:id
        new PlayerApiRouter(playerSessionManager, atmSessionManager, pluginConfig, executionRepo).register(webServer.getApp());

        // ⑯ 入出金 API エンドポイントを登録する
        //    POST /api/deposit ・ POST /api/withdraw
        new DepositWithdrawRouter(this, playerSessionManager, atmSessionManager).register(webServer.getApp());

        // ⑰ 管理者 API エンドポイントを登録する
        //    GET|POST /api/admin/pairs ・ PATCH|DELETE /api/admin/pairs/:id
        arbitrageService = new ArbitrageService(this, executionRepo);
        arbitrageService.start();

        new AdminApiRouter(adminSessionManager, this, arbitrageService).register(webServer.getApp());
    }

    @Override
    public void onDisable() {
        getLogger().info("GekiyabaFX が無効化されました。");

        // Javalin Web サーバーを停止する
        if (webServer != null) {
            webServer.stop();
        }

        // H2ExecutionRepository の接続を閉じる
        if (executionRepo != null) {
            executionRepo.close();
        }

        if (arbitrageService != null) {
            arbitrageService.stop();
        }

        if (atmSignListener != null) {
            atmSignListener.releaseAllOccupancyForShutdown();
        }

        // StorageManager をシャットダウンし、未書き込みデータを同期フラッシュする
        try {
            StorageManager sm = StorageManager.getInstance();
            sm.shutdown();
        } catch (IllegalStateException e) {
            // onEnable が失敗した場合など、StorageManager が未初期化の可能性がある
            getLogger().warning("StorageManager のシャットダウンをスキップしました: " + e.getMessage());
        }

        instance = null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  内部ヘルパー
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code config.yml} をディスクから読み込み、{@link PluginConfig} を（再）生成する。
     *
     * <p>{@code saveDefaultConfig()} により、プラグインデータフォルダに
     * {@code config.yml} が存在しない場合は jar 内のデフォルトが自動コピーされる。</p>
     *
     * @throws IllegalArgumentException {@link PluginConfig#load} が設定値の不正を検出した場合
     */
    private void loadPluginConfig() {
        saveDefaultConfig();
        reloadConfig();
        pluginConfig = PluginConfig.load(getConfig());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  公開メソッド
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code config.yml} を再読み込みして {@link PluginConfig} を更新する。
     * {@link FxCommandExecutor} の {@code /fx reload} から呼ばれる。
     *
     * @throws IllegalArgumentException 設定値が不正な場合
     */
    public void reloadPluginConfig() {
        loadPluginConfig();
    }

    /**
     * 現在有効な {@link PluginConfig} を返す。
     *
     * @return 現在の設定インスタンス
     */
    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    /**
     * プレイヤー用 {@link OtpManager} を返す。
     * Step 11 の認証APIエンドポイントから参照する。
     *
     * @return プレイヤー用 {@link OtpManager}
     */
    public OtpManager getPlayerOtpManager() {
        return playerOtpManager;
    }
    /**
     * 管理者用 {@link OtpManager} を返す。
     * Step 11 の認証アピイエンドポイントから参照する。
     *
     * @return 管理者用 {@link OtpManager}
     */
    public OtpManager getAdminOtpManager() {
        return adminOtpManager;
    }

    /**
     * プレイヤー用 {@link SessionManager} を返す。
     * Step 11 の認証アピイエンドポイントから参照する。
     *
     * @return プレイヤー用 {@link SessionManager}
     */
    public SessionManager getPlayerSessionManager() {
        return playerSessionManager;
    }

    /**
     * 管理者用 {@link SessionManager} を返す。
     * Step 11 の認証アピイエンドポイントから参照する。
     *
     * @return 管理者用 {@link SessionManager}
     */
    public SessionManager getAdminSessionManager() {
        return adminSessionManager;
    }

    public AtmSessionManager getAtmSessionManager() {
        return atmSessionManager;
    }

    public AtmSignListener getAtmSignListener() {
        return atmSignListener;
    }

    public ArbitrageService getArbitrageService() {
        return arbitrageService;
    }
}
