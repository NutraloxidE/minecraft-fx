package com.gekiyabafx.web;

import com.gekiyabafx.GekiyabaFXPlugin;
import com.gekiyabafx.config.PluginConfig;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.util.logging.Logger;

/**
 * Javalin 組み込み Web サーバーのラッパー。
 *
 * <h3>責務</h3>
 * <ul>
 *   <li>静的ファイル配信 — クラスパス内の {@code /www} ディレクトリを配信する。</li>
 *   <li>SPA フォールバック — API パス以外の全リクエストを {@code /www/index.html} へ転送する。</li>
 *   <li>CORS — {@code dev-mode: true} のときは全オリジンを許可し、
 *       本番時は {@code server-ip:web-port} のオリジンのみ許可する。</li>
 *   <li>Javalin インスタンスを公開し、Step 11 以降のエンドポイント登録に使用できるようにする。</li>
 * </ul>
 *
 * <p>使い方:</p>
 * <pre>{@code
 * webServer = new WebServer(plugin);
 * webServer.start();
 * // Step 11: webServer.getApp().post("/api/auth", ctx -> { ... });
 * // onDisable:
 * webServer.stop();
 * }</pre>
 */
public final class WebServer {

    private final Javalin app;
    private final String  bindIp;
    private final int     bindPort;
    private final Logger  logger;

    /**
     * WebServer を構築する。{@link #start()} を呼ぶまでサーバーは起動しない。
     *
     * @param plugin プラグインインスタンス（設定・ロガー取得用）
     */
    public WebServer(GekiyabaFXPlugin plugin) {
        PluginConfig cfg = plugin.getPluginConfig();
        this.bindIp   = cfg.getServerIp();
        this.bindPort = cfg.getWebPort();
        this.logger   = plugin.getLogger();

        app = Javalin.create(config -> {

            // ── CORS ─────────────────────────────────────────────────────────
            config.bundledPlugins.enableCors(cors ->
                cors.addRule(rule -> {
                    if (cfg.isDevMode()) {
                        // 開発時: ブラウザの開発サーバー（任意オリジン）を許可する
                        rule.anyHost();
                    } else {
                        // 本番時: 設定されたオリジンのみ許可する
                        rule.allowHost(
                            "http://"  + bindIp + ":" + bindPort,
                            "https://" + bindIp + ":" + bindPort
                        );
                    }
                })
            );

            // ── 静的ファイル配信 ──────────────────────────────────────────────
            // クラスパス内の /www ディレクトリを / へマッピングする。
            // Step 18 の Vite ビルド成果物を src/main/resources/www/ へコピーすることで更新される。
            config.staticFiles.add("/www", Location.CLASSPATH);

            // ── SPA フォールバック ────────────────────────────────────────────
            // /api/* にマッチしなかった全パスを index.html にフォールバックさせる。
            // これにより React Router のクライアントサイドルーティングが機能する。
            config.spaRoot.addFile("/", "/www/index.html", Location.CLASSPATH);

            // ── リクエストロガー（dev-mode のみ）─────────────────────────────
            if (cfg.isDevMode()) {
                config.requestLogger.http((ctx, ms) ->
                    logger.info("[WebServer] " + ctx.method() + " " + ctx.path()
                            + " -> " + ctx.status() + " (" + ms.intValue() + "ms)")
                );
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ライフサイクル
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * サーバーを起動する。{@code onEnable()} から呼ぶこと。
     *
     * <p>{@link PluginConfig#getServerIp()} と {@link PluginConfig#getWebPort()} に
     * バインドする。</p>
     */
    public void start() {
        app.start(bindIp, bindPort);
        logger.info("[WebServer] 起動しました — http://" + bindIp + ":" + bindPort + "/");
    }

    /**
     * サーバーを停止する。{@code onDisable()} から呼ぶこと。
     * すでに停止済みの場合は何もしない。
     */
    public void stop() {
        try {
            app.stop();
            logger.info("[WebServer] 停止しました。");
        } catch (Exception e) {
            logger.warning("[WebServer] 停止中に例外が発生しました: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  アクセサ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 内部の {@link Javalin} インスタンスを返す。
     * Step 11 以降のエンドポイント登録に使用する。
     *
     * @return {@link Javalin} インスタンス
     */
    public Javalin getApp() {
        return app;
    }
}
