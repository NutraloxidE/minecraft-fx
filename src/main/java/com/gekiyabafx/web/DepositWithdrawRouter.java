package com.gekiyabafx.web;

import com.gekiyabafx.GekiyabaFXPlugin;
import com.gekiyabafx.atm.AtmSessionManager;
import com.gekiyabafx.auth.SessionManager;
import com.gekiyabafx.model.PlayerData;
import com.gekiyabafx.storage.StorageManager;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 入出金 API エンドポイントを Javalin アプリに登録するルーター。
 *
 * <h3>エンドポイント</h3>
 * <ul>
 *   <li>{@code POST /api/deposit}  — プレイヤーのインベントリからアイテムを回収してホット残高へ加算する。</li>
 *   <li>{@code POST /api/withdraw} — ホット残高を減算してプレイヤーのインベントリへアイテムを払い出す。</li>
 * </ul>
 *
 * <h3>インベントリ操作の同期要件</h3>
 * <p>Bukkit のインベントリ API はメインスレッド（サーバースレッド）からのみ安全に呼び出せる。
 * Javalin のハンドラは別スレッドで動くため、
 * {@link Bukkit#getScheduler()}{@code .runTask()} で同期タスクをスケジュールし、
 * {@link java.util.concurrent.CompletableFuture} でその完了を待ち合わせてレスポンスを返す。</p>
 *
 * <h3>オフラインプレイヤーの扱い</h3>
 * <p>インベントリ操作は <b>オンライン時のみ即時実行</b> する。
 * オフライン時は {@code pending_deposit} / {@code pending_withdraw} に数量を積み、
 * 次回ログイン時に {@link com.gekiyabafx.listener.PlayerJoinListener} が処理する。</p>
 */
public final class DepositWithdrawRouter {

    private final GekiyabaFXPlugin plugin;
    private final SessionManager   playerSessionManager;
    private final AtmSessionManager atmSessionManager;

    private static final class AuthContext {
        private final String token;
        private final SessionManager.SessionEntry entry;

        private AuthContext(String token, SessionManager.SessionEntry entry) {
            this.token = token;
            this.entry = entry;
        }
    }

    /**
     * @param plugin               プラグインインスタンス（スケジューラ取得用）
     * @param playerSessionManager プレイヤー用 {@link SessionManager}
     * @param atmSessionManager ATM セッション管理
     */
    public DepositWithdrawRouter(
            GekiyabaFXPlugin plugin,
            SessionManager playerSessionManager,
            AtmSessionManager atmSessionManager
    ) {
        this.plugin               = plugin;
        this.playerSessionManager = playerSessionManager;
        this.atmSessionManager    = atmSessionManager;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ルート登録
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@link Javalin} アプリに入出金ルートを登録する。
     *
     * @param app {@link Javalin} インスタンス
     */
    public void register(Javalin app) {
        app.get("/api/atm-session", this::handleAtmSession);
        app.post("/api/deposit",  this::handleDeposit);
        app.post("/api/withdraw", this::handleWithdraw);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  認証ヘルパー
    // ─────────────────────────────────────────────────────────────────────────

    private AuthContext requireAuth(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            ctx.status(401).json(Map.of("error", "unauthorized"));
            return null;
        }
        String token = header.substring(7).trim();
        SessionManager.SessionEntry entry = playerSessionManager.resolve(token);
        if (entry == null) {
            ctx.status(401).json(Map.of("error", "unauthorized"));
            return null;
        }
        return new AuthContext(token, entry);
    }

    private void handleAtmSession(Context ctx) {
        AuthContext auth = requireAuth(ctx);
        if (auth == null) return;

        AtmSessionManager.AtmSessionState state =
                atmSessionManager.getStateByToken(auth.token, auth.entry.getIdentity());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", state.isActive());
        body.put("atm_id", state.getAtmId());
        body.put("grade", state.getGrade());
        body.put("max_distance", state.getMaxDistance());
        ctx.json(body);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  POST /api/deposit
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * アイテムを預け入れる。
     *
     * <h4>リクエストボディ</h4>
     * <pre>{@code { "item": "diamond", "amount": 64 } }</pre>
     *
     * <h4>レスポンス（200）— オンライン即時処理</h4>
     * <pre>{@code { "deposited": 64, "hot_balance": "164.0000" } }</pre>
     *
     * <h4>レスポンス（200）— オフライン pending 積み</h4>
     * <pre>{@code { "pending": 64 } }</pre>
     *
     * <p>amount は正の整数。アイテム名は Minecraft Material 名（小文字スネークケース）。</p>
     */
    private void handleDeposit(Context ctx) {
        AuthContext auth = requireAuth(ctx);
        if (auth == null) return;

        try {
            atmSessionManager.requireActiveInRange(auth.token, auth.entry.getIdentity(), "deposit");
        } catch (AtmSessionManager.AtmSessionException e) {
            ctx.status(403).json(Map.of("error", e.getCode(), "message", e.getMessage()));
            return;
        }

        String playerUuid = auth.entry.getIdentity();

        // ── リクエストパース ──────────────────────────────────────────────────
        Map<String, Object> body = parseBody(ctx);
        if (body == null) return;

        String item   = getString(body, "item");
        int    amount = getInt(body, "amount");

        if (item == null || item.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing_item"));
            return;
        }
        if (amount <= 0) {
            ctx.status(400).json(Map.of("error", "amount_must_be_positive"));
            return;
        }

        // Material 検証
        Material material = Material.matchMaterial(item.toUpperCase());
        if (material == null) {
            ctx.status(400).json(Map.of("error", "unknown_material"));
            return;
        }

        // ── オンラインチェック ────────────────────────────────────────────────
        Player onlinePlayer = Bukkit.getPlayer(java.util.UUID.fromString(playerUuid));

        if (onlinePlayer == null || !onlinePlayer.isOnline()) {
            // オフライン → pending_deposit に積む
            StorageManager sm = StorageManager.getInstance();
            sm.lock();
            try {
                PlayerData data = sm.getData().getPlayer(playerUuid);
                if (data == null) {
                    ctx.status(404).json(Map.of("error", "player_not_found"));
                    return;
                }
                int current = data.getPendingDepositAmount(item);
                data.getPendingDeposit().put(item, current + amount);
                sm.markDirty();
            } finally {
                sm.unlock();
            }
            ctx.json(Map.of("pending", amount));
            return;
        }

        // ── オンライン → BukkitScheduler でメインスレッド実行 ─────────────────
        java.util.concurrent.CompletableFuture<Map<String, Object>> future =
                new java.util.concurrent.CompletableFuture<>();

        final Material mat = material;
        final int      amt = amount;
        final String   itm = item.toLowerCase();

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                // インベントリからアイテムを回収
                ItemStack toRemove = new ItemStack(mat, amt);
                Map<Integer, ItemStack> notRemoved = onlinePlayer.getInventory().removeItem(toRemove);

                // 実際に回収できた数量
                int removed = amt;
                for (ItemStack leftover : notRemoved.values()) {
                    removed -= leftover.getAmount();
                }

                if (removed <= 0) {
                    future.complete(Map.of("error", "insufficient_inventory"));
                    return;
                }

                // ホット残高に加算
                StorageManager sm = StorageManager.getInstance();
                sm.lock();
                try {
                    PlayerData data = sm.getData().getPlayer(playerUuid);
                    if (data == null) {
                        future.complete(Map.of("error", "player_not_found"));
                        return;
                    }
                    BigDecimal current  = data.getHotBalance(itm);
                    BigDecimal newBal   = current.add(
                            new BigDecimal(removed).setScale(4, RoundingMode.HALF_UP));
                    data.setHotBalance(itm, newBal);
                    sm.markDirty();

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("deposited",   removed);
                    result.put("hot_balance", newBal.toPlainString());
                    future.complete(result);
                } finally {
                    sm.unlock();
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        // Javalin スレッドで完了を待つ
        try {
            Map<String, Object> result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (result.containsKey("error")) {
                ctx.status(400).json(result);
            } else {
                ctx.json(result);
            }
        } catch (java.util.concurrent.TimeoutException e) {
            ctx.status(503).json(Map.of("error", "server_timeout"));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "internal_error"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  POST /api/withdraw
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * アイテムを引き出す。
     *
     * <h4>リクエストボディ</h4>
     * <pre>{@code { "item": "diamond", "amount": 32 } }</pre>
     *
     * <h4>レスポンス（200）— オンライン即時処理</h4>
     * <pre>{@code { "withdrawn": 32, "hot_balance": "100.0000" } }</pre>
     *
     * <h4>レスポンス（200）— オフライン pending 積み</h4>
     * <pre>{@code { "pending": 32 } }</pre>
     *
     * <p>ホット残高が不足する場合は {@code 400 insufficient_balance} を返す。
     * インベントリが満杯で入りきらない場合は入った分だけ処理し、残りは {@code pending_withdraw} に積む。</p>
     */
    private void handleWithdraw(Context ctx) {
        AuthContext auth = requireAuth(ctx);
        if (auth == null) return;

        try {
            atmSessionManager.requireActiveInRange(auth.token, auth.entry.getIdentity(), "withdraw");
        } catch (AtmSessionManager.AtmSessionException e) {
            ctx.status(403).json(Map.of("error", e.getCode(), "message", e.getMessage()));
            return;
        }

        String playerUuid = auth.entry.getIdentity();

        // ── リクエストパース ──────────────────────────────────────────────────
        Map<String, Object> body = parseBody(ctx);
        if (body == null) return;

        String item   = getString(body, "item");
        int    amount = getInt(body, "amount");

        if (item == null || item.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing_item"));
            return;
        }
        if (amount <= 0) {
            ctx.status(400).json(Map.of("error", "amount_must_be_positive"));
            return;
        }

        Material material = Material.matchMaterial(item.toUpperCase());
        if (material == null) {
            ctx.status(400).json(Map.of("error", "unknown_material"));
            return;
        }

        // ── 残高チェック & 減算（ロック内） ───────────────────────────────────
        final String itm = item.toLowerCase();
        final int    amt = amount;

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            PlayerData data = sm.getData().getPlayer(playerUuid);
            if (data == null) {
                ctx.status(404).json(Map.of("error", "player_not_found"));
                return;
            }

            BigDecimal hotBal  = data.getHotBalance(itm);
            BigDecimal reqDec  = new BigDecimal(amt).setScale(4, RoundingMode.HALF_UP);
            if (hotBal.compareTo(reqDec) < 0) {
                ctx.status(400).json(Map.of("error", "insufficient_balance",
                        "hot_balance", hotBal.toPlainString()));
                return;
            }

            // 先に残高を減算（インベントリ付与の成否に関わらず確定）
            data.setHotBalance(itm, hotBal.subtract(reqDec));
            sm.markDirty();
        } finally {
            sm.unlock();
        }

        // ── オンラインチェック ────────────────────────────────────────────────
        Player onlinePlayer = Bukkit.getPlayer(java.util.UUID.fromString(playerUuid));

        if (onlinePlayer == null || !onlinePlayer.isOnline()) {
            // オフライン → pending_withdraw に積む
            sm.lock();
            try {
                PlayerData data = sm.getData().getPlayer(playerUuid);
                if (data != null) {
                    int current = data.getPendingWithdrawAmount(itm);
                    data.getPendingWithdraw().put(itm, current + amt);
                    sm.markDirty();
                }
            } finally {
                sm.unlock();
            }
            ctx.json(Map.of("pending", amt));
            return;
        }

        // ── オンライン → BukkitScheduler でメインスレッド実行 ─────────────────
        java.util.concurrent.CompletableFuture<Map<String, Object>> future =
                new java.util.concurrent.CompletableFuture<>();

        final Material mat = material;

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                ItemStack toGive = new ItemStack(mat, amt);
                Map<Integer, ItemStack> leftover = onlinePlayer.getInventory().addItem(toGive);

                // 入りきらなかった分を pending_withdraw に積む
                int notGiven = 0;
                for (ItemStack ls : leftover.values()) {
                    notGiven += ls.getAmount();
                }
                int given = amt - notGiven;

                if (notGiven > 0) {
                    StorageManager sm2 = StorageManager.getInstance();
                    sm2.lock();
                    try {
                        PlayerData data = sm2.getData().getPlayer(playerUuid);
                        if (data != null) {
                            int current = data.getPendingWithdrawAmount(itm);
                            data.getPendingWithdraw().put(itm, current + notGiven);
                            sm2.markDirty();
                        }
                    } finally {
                        sm2.unlock();
                    }
                }

                // 最終残高を取得してレスポンス
                StorageManager sm2 = StorageManager.getInstance();
                sm2.lock();
                try {
                    PlayerData data = sm2.getData().getPlayer(playerUuid);
                    BigDecimal newBal = data != null ? data.getHotBalance(itm) : BigDecimal.ZERO;
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("withdrawn",   given);
                    result.put("hot_balance", newBal.toPlainString());
                    if (notGiven > 0) result.put("pending", notGiven);
                    future.complete(result);
                } finally {
                    sm2.unlock();
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            Map<String, Object> result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            ctx.json(result);
        } catch (java.util.concurrent.TimeoutException e) {
            ctx.status(503).json(Map.of("error", "server_timeout"));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "internal_error"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  内部ヘルパー
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(Context ctx) {
        try {
            return ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "bad_request"));
            return null;
        }
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    private static int getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return 0;
        try {
            if (v instanceof Number n) return n.intValue();
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
