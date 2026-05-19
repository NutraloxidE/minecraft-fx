package com.gekiyabafx.command;

import com.gekiyabafx.GekiyabaFXPlugin;
import com.gekiyabafx.auth.OtpManager;
import com.gekiyabafx.model.PlayerData;
import com.gekiyabafx.model.StorageData;
import com.gekiyabafx.storage.StorageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /fx} コマンドのメインエグゼキューター。
 *
 * <p>サブコマンドのルーティングを行い、各処理に委譲する。</p>
 *
 * <ul>
 *   <li>{@code /fx login} — プレイヤー用OTP発行・ClickEvent送信（Step 7）</li>
 *   <li>{@code /fx admin} — 管理者用OTP発行（Step 8 で実装）</li>
 *   <li>{@code /fx reload} — config.yml 再読み込み</li>
 * </ul>
 */
public final class FxCommandExecutor implements CommandExecutor {

    /** プラグイン本体への参照。config 取得・ロガー取得に使用する。 */
    private final GekiyabaFXPlugin plugin;

    /** プレイヤー用OTPを管理する {@link OtpManager}。 */
    private final OtpManager playerOtpManager;

    /** 管理者用OTPを管理する {@link OtpManager}。 */
    private final OtpManager adminOtpManager;

    /**
     * コンストラクタ。
     *
     * @param plugin           プラグイン本体
     * @param playerOtpManager プレイヤー用 {@link OtpManager}
     * @param adminOtpManager  管理者用 {@link OtpManager}
     */
    public FxCommandExecutor(
            GekiyabaFXPlugin plugin,
            OtpManager playerOtpManager,
            OtpManager adminOtpManager
    ) {
        this.plugin            = plugin;
        this.playerOtpManager  = playerOtpManager;
        this.adminOtpManager   = adminOtpManager;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CommandExecutor
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("fx")) {
            return false;
        }

