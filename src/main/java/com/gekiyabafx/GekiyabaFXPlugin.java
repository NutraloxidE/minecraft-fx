package com.gekiyabafx;

import com.gekiyabafx.config.PluginConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * GekiyabaFX のエントリポイント。
 *
 * <p>責務:</p>
 * <ol>
 *   <li>{@code onEnable()} で {@code config.yml} をロードし {@link PluginConfig} を初期化する。</li>
 *   <li>{@code /fx reload} コマンドで {@code config.yml} の再読み込みを提供する。</li>
 *   <li>Step 6 以降で StorageManager・Javalin の起動/停止をここに追加する。</li>
 * </ol>
 */
public final class GekiyabaFXPlugin extends JavaPlugin {

    /** シングルトンインスタンス（他クラスから静的アクセス用）。 */
    private static GekiyabaFXPlugin instance;

    /** 現在有効な設定値。{@code /fx reload} で差し替えられる。 */
    private PluginConfig pluginConfig;

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

        // config.yml をディスクからロード（存在しない場合は jar 内のデフォルトをコピー）
        loadPluginConfig();

        getLogger().info("GekiyabaFX が有効化されました（v" + getDescription().getVersion() + "）。");
        getLogger().info("設定: " + pluginConfig);

        // Step 6 以降: StorageManager 初期化をここに追加する。
        // Step 7 以降: FxCommandExecutor 登録をここに追加する。
        // Step 9 以降: PlayerJoinListener 登録をここに追加する。
        // Step 10 以降: Javalin 起動をここに追加する。
    }

    @Override
    public void onDisable() {
        getLogger().info("GekiyabaFX が無効化されました。");

        // Step 6 以降: StorageManager のフラッシュ（同期書き込み）をここに追加する。
        // Step 10 以降: Javalin の停止をここに追加する。

        instance = null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  コマンドハンドラ（plugin.yml で登録した "fx" コマンドのルーター）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code /fx <subcommand>} のエントリポイント。
     *
     * <p>各サブコマンドの本実装は Step 7・8 で専用クラスに委譲するが、
     * {@code reload} サブコマンドはプラグイン本体の責務であるためここで処理する。</p>
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("fx")) {
            return false;
        }

        if (args.length == 0) {
            sender.sendMessage("§e[GekiyabaFX] §f使用法: /" + label + " <login|admin|reload>");
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "reload" -> handleReload(sender);

            // Step 7 で /fx login の実装クラスに委譲する。
            case "login" -> sender.sendMessage("§e[GekiyabaFX] §c/fx login は現在未実装です（Step 7 で追加されます）。");

            // Step 8 で /fx admin の実装クラスに委譲する。
            case "admin" -> sender.sendMessage("§e[GekiyabaFX] §c/fx admin は現在未実装です（Step 8 で追加されます）。");

            default -> sender.sendMessage("§e[GekiyabaFX] §f不明なサブコマンドです: " + sub);
        }

        return true;
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
        // jar 内の config.yml をプラグインデータフォルダへコピー（初回のみ）
        saveDefaultConfig();

        // Bukkit の設定キャッシュを破棄して最新ファイルを読み直す
        reloadConfig();

        // PluginConfig インスタンスを生成（バリデーション込み）
        pluginConfig = PluginConfig.load(getConfig());
    }

    /**
     * {@code /fx reload} サブコマンドの処理。
     * {@code gekiyabafx.reload} 権限を持つ送信者のみ実行可能。
     *
     * @param sender コマンド送信者
     */
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("gekiyabafx.reload")) {
            sender.sendMessage("§c[GekiyabaFX] このコマンドを実行する権限がありません。");
            return;
        }

        try {
            loadPluginConfig();
            sender.sendMessage("§a[GekiyabaFX] config.yml を再読み込みしました。");
            getLogger().info("config.yml を再読み込みしました: " + pluginConfig);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§c[GekiyabaFX] config.yml の値が不正です: " + e.getMessage());
            getLogger().severe("config.yml の再読み込みに失敗しました: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  公開ゲッター
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 現在有効な {@link PluginConfig} を返す。
     *
     * @return 現在の設定インスタンス
     */
    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }
}
