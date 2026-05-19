package com.gekiyabafx.listener;

import com.gekiyabafx.GekiyabaFXPlugin;
import com.gekiyabafx.model.PlayerData;
import com.gekiyabafx.storage.StorageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

/**
 * プレイヤーがサーバに参加した際の入出金保留処理を担当するリスナー。
 *
 * <h3>処理概要</h3>
 * <ol>
 *   <li><b>pending_deposit の回収</b>: オフライン中に API 経由で預け入れが要求された分を
 *       プレイヤーのインベントリから取り出し、ホット残高へ加算する。
 *       インベントリに在庫が不足している場合は取り出せた分だけ処理し、残りは {@code pending_deposit} に残す。</li>
 *   <li><b>pending_withdraw の付与</b>: オフライン中に API 経由で引き出しが要求された分を
 *       プレイヤーのインベントリへ追加する。
 *       インベントリが満杯で入らない場合は入った分だけ処理し、残りは {@code pending_withdraw} に残す。</li>
 *   <li>いずれかの処理が行われた場合は {@link StorageManager#markDirty()} を呼んで永続化をスケジュールする。</li>
 * </ol>
 *
 * <p>ロックは {@link StorageManager#lock()} / {@link StorageManager#unlock()} で保護し、
 * すべての残高操作をアトミックに行う。</p>
 */
public final class PlayerJoinListener implements Listener {

    private final Logger logger;

    /**
     * コンストラクタ。
     *
     * @param plugin プラグインインスタンス（ロガー取得用）
     */
    public PlayerJoinListener(GekiyabaFXPlugin plugin) {
        this.logger = plugin.getLogger();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  イベントハンドラ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * プレイヤー参加時に pending_deposit / pending_withdraw を処理する。
     *
     * @param event {@link PlayerJoinEvent}
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String uuidStr = player.getUniqueId().toString();
        StorageManager sm = StorageManager.getInstance();

        sm.lock();
        try {
            PlayerData data = sm.getData().getPlayers().get(uuidStr);
            if (data == null) {
                // まだ /fx login していないプレイヤーは処理不要
                return;
            }

            boolean dirty = false;
            dirty |= processPendingDeposit(player, data);
            dirty |= processPendingWithdraw(player, data);

            if (dirty) {
                sm.markDirty();
            }
        } finally {
            sm.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  内部処理
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code pending_deposit} を処理する。
     * プレイヤーのインベントリからアイテムを取り出し、取り出せた分をホット残高へ加算する。
     *
     * @param player プレイヤー
     * @param data   プレイヤーデータ
     * @return 何かしら変更があった場合 {@code true}
     */
    private boolean processPendingDeposit(Player player, PlayerData data) {
        Map<String, Integer> pending = data.getPendingDeposit();
        if (pending.isEmpty()) return false;

        boolean changed = false;
        Iterator<Map.Entry<String, Integer>> it = pending.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, Integer> entry = it.next();
            String itemName = entry.getKey();
            int requested = entry.getValue();

            Material material = Material.matchMaterial(itemName.toUpperCase());
            if (material == null) {
                logger.warning("[PlayerJoinListener] 不明なマテリアル '" + itemName
                        + "' — pending_deposit エントリを削除します。");
                it.remove();
                changed = true;
                continue;
            }

            // インベントリから取り出す（返り値 = 取り出せなかった残り）
            Map<Integer, ItemStack> leftover =
                    player.getInventory().removeItem(new ItemStack(material, requested));
            int notRemoved = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            int actuallyRemoved = requested - notRemoved;

            if (actuallyRemoved > 0) {
                // ホット残高へ加算
                BigDecimal added = BigDecimal.valueOf(actuallyRemoved)
                        .setScale(4, RoundingMode.HALF_UP);
                data.setHotBalance(itemName,
                        data.getHotBalance(itemName).add(added).setScale(4, RoundingMode.HALF_UP));

                player.sendMessage(Component.text()
                        .append(Component.text("[GekiyabaFX] ", NamedTextColor.GOLD))
                        .append(Component.text("デポジット完了: ", NamedTextColor.GREEN))
                        .append(Component.text(actuallyRemoved + "x ", NamedTextColor.WHITE))
                        .append(Component.text(itemName, NamedTextColor.YELLOW))
                        .append(Component.text(" をホット残高に加算しました。", NamedTextColor.GREEN))
                        .build());
                changed = true;
            }

            if (notRemoved == 0) {
                // 全量回収完了 → エントリを削除
                it.remove();
            } else {
                // 一部しか取り出せなかった → 残量を更新して次回ログイン時に再試行
                entry.setValue(notRemoved);
                player.sendMessage(Component.text()
                        .append(Component.text("[GekiyabaFX] ", NamedTextColor.GOLD))
                        .append(Component.text("デポジット保留: ", NamedTextColor.YELLOW))
                        .append(Component.text(notRemoved + "x ", NamedTextColor.WHITE))
                        .append(Component.text(itemName, NamedTextColor.YELLOW))
                        .append(Component.text(
                                " がインベントリに見つかりません。次回ログイン時に再試行します。",
                                NamedTextColor.YELLOW))
                        .build());
            }
        }

        return changed;
    }

