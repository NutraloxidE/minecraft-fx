package com.gekiyabafx.command;

import com.gekiyabafx.GekiyabaFXPlugin;
import com.gekiyabafx.model.AtmData;
import com.gekiyabafx.auth.OtpManager;
import com.gekiyabafx.model.PlayerData;
import com.gekiyabafx.model.StorageData;
import com.gekiyabafx.storage.StorageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map;

/**
 * {@code /fx} コマンドのメインエグゼキューター。
 *
 * <p>サブコマンドのルーティングを行い、各処理に委譲する。</p>
 *
 * <ul>
 *   <li>{@code /fx login} — プレイヤー用OTP発行・ClickEvent送信（Step 7）</li>
 *   <li>{@code /fx removeatm} — 自分のATMを全停止・削除する</li>
 *   <li>{@code /fx admin} — 管理者用OTP発行（Step 8 で実装）</li>
 *   <li>{@code /fx reload} — config.yml 再読み込み</li>
 * </ul>
 */
public final class FxCommandExecutor implements CommandExecutor, TabCompleter {

    private static final double COMMAND_ATM_MAX_DISTANCE = 3.0;

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
            handleHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "login"     -> handleLogin(sender, args);
            case "deposit"   -> handleDeposit(sender, args);
            case "withdraw"  -> handleWithdraw(sender, args);
            case "removeatm" -> handleRemoveAtm(sender, args);
            case "login-as"  -> handleLoginAs(sender, args);
            case "admin"     -> handleAdmin(sender);
            case "reload"    -> handleReload(sender);
            case "help"      -> handleHelp(sender, label);
            default -> sender.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("不明なサブコマンドです: " + sub, NamedTextColor.RED)));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("fx")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> subs = List.of("login", "deposit", "withdraw", "removeatm", "login-as", "admin", "reload", "help");
            return filterByPrefix(subs, args[0]);
        }

        String sub = args[0].toLowerCase();

        if ("login".equals(sub) && args.length == 2) {
            return filterByPrefix(List.of("longer"), args[1]);
        }

        if (("deposit".equals(sub) || "withdraw".equals(sub)) && args.length == 2) {
            return filterByPrefix(getCurrencyItemsFromPairs(), args[1]);
        }

        if (("deposit".equals(sub) || "withdraw".equals(sub)) && args.length == 3) {
            return filterByPrefix(List.of("1", "16", "32", "64"), args[2]);
        }

        return Collections.emptyList();
    }

    private List<String> getCurrencyItemsFromPairs() {
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            Set<String> items = new TreeSet<>();
            sm.getData().getPairs().values().forEach(pair -> {
                if (pair == null) return;
                if (pair.getBase() != null && !pair.getBase().isBlank()) {
                    items.add(pair.getBase().toLowerCase());
                }
                if (pair.getQuote() != null && !pair.getQuote().isBlank()) {
                    items.add(pair.getQuote().toLowerCase());
                }
            });

            List<String> list = new ArrayList<>(items);
            list.sort(Comparator.naturalOrder());
            return list;
        } finally {
            sm.unlock();
        }
    }

    private static List<String> filterByPrefix(List<String> candidates, String rawPrefix) {
        String prefix = rawPrefix == null ? "" : rawPrefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            if (c.toLowerCase().startsWith(prefix)) {
                out.add(c);
            }
        }
        return out;
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
    private void handleLogin(CommandSender sender, String[] args) {
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
        boolean longer = args.length >= 2 && "longer".equalsIgnoreCase(args[1]);

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
        OtpManager.OtpEntry entry = playerOtpManager.generate(uuid, longer);
        long expireMinutes = plugin.getPluginConfig().getOtpExpireSeconds() / 60;

        // ─── ログイン URL の構築 ────────────────────────────────────────────────
        String loginUrl = plugin.getPluginConfig().buildPublicWebUrl("/trade?otp=" + entry.getOtp());

        // ─── チャットメッセージ送信（ClickEvent 付き） ─────────────────────────
        player.sendMessage(
            Component.text("[GekiyabaFX] ", NamedTextColor.GOLD)
                .append(Component.text(longer ? "長時間ログインURLを生成しました" : "ログインURLを生成しました", NamedTextColor.WHITE))
                .append(Component.text("（有効期限: " + expireMinutes + "分）", NamedTextColor.GRAY))
        );
        if (longer) {
            player.sendMessage(Component.text("  このURLでログインするとセッションは24時間維持されます。", NamedTextColor.GRAY));
        }
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
    //  /fx removeatm
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code /fx removeatm} の処理。
     *
     * <ol>
     *   <li>プレイヤー実行のみ許可する。</li>
     *   <li>引数 {@code ATM} を確認する。</li>
     *   <li>実行者の所有するATMを全て停止・削除する。</li>
     * </ol>
     */
    private void handleRemoveAtm(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("このコマンドはゲーム内でのみ使用できます。", NamedTextColor.RED)));
            return;
        }

        if (!player.hasPermission("gekiyabafx.login")) {
            player.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("このコマンドを実行する権限がありません。", NamedTextColor.RED)));
            return;
        }

        if (plugin.getAtmSignListener() == null) {
            player.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("ATM削除機能が初期化されていません。", NamedTextColor.RED)));
            return;
        }

        int removed = plugin.getAtmSignListener().removeAllAtmsForOwner(player.getUniqueId().toString());
        if (removed <= 0) {
            player.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("あなたが所有するATMは見つかりませんでした。", NamedTextColor.GRAY)));
            return;
        }

        player.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                .append(Component.text("ATMを" + removed + "件停止・削除しました。", NamedTextColor.GREEN)));
    }

        private void handleHelp(CommandSender sender, String label) {
        sender.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.GOLD)
            .append(Component.text("利用可能コマンド", NamedTextColor.WHITE)));

            if (sender.hasPermission("gekiyabafx.login")) {
                sender.sendMessage(Component.text("  /" + label + " login", NamedTextColor.AQUA)
                    .append(Component.text(" - プレイヤーログインURLを発行", NamedTextColor.GRAY)));
                sender.sendMessage(Component.text("  /" + label + " login longer", NamedTextColor.AQUA)
                    .append(Component.text(" - 24時間セッションのログインURLを発行", NamedTextColor.GRAY)));
                sender.sendMessage(Component.text("  /" + label + " deposit <keyname> <amount>", NamedTextColor.AQUA)
                    .append(Component.text(" - インベントリからホット残高へ預入", NamedTextColor.GRAY)));
                sender.sendMessage(Component.text("  /" + label + " withdraw <keyname> <amount>", NamedTextColor.AQUA)
                    .append(Component.text(" - ホット残高からインベントリへ引出", NamedTextColor.GRAY)));
                sender.sendMessage(Component.text("  /" + label + " removeatm", NamedTextColor.AQUA)
                    .append(Component.text(" - 自分のATMを全停止・削除", NamedTextColor.GRAY)));
            }

            if (sender.hasPermission("gekiyabafx.admin")) {
                sender.sendMessage(Component.text("  /" + label + " login-as <serviceAccountName>", NamedTextColor.AQUA)
                    .append(Component.text(" - ServiceアカウントでログインURLを発行", NamedTextColor.GRAY)));
                sender.sendMessage(Component.text("  /" + label + " admin", NamedTextColor.AQUA)
                    .append(Component.text(" - 管理者ログインURLを発行", NamedTextColor.GRAY)));
            }

            if (sender.hasPermission("gekiyabafx.reload")) {
                sender.sendMessage(Component.text("  /" + label + " reload", NamedTextColor.AQUA)
                    .append(Component.text(" - config.yml を再読み込み", NamedTextColor.GRAY)));
            }

        sender.sendMessage(Component.text("  /" + label + " help", NamedTextColor.AQUA)
            .append(Component.text(" - このヘルプを表示", NamedTextColor.GRAY)));
        }

    private void handleDeposit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("このコマンドはゲーム内でのみ使用できます。", NamedTextColor.RED)));
            return;
        }

        if (!player.hasPermission("gekiyabafx.login")) {
            player.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("このコマンドを実行する権限がありません。", NamedTextColor.RED)));
            return;
        }

        if (args.length < 3) {
            player.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("使用法: /fx deposit <keyname> <amount>", NamedTextColor.RED)));
            return;
        }

        String keyname = args[1].toLowerCase();
        Integer amount = parsePositiveInt(args[2]);
        Material material = Material.matchMaterial(keyname.toUpperCase());

        if (amount == null || material == null) {
            sendCommandResult(player, keyname, args[2], "deposit", false);
            return;
        }

        if (!hasNonOccupiedAtmInRange(player, COMMAND_ATM_MAX_DISTANCE)) {
            sendCommandResult(player, keyname, String.valueOf(amount), "deposit", false);
            return;
        }

        int inventoryCount = countItemInInventory(player, material);
        int executableAmount = Math.min(amount, inventoryCount);
        if (executableAmount <= 0) {
            sendCommandResult(player, keyname, String.valueOf(amount), "deposit", false);
            return;
        }

        Map<Integer, ItemStack> notRemoved = player.getInventory().removeItem(new ItemStack(material, executableAmount));
        int removed = executableAmount;
        for (ItemStack leftover : notRemoved.values()) {
            removed -= leftover.getAmount();
        }
        if (removed != executableAmount) {
            if (removed > 0) {
                player.getInventory().addItem(new ItemStack(material, removed));
            }
            sendCommandResult(player, keyname, String.valueOf(executableAmount), "deposit", false);
            return;
        }

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            StorageData data = sm.getData();
            PlayerData pd = data.getPlayer(player.getUniqueId().toString());
            if (pd == null) {
                player.getInventory().addItem(new ItemStack(material, amount));
                sendCommandResult(player, keyname, String.valueOf(amount), "deposit", false);
                return;
            }

            BigDecimal current = pd.getHotBalance(keyname);
            BigDecimal next = current.add(new BigDecimal(executableAmount).setScale(4, RoundingMode.HALF_UP));
            pd.setHotBalance(keyname, next);
            sm.markDirty();
        } finally {
            sm.unlock();
        }

        sendCommandResult(player, keyname, String.valueOf(executableAmount), "deposit", true);
    }

    private void handleWithdraw(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("このコマンドはゲーム内でのみ使用できます。", NamedTextColor.RED)));
            return;
        }

        if (!player.hasPermission("gekiyabafx.login")) {
            player.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("このコマンドを実行する権限がありません。", NamedTextColor.RED)));
            return;
        }

        if (args.length < 3) {
            player.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("使用法: /fx withdraw <keyname> <amount>", NamedTextColor.RED)));
            return;
        }

        String keyname = args[1].toLowerCase();
        Integer amount = parsePositiveInt(args[2]);
        Material material = Material.matchMaterial(keyname.toUpperCase());

        if (amount == null || material == null) {
            sendCommandResult(player, keyname, args[2], "withdraw", false);
            return;
        }

        if (!hasNonOccupiedAtmInRange(player, COMMAND_ATM_MAX_DISTANCE)) {
            sendCommandResult(player, keyname, String.valueOf(amount), "withdraw", false);
            return;
        }

        if (!canFitInInventory(player, material, amount)) {
            sendCommandResult(player, keyname, String.valueOf(amount), "withdraw", false);
            return;
        }

        StorageManager sm = StorageManager.getInstance();
        int executableAmount;
        sm.lock();
        try {
            StorageData data = sm.getData();
            PlayerData pd = data.getPlayer(player.getUniqueId().toString());
            if (pd == null) {
                sendCommandResult(player, keyname, String.valueOf(amount), "withdraw", false);
                return;
            }

            BigDecimal current = pd.getHotBalance(keyname);
            int maxByBalance = current.setScale(0, RoundingMode.DOWN).intValue();
            executableAmount = Math.min(amount, maxByBalance);
            if (executableAmount <= 0) {
                sendCommandResult(player, keyname, String.valueOf(amount), "withdraw", false);
                return;
            }

            BigDecimal req = new BigDecimal(executableAmount).setScale(4, RoundingMode.HALF_UP);
            pd.setHotBalance(keyname, current.subtract(req));
            sm.markDirty();
        } finally {
            sm.unlock();
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(material, executableAmount));
        if (!leftover.isEmpty()) {
            int notAdded = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            int added = executableAmount - notAdded;
            if (added > 0) {
                player.getInventory().removeItem(new ItemStack(material, added));
            }

            sm.lock();
            try {
                PlayerData pd = sm.getData().getPlayer(player.getUniqueId().toString());
                if (pd != null) {
                    BigDecimal current = pd.getHotBalance(keyname);
                    pd.setHotBalance(keyname, current.add(new BigDecimal(executableAmount).setScale(4, RoundingMode.HALF_UP)));
                    sm.markDirty();
                }
            } finally {
                sm.unlock();
            }

            sendCommandResult(player, keyname, String.valueOf(executableAmount), "withdraw", false);
            return;
        }

        sendCommandResult(player, keyname, String.valueOf(executableAmount), "withdraw", true);
    }

    private static Integer parsePositiveInt(String raw) {
        try {
            int v = Integer.parseInt(raw);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean hasNonOccupiedAtmInRange(Player player, double maxDistance) {
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            World world = player.getWorld();
            for (AtmData atm : sm.getAtmRegistry().getAtms().values()) {
                if (atm == null) continue;
                if (!"active".equalsIgnoreCase(atm.getStatus())) continue;
                if (atm.isOccupied()) continue;
                if (!world.getName().equals(atm.getSignWorld())) continue;

                double dx = player.getLocation().getX() - atm.getSignX();
                double dy = player.getLocation().getY() - atm.getSignY();
                double dz = player.getLocation().getZ() - atm.getSignZ();
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist <= maxDistance) {
                    return true;
                }
            }
            return false;
        } finally {
            sm.unlock();
        }
    }

    private static int countItemInInventory(Player player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType() != material) continue;
            total += stack.getAmount();
        }
        return total;
    }

    private static boolean canFitInInventory(Player player, Material material, int amount) {
        int remaining = amount;
        int maxStack = material.getMaxStackSize();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                remaining -= maxStack;
            } else if (stack.getType() == material) {
                remaining -= (maxStack - stack.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    private static void sendCommandResult(Player player, String keyname, String amount, String op, boolean success) {
        NamedTextColor color = success ? NamedTextColor.GREEN : NamedTextColor.RED;
        String status = success ? "success!" : "failed!";
        player.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                .append(Component.text(keyname + " * " + amount + " " + op + " " + status, color)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  /fx login-as <serviceAccount>
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code /fx login-as <serviceAccount>} の処理。
     *
     * <ol>
     *   <li>{@code gekiyabafx.admin} 権限を確認する。</li>
     *   <li>引数 {@code <serviceAccount>} が config.yml の {@code serviceAccounts} リストに
     *       含まれているか確認する。</li>
     *   <li>内部ID {@code svc:<id>} に対して {@link PlayerData} が存在しなければ自動作成する。</li>
     *   <li>プレイヤー用OTPを {@code svc:<id>} に対して発行し、ログインURLを返す。</li>
     * </ol>
     *
     * @param sender コマンド送信者
     * @param args   コマンド引数（args[0] = "login-as", args[1] = serviceAccount名）
     */
    private void handleLoginAs(CommandSender sender, String[] args) {
        // 権限チェック
        if (!sender.hasPermission("gekiyabafx.admin")) {
            sender.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("このコマンドを実行する権限がありません。",
                            NamedTextColor.RED)));
            return;
        }

        // 引数チェック
        if (args.length < 2 || args[1].isBlank()) {
            sender.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text("使用法: /fx login-as <serviceAccountName>",
                            NamedTextColor.RED)));
            return;
        }

        String svcName = args[1];

        // 許可リストチェック（config.yml の serviceAccounts）
        java.util.List<String> allowed = plugin.getPluginConfig().getServiceAccounts();
        if (!allowed.contains(svcName)) {
            sender.sendMessage(Component.text("[GekiyabaFX] ", NamedTextColor.YELLOW)
                    .append(Component.text(
                            "'" + svcName + "' は許可された Service アカウントではありません。",
                            NamedTextColor.RED)));
            sender.sendMessage(Component.text("  許可リスト: " + allowed, NamedTextColor.GRAY));
            return;
        }

        // 内部ID: svc:<name>
        String svcId = "svc:" + svcName;
        String displayName = "[SERVICE] " + svcName;

        // PlayerData 自動作成（存在しない場合のみ）
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            StorageData data = sm.getData();
            if (data.getPlayer(svcId) == null) {
                PlayerData newSvc = new PlayerData(displayName);
                data.getPlayers().put(svcId, newSvc);
                sm.markDirty();
                plugin.getLogger().info("Service アカウントを自動作成しました: " + svcId);
            }
        } finally {
            sm.unlock();
        }

        // プレイヤー用OTP発行（通常ログインと同じ導線）
        OtpManager.OtpEntry entry = playerOtpManager.generate(svcId);
        long expireMinutes = plugin.getPluginConfig().getOtpExpireSeconds() / 60;

        String loginUrl = plugin.getPluginConfig().buildPublicWebUrl("/trade?otp=" + entry.getOtp());

        // チャットメッセージ送信
        sender.sendMessage(
                Component.text("[GekiyabaFX] ", NamedTextColor.GOLD)
                        .append(Component.text("Service アカウント ", NamedTextColor.WHITE))
                        .append(Component.text(svcId, NamedTextColor.AQUA))
                        .append(Component.text(" のログインURLを生成しました", NamedTextColor.WHITE))
                        .append(Component.text("（有効期限: " + expireMinutes + "分）", NamedTextColor.GRAY))
        );

        if (sender instanceof Player) {
            sender.sendMessage(
                    Component.text("► ", NamedTextColor.GOLD)
                            .append(
                                    Component.text(loginUrl, NamedTextColor.AQUA)
                                            .decorate(TextDecoration.UNDERLINED)
                                            .clickEvent(ClickEvent.openUrl(loginUrl))
                            )
            );
            sender.sendMessage(
                    Component.text("  クリックでブラウザが開きます。", NamedTextColor.GRAY)
            );
        } else {
            sender.sendMessage(
                    Component.text("► " + loginUrl, NamedTextColor.AQUA)
            );
        }
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

        String adminUrl = plugin.getPluginConfig().buildPublicWebUrl("/admin?otp=" + entry.getOtp());

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