        if (args.length == 0) {
            sender.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("使用法: /" + label + " <login|admin|reload>",
                            NamedTextColor.WHITE)));
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "login"  -> handleLogin(sender);
            case "admin"  -> handleAdmin(sender);
            case "reload" -> handleReload(sender);
            default -> sender.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("不明なサブコマンドです: " + sub, NamedTextColor.RED)));
        }

        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  /fx login
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code /fx login} の処理。
     *
     * <ol>
     *   <li>コマンドをプレイヤーが実行したか確認する（コンソール不可）。</li>
     *   <li>{@code storage.json} の {@code players} に該当UUIDが存在しない場合、
     *       空のプレイヤーレコードを自動作成する。</li>
     *   <li>OTP を生成し、ゲーム内チャットにClickEvent付きURLを送信する。</li>
     * </ol>
     *
     * @param sender コマンド送信者
     */
    private void handleLogin(CommandSender sender) {
        // プレイヤーのみ実行可能
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("このコマンドはゲーム内でのみ使用できます。",
                            NamedTextColor.RED)));
            return;
        }

        // gekiyabafx.login 権限チェック
        if (!player.hasPermission("gekiyabafx.login")) {
            player.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("このコマンドを実行する権限がありません。",
                            NamedTextColor.RED)));
            return;
        }

        String uuid = player.getUniqueId().toString();
        String playerName = player.getName();

        // ─── プレイヤーレコードの自動作成 ──────────────────────────────────────
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            StorageData data = sm.getData();
            if (data.getPlayer(uuid) == null) {
                // 初回ログイン: 空のプレイヤーレコードを作成する
                PlayerData newPlayer = new PlayerData(playerName);
                data.getPlayers().put(uuid, newPlayer);
                sm.markDirty();
                plugin.getLogger().info("プレイヤーレコードを自動作成しました: " + playerName + " (" + uuid + ")");
            } else {
                // 既存プレイヤーの名前を最新に更新する
                PlayerData existing = data.getPlayer(uuid);
                if (!playerName.equals(existing.getName())) {
                    existing.setName(playerName);
                    sm.markDirty();
                }
            }
        } finally {
            sm.unlock();
        }

        // ─── OTP 生成 ──────────────────────────────────────────────────────────
        OtpManager.OtpEntry entry = playerOtpManager.generate(uuid);
        long expireMinutes = plugin.getPluginConfig().getOtpExpireSeconds() / 60;

        // ─── ログイン URL の構築 ────────────────────────────────────────────────
        String serverIp = plugin.getPluginConfig().getServerIp();
        int webPort     = plugin.getPluginConfig().getWebPort();
        String loginUrl = "http://" + serverIp + ":" + webPort + "/trade?otp=" + entry.getOtp();

        // ─── チャットメッセージ送信（ClickEvent 付き） ─────────────────────────
        player.sendMessage(
                Component.text("[GekiyabaFX] ", NamedTextColor.GOLD)
                        .append(Component.text("ログインURLを生成しました", NamedTextColor.WHITE))
                        .append(Component.text("（有効期限: " + expireMinutes + "分）", NamedTextColor.GRAY))
        );
        player.sendMessage(
                Component.text("► ", NamedTextColor.GOLD)
                        .append(
                                Component.text(loginUrl, NamedTextColor.AQUA)
                                        .decorate(TextDecoration.UNDERLINED)
                                        .clickEvent(ClickEvent.openUrl(loginUrl))
                        )
        );
        player.sendMessage(
                Component.text("  クリックでブラウザが開きます。", NamedTextColor.GRAY)
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  /fx admin  （Step 8 で本実装 — 現時点はプレースホルダー）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code /fx admin} の処理。
     *
     * <ol>
     *   <li>{@code gekiyabafx.admin} 権限を確認する（op または権限付与プレイヤーのみ実行可）。</li>
     *   <li>管理者用OTPを生成し、ゲーム内チャットにClickEvent付きURLを送信する。</li>
     * </ol>
     *
     * <p>管理者OTPはプレイヤー UUIDに紐づかず、固定の管理者識別子（{@code "admin"}）に紐づく。
     * セッショントークンたことに識別子は保持しない（管理者は単一セッション）。</p>
     *
     * @param sender コマンド送信者
     */
    void handleAdmin(CommandSender sender) {
        // 権限チェック（コンソールには op 権限なしに許可しない）
        if (!sender.hasPermission("gekiyabafx.admin")) {
            sender.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("このコマンドを実行する権限がありません。",
                            NamedTextColor.RED)));
            return;
        }

        // 管理者OTPは実行者の Playerインスタンスが不要（コンソールからも発行可能）
        // セッションはプレイヤーに紐づかない固定識別子 "admin" に紐づく
        OtpManager.OtpEntry entry = adminOtpManager.generate("admin");
        long expireMinutes = plugin.getPluginConfig().getOtpExpireSeconds() / 60;

        String serverIp = plugin.getPluginConfig().getServerIp();
        int webPort     = plugin.getPluginConfig().getWebPort();
        String adminUrl = "http://" + serverIp + ":" + webPort + "/admin?otp=" + entry.getOtp();

        // チャットメッセージ送信（プレイヤーの場合はClickEvent付き、コンソールはテキストのみ）
        sender.sendMessage(
                Component.text("[GekiyabaFX] ", NamedTextColor.GOLD)
                        .append(Component.text("管理者ログインURLを生成しました",
                                NamedTextColor.WHITE))
                        .append(Component.text("（有効期限: " + expireMinutes + "分）",
                                NamedTextColor.GRAY))
        );

        if (sender instanceof Player) {
            // プレイヤーには ClickEvent 付き URL を送信する
            sender.sendMessage(
                    Component.text("► ", NamedTextColor.GOLD)
                            .append(
                                    Component.text(adminUrl, NamedTextColor.AQUA)
                                            .decorate(TextDecoration.UNDERLINED)
                                            .clickEvent(ClickEvent.openUrl(adminUrl))
                            )
            );
            sender.sendMessage(
                    Component.text("  クリックでブラウザが開きます。",
                            NamedTextColor.GRAY)
            );
        } else {
            // コンソールにはプレーンテキストでURLを表示する
            sender.sendMessage(
                    Component.text("► " + adminUrl, NamedTextColor.AQUA)
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  /fx reload
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code /fx reload} の処理。
     * {@code gekiyabafx.reload} 権限を持つ送信者のみ実行可能。
     *
     * @param sender コマンド送信者
     */
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("gekiyabafx.reload")) {
            sender.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("このコマンドを実行する権限がありません。",
                            NamedTextColor.RED)));
            return;
        }

        try {
            plugin.reloadPluginConfig();
            sender.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("config.yml を再読み込みしました。",
                            NamedTextColor.GREEN)));
            plugin.getLogger().info("config.yml を再読み込みしました: " + plugin.getPluginConfig());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("config.yml の値が不正です: " + e.getMessage(),
                            NamedTextColor.RED)));
            plugin.getLogger().severe("config.yml の再読み込みに失敗しました: " + e.getMessage());
        }
    }
}