    /**
     * {@code pending_withdraw} を処理する。
     * プレイヤーのインベントリへアイテムを追加し、追加できた分だけ保留量を減らす。
     *
     * @param player プレイヤー
     * @param data   プレイヤーデータ
     * @return 何かしら変更があった場合 {@code true}
     */
    private boolean processPendingWithdraw(Player player, PlayerData data) {
        Map<String, Integer> pending = data.getPendingWithdraw();
        if (pending.isEmpty()) return false;

        boolean changed = false;
        Iterator<Map.Entry<String, Integer>> it = pending.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, Integer> entry = it.next();
            String itemName = entry.getKey();
            int requested = entry.getValue();

            Material material = Material.matchMaterial(itemName.toUpperCase());
            if (material == null) {
                logger.warning("[PlayerJoinListener] 不明なマテリアル '" + itemName
                        + "' — pending_withdraw エントリを削除します。");
                it.remove();
                changed = true;
                continue;
            }

            // インベントリへ追加（返り値 = 入らなかった残り）
            Map<Integer, ItemStack> leftover =
                    player.getInventory().addItem(new ItemStack(material, requested));
            int notAdded = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            int actuallyAdded = requested - notAdded;

            if (actuallyAdded > 0) {
                player.sendMessage(Component.text()
                        .append(Component.text("[GekiyabaFX] ", NamedTextColor.GOLD))
                        .append(Component.text("引き出し完了: ", NamedTextColor.GREEN))
                        .append(Component.text(actuallyAdded + "x ", NamedTextColor.WHITE))
                        .append(Component.text(itemName, NamedTextColor.YELLOW))
                        .append(Component.text(" をインベントリに追加しました。", NamedTextColor.GREEN))
                        .build());
                changed = true;
            }

            if (notAdded == 0) {
                // 全量付与完了 → エントリを削除
                it.remove();
            } else {
                // インベントリ満杯で一部しか渡せなかった → 残量を更新して次回再試行
                entry.setValue(notAdded);
                player.sendMessage(Component.text()
                        .append(Component.text("[GekiyabaFX] ", NamedTextColor.GOLD))
                        .append(Component.text("引き出し保留: ", NamedTextColor.YELLOW))
                        .append(Component.text(notAdded + "x ", NamedTextColor.WHITE))
                        .append(Component.text(itemName, NamedTextColor.YELLOW))
                        .append(Component.text(
                                " がインベントリに入りません（満杯）。次回ログイン時に再試行します。",
                                NamedTextColor.YELLOW))
                        .build());
            }
        }

        return changed;
    }
}
