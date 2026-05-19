package com.gekiyabafx.web;

import com.gekiyabafx.auth.SessionManager;
import com.gekiyabafx.config.PluginConfig;
import com.gekiyabafx.engine.InsufficientBalanceException;
import com.gekiyabafx.engine.MatchResult;
import com.gekiyabafx.engine.MatchingEngine;
import com.gekiyabafx.model.*;
import com.gekiyabafx.storage.StorageManager;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.math.BigDecimal;
import java.util.*;

/**
 * 認証済みプレイヤー向け API エンドポイントを Javalin アプリに登録するルーター。
 *
 * <h3>エンドポイント</h3>
 * <ul>
 *   <li>{@code GET  /api/state}       — プレイヤーの残高・注文一覧を返す。</li>
 *   <li>{@code POST /api/order}        — 新規注文を発注する。</li>
 *   <li>{@code DELETE /api/order/:id}  — 指定注文をキャンセルする。</li>
 * </ul>
 *
 * <h3>認証</h3>
 * <p>全エンドポイントで {@code Authorization: Bearer <token>} ヘッダーを検証する。
 * 無効なトークンは {@code 401 Unauthorized} を返す。</p>
 */
public final class PlayerApiRouter {

    private final SessionManager playerSessionManager;
    private final PluginConfig   config;

    /**
     * @param playerSessionManager プレイヤー用 {@link SessionManager}
     * @param config               プラグイン設定
     */
    public PlayerApiRouter(SessionManager playerSessionManager, PluginConfig config) {
        this.playerSessionManager = playerSessionManager;
        this.config               = config;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ルート登録
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@link Javalin} アプリにプレイヤー API ルートを登録する。
     *
     * @param app {@link Javalin} インスタンス
     */
    public void register(Javalin app) {
        app.get("/api/state",          this::handleState);
        app.post("/api/order",         this::handlePlaceOrder);
        app.delete("/api/order/{id}",  this::handleCancelOrder);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  認証ヘルパー
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code Authorization: Bearer <token>} ヘッダーを検証し、
     * 有効なら {@link SessionManager.SessionEntry} を返す。
     * 無効なら {@code 401} を返して {@code null} を返す。
     */
    private SessionManager.SessionEntry requireAuth(Context ctx) {
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
        return entry;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GET /api/state
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * プレイヤーの残高・アクティブ注文一覧を返す。
     *
     * <h4>レスポンス例（200）</h4>
     * <pre>{@code
     * {
     *   "uuid": "550e8400-...",
     *   "name": "Steve",
     *   "hot_storage": {
     *     "diamond": "120.0000",
     *     "emerald": "4500.0000"
     *   },
     *   "locked_balance": {
     *     "DIAMOND/EMERALD": { "emerald": "1200.0000" }
     *   },
     *   "open_orders": [
     *     {
     *       "order_id": "...",
     *       "pair_id":  "DIAMOND/EMERALD",
     *       "side":     "BUY",
     *       "type":     "LIMIT",
     *       "price":    "4.2000",
     *       "amount":   "10.0000",
     *       "filled":   "0.0000",
     *       "status":   "OPEN",
     *       "created_at": 1716200000
     *     }
     *   ]
     * }
     * }</pre>
     */
    private void handleState(Context ctx) {
        SessionManager.SessionEntry entry = requireAuth(ctx);
        if (entry == null) return;

        String playerUuid = entry.getIdentity();

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            PlayerData player = sm.getData().getPlayer(playerUuid);
            if (player == null) {
                ctx.status(404).json(Map.of("error", "player_not_found"));
                return;
            }

            // ── hot_storage スナップショット ──────────────────────────────────
            Map<String, String> hotSnapshot = new LinkedHashMap<>();
            player.getHotStorage().forEach((item, amt) ->
                    hotSnapshot.put(item, amt.toPlainString()));

            // ── locked_balance スナップショット ───────────────────────────────
            Map<String, Map<String, String>> lockedSnapshot = new LinkedHashMap<>();
            player.getLockedBalance().forEach((pairId, innerMap) -> {
                Map<String, String> inner = new LinkedHashMap<>();
                innerMap.forEach((item, amt) -> inner.put(item, amt.toPlainString()));
                lockedSnapshot.put(pairId, inner);
            });

            // ── open_orders: 全ペアの板からこのプレイヤーの注文を収集 ──────────
            List<Map<String, Object>> openOrders = new ArrayList<>();
            sm.getData().getPairs().forEach((pairId, pair) -> {
                OrderBook book = pair.getOrderBook();
                if (book == null) return;
                List<Order> all = new ArrayList<>(book.getBids());
                all.addAll(book.getAsks());
                for (Order o : all) {
                    if (playerUuid.equals(o.getUuid())) {
                        openOrders.add(orderToMap(pairId, o));
                    }
                }
            });

            // ── レスポンス組み立て ────────────────────────────────────────────
            Map<String, Object> resp = new LinkedHashMap<>();
            // ── pending_deposit スナップショット ──────────────────────────────
            Map<String, Integer> pendingDeposit = new LinkedHashMap<>(player.getPendingDeposit());

            // ── pending_withdraw スナップショット ─────────────────────────────
            Map<String, Integer> pendingWithdraw = new LinkedHashMap<>(player.getPendingWithdraw());

            resp.put("uuid",            playerUuid);
            resp.put("name",            player.getName());
            resp.put("hot_storage",     hotSnapshot);
            resp.put("locked_balance",  lockedSnapshot);
            resp.put("open_orders",     openOrders);
            resp.put("pending_deposit", pendingDeposit);
            resp.put("pending_withdraw",pendingWithdraw);

            ctx.json(resp);
        } finally {
            sm.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  POST /api/order
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 新規注文を発注する。
     *
     * <h4>リクエストボディ（JSON）</h4>
     * <pre>{@code
     * {
     *   "pair_id": "DIAMOND/EMERALD",
     *   "side":    "BUY",           // "BUY" | "SELL"
     *   "type":    "LIMIT",         // "LIMIT" | "MARKET"
     *   "price":   "4.2000",        // LIMIT のみ必須
     *   "amount":  "10.0000",       // LIMIT + SELL MARKET で使用
     *   "max_spend": "42.0000"      // BUY MARKET のみ使用
     * }
     * }</pre>
     *
     * <h4>レスポンス（201）</h4>
     * <pre>{@code
     * {
     *   "order_id":   "...",
     *   "executions": [ { "price": "4.2000", "amount": "10.0000", "created_at": 1716200000 } ]
     * }
     * }</pre>
     */
    private void handlePlaceOrder(Context ctx) {
        SessionManager.SessionEntry entry = requireAuth(ctx);
        if (entry == null) return;

        String playerUuid = entry.getIdentity();

        // ── リクエストパラメータのパース ──────────────────────────────────────
        Map<String, Object> body;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = ctx.bodyAsClass(Map.class);
            body = parsed;
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "bad_request"));
            return;
        }

        String pairId = getString(body, "pair_id");
        String sideStr = getString(body, "side");
        String typeStr = getString(body, "type");

        if (pairId == null || sideStr == null || typeStr == null) {
            ctx.status(400).json(Map.of("error", "missing_fields"));
            return;
        }

        OrderSide side;
        OrderType type;
        try {
            side = OrderSide.valueOf(sideStr.toUpperCase());
            type = OrderType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", "invalid_side_or_type"));
            return;
        }

        BigDecimal price     = parseBigDecimal(body, "price");
        BigDecimal amount    = parseBigDecimal(body, "amount");
        BigDecimal maxSpend  = parseBigDecimal(body, "max_spend");

        if (type == OrderType.LIMIT && (price == null || amount == null)) {
            ctx.status(400).json(Map.of("error", "limit_order_requires_price_and_amount"));
            return;
        }
        if (type == OrderType.MARKET && side == OrderSide.SELL && amount == null) {
            ctx.status(400).json(Map.of("error", "market_sell_requires_amount"));
            return;
        }
        if (type == OrderType.MARKET && side == OrderSide.BUY && maxSpend == null) {
            ctx.status(400).json(Map.of("error", "market_buy_requires_max_spend"));
            return;
        }

        // ── 注文オブジェクト生成 ──────────────────────────────────────────────
        Order incoming = new Order();
        incoming.setOrderId(UUID.randomUUID().toString());
        incoming.setUuid(playerUuid);
        incoming.setType(type);
        incoming.setSide(side);
        incoming.setPrice(price);
        incoming.setAmount(amount != null ? amount : BigDecimal.ZERO);
        incoming.setFilled(BigDecimal.ZERO);
        incoming.setMaxSpend(maxSpend);
        incoming.setStatus(OrderStatus.OPEN);
        incoming.setCreatedAt(System.currentTimeMillis() / 1000L);

        // ── マッチングエンジン実行（ロック内） ────────────────────────────────
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            StorageData data = sm.getData();

            Pair pair = data.getPairs().get(pairId);
            if (pair == null || !pair.isEnabled()) {
                ctx.status(400).json(Map.of("error", "pair_not_found"));
                return;
            }

            MatchResult result;
            try {
                result = MatchingEngine.placeOrder(pairId, pair, incoming, data, config);
            } catch (InsufficientBalanceException e) {
                ctx.status(400).json(Map.of("error", "insufficient_balance", "detail", e.getMessage()));
                return;
            }

            sm.markDirty();

            // ── レスポンス ───────────────────────────────────────────────────
            List<Map<String, Object>> execList = new ArrayList<>();
            for (Execution ex : result.getExecutions()) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("price",      ex.getPrice().toPlainString());
                em.put("amount",     ex.getAmount().toPlainString());
                em.put("created_at", ex.getTimestamp());
                execList.add(em);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("order_id",   incoming.getOrderId());
            resp.put("executions", execList);

            ctx.status(201).json(resp);
        } finally {
            sm.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DELETE /api/order/:id
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 指定 ID の注文をキャンセルする。
     *
     * <p>発注したプレイヤー本人の注文のみキャンセル可能。
     * 他プレイヤーの注文 ID を指定した場合は {@code 403} を返す。</p>
     *
     * <h4>レスポンス（200）</h4>
     * <pre>{@code { "cancelled": true } }</pre>
     */
    private void handleCancelOrder(Context ctx) {
        SessionManager.SessionEntry entry = requireAuth(ctx);
        if (entry == null) return;

        String playerUuid = entry.getIdentity();
        String orderId    = ctx.pathParam("id");

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            StorageData data = sm.getData();

            // 全ペアの板から対象注文を探す
            for (Map.Entry<String, Pair> e : data.getPairs().entrySet()) {
                String    pairId = e.getKey();
                OrderBook book   = e.getValue().getOrderBook();
                if (book == null) continue;

                Order found = findInBook(book, orderId);
                if (found == null) continue;

                // 所有者チェック
                if (!playerUuid.equals(found.getUuid())) {
                    ctx.status(403).json(Map.of("error", "forbidden"));
                    return;
                }

                Pair pair = data.getPairs().get(pairId);
                MatchingEngine.cancelOrder(pairId, pair, found, data, config);
                sm.markDirty();

                ctx.json(Map.of("cancelled", true));
                return;
            }

            ctx.status(404).json(Map.of("error", "order_not_found"));
        } finally {
            sm.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  内部ヘルパー
    // ─────────────────────────────────────────────────────────────────────────

    /** 注文を板（bids + asks）から ID で検索する。 */
    private static Order findInBook(OrderBook book, String orderId) {
        for (Order o : book.getBids()) {
            if (orderId.equals(o.getOrderId())) return o;
        }
        for (Order o : book.getAsks()) {
            if (orderId.equals(o.getOrderId())) return o;
        }
        return null;
    }

    /** {@code Order} → レスポンス用 {@code Map} 変換。 */
    private static Map<String, Object> orderToMap(String pairId, Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order_id",   o.getOrderId());
        m.put("pair_id",    pairId);
        m.put("side",       o.getSide().name());
        m.put("type",       o.getType().name());
        m.put("price",      o.getPrice() != null ? o.getPrice().toPlainString() : null);
        m.put("amount",     o.getAmount().toPlainString());
        m.put("filled",     o.getFilled().toPlainString());
        m.put("status",     o.getStatus().name());
        m.put("created_at", o.getCreatedAt());
        return m;
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    private static BigDecimal parseBigDecimal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
