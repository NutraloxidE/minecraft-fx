package com.gekiyabafx.web;

import com.gekiyabafx.atm.AtmSessionManager;
import com.gekiyabafx.auth.SessionManager;
import com.gekiyabafx.config.PluginConfig;
import com.gekiyabafx.model.Execution;
import com.gekiyabafx.model.Order;
import com.gekiyabafx.model.OrderBook;
import com.gekiyabafx.model.Pair;
import com.gekiyabafx.storage.ExecutionRepository;
import com.gekiyabafx.storage.StorageManager;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 認証不要の公開 API エンドポイントを Javalin アプリに登録するルーター。
 *
 * <h3>エンドポイント</h3>
 * <ul>
 *   <li>{@code GET /api/pairs} — 全ペアのサマリー一覧を返す。</li>
 *   <li>{@code GET /api/orderbook?pair=DIAMOND/EMERALD} — 指定ペアの板（bids/asks）を返す。</li>
 *   <li>{@code GET /api/executions?pair=DIAMOND/EMERALD[&since=<timestamp>]} — 指定ペアの約定履歴を返す。</li>
 * </ul>
 *
 * <p>全エンドポイントは {@link StorageManager} のロックを取得してデータを読み出し、
 * スナップショットをコピーしてからロックを解放した後に JSON を生成する。
 * これにより JSON シリアライズ中に別スレッドがデータを書き換えても影響を受けない。</p>
 *
 * <p>BigDecimal は {@link BigDecimal#toPlainString()} で文字列に変換して返す。
 * （GSON を使わず Javalin の組み込み Jackson/JSON を使うため、文字列変換を自前で行う。）</p>
 */
public final class PublicApiRouter {

    private final ExecutionRepository executionRepo;
    private final PluginConfig pluginConfig;
    private final SessionManager playerSessionManager;
    private final AtmSessionManager atmSessionManager;

    /**
     * @param executionRepo 約定履歴リポジトリ（H2）
     * @param pluginConfig  手数料率参照用
     */
    public PublicApiRouter(
            ExecutionRepository executionRepo,
            PluginConfig pluginConfig,
            SessionManager playerSessionManager,
            AtmSessionManager atmSessionManager
    ) {
        this.executionRepo = executionRepo;
        this.pluginConfig  = pluginConfig;
        this.playerSessionManager = playerSessionManager;
        this.atmSessionManager = atmSessionManager;
    }

    /**
     * {@link Javalin} アプリに公開 API ルートを登録する。
     *
     * @param app {@link Javalin} インスタンス
     */
    public void register(Javalin app) {
        app.get("/api/pairs",           this::handlePairs);
        app.get("/api/pairs/{id}/fee",  this::handlePairFee);
        app.get("/api/orderbook",       this::handleOrderBook);
        app.get("/api/executions",      this::handleExecutions);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GET /api/pairs
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 全ペアのサマリー一覧を返す。
     *
     * <h4>レスポンス例（200）</h4>
     * <pre>{@code
     * [
     *   {
     *     "id":         "DIAMOND/EMERALD",
     *     "base":       "diamond",
     *     "quote":      "emerald",
     *     "enabled":    true,
     *     "min_amount": "0.0001",
     *     "min_price":  "0.0001",
     *     "last_price": "4.3000"    // null の場合はフィールドなし
     *   }
     * ]
     * }</pre>
     *
     * @param ctx Javalin コンテキスト
     */
    private void handlePairs(Context ctx) {
        // ── スナップショット取得（ロック内） ──────────────────────────────────
        List<Map<String, Object>> result = new ArrayList<>();
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            sm.getData().getPairs().forEach((pairId, pair) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",         pairId);
                m.put("base",       pair.getBase());
                m.put("quote",      pair.getQuote());
                m.put("enabled",    pair.isEnabled());
                m.put("min_amount", bdStr(pair.getMinAmount()));
                m.put("min_price",  bdStr(pair.getMinPrice()));
                if (pair.getLastPrice() != null) {
                    m.put("last_price", bdStr(pair.getLastPrice()));
                }
                result.add(m);
            });
        } finally {
            sm.unlock();
        }

        ctx.status(200).json(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GET /api/pairs/{id}/fee
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 指定ペアの手数料率を返す。
     *
     * <p>パスパラメータ {@code id} は URL エンコードされた pairId
     * （例: {@code DIAMOND%2FEMERALD}）を想定する。
     * ペアが存在しない場合でもグローバル手数料率を返す。
     *
     * <h4>レスポンス例（200）</h4>
     * <pre>{@code
     * {
     *   "pair":        "DIAMOND/EMERALD",
     *   "maker_base":  "0.0010",
     *   "taker_base":  "0.0012",
     *   "maker_quote": "0.0010",
     *   "taker_quote": "0.0012"
     * }
     * }</pre>
     *
     * @param ctx Javalin コンテキスト
     */
    private void handlePairFee(Context ctx) {
        String rawId = ctx.pathParam("id");
        // pairId の base / quote を取得してfeeOverridesを引く
        StorageManager sm = StorageManager.getInstance();
        Pair pair = null;
        sm.lock();
        try {
            pair = sm.getData().getPairs().get(rawId);
        } finally {
            sm.unlock();
        }

        BigDecimal globalMaker = pluginConfig.getFeeMaker();
        BigDecimal globalTaker = pluginConfig.getFeeTaker();

        BigDecimal makerBase;
        BigDecimal takerBase;
        BigDecimal makerQuote;
        BigDecimal takerQuote;

        if (pair != null) {
            makerBase  = pluginConfig.resolveFeeRate(pair.getBase(),  globalMaker);
            takerBase  = pluginConfig.resolveFeeRate(pair.getBase(),  globalTaker);
            makerQuote = pluginConfig.resolveFeeRate(pair.getQuote(), globalMaker);
            takerQuote = pluginConfig.resolveFeeRate(pair.getQuote(), globalTaker);
        } else {
            // ペアが存在しない場合はグローバル値を返す
            makerBase  = globalMaker;
            takerBase  = globalTaker;
            makerQuote = globalMaker;
            takerQuote = globalTaker;
        }

        // プレイヤー認証済みかつ ATM セッション有効なら、そのグレードに実適用される率を返す。
        String effectiveSource = "global";
        String effectiveGrade = null;
        String effectiveAtmId = null;
        String header = ctx.header("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            SessionManager.SessionEntry entry = playerSessionManager.resolve(token);
            if (entry != null) {
                var atmState = atmSessionManager.getStateByToken(token, entry.getIdentity());
                if (atmState != null && atmState.isActive()) {
                    PluginConfig.AtmFeeProfile profile = pluginConfig.findAtmFeeProfileByGrade(atmState.getGrade());
                    if (profile != null) {
                        makerBase = profile.getMakerRate();
                        takerBase = profile.getTakerRate();
                        makerQuote = profile.getMakerRate();
                        takerQuote = profile.getTakerRate();
                        effectiveSource = "atm";
                        effectiveGrade = profile.getGrade();
                        effectiveAtmId = atmState.getAtmId();
                    }
                }
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("pair",        rawId);
        resp.put("maker_base",  feeStr(makerBase));
        resp.put("taker_base",  feeStr(takerBase));
        resp.put("maker_quote", feeStr(makerQuote));
        resp.put("taker_quote", feeStr(takerQuote));
        resp.put("effective_source", effectiveSource);
        resp.put("effective_grade", effectiveGrade);
        resp.put("effective_atm_id", effectiveAtmId);
        resp.put("global_maker", feeStr(globalMaker));
        resp.put("global_taker", feeStr(globalTaker));
        ctx.status(200).json(resp);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GET /api/orderbook?pair=<pairId>
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 指定ペアの板（bids / asks）を返す。
     *
     * <h4>クエリパラメータ</h4>
     * <ul>
     *   <li>{@code pair} （必須）: ペアID（例: {@code DIAMOND/EMERALD}）</li>
     * </ul>
     *
     * <h4>レスポンス例（200）</h4>
     * <pre>{@code
     * {
     *   "pair": "DIAMOND/EMERALD",
     *   "bids": [
     *     { "order_id": "...", "price": "4.3000", "amount": "1.0000",
     *       "filled": "0.0000", "type": "LIMIT", "created_at": 1716100000 }
     *   ],
     *   "asks": [ ... ]
     * }
     * }</pre>
     *
     * <h4>エラー</h4>
     * <ul>
     *   <li>400 — {@code pair} パラメータなし</li>
     *   <li>404 — 存在しないペアID</li>
     * </ul>
     *
     * @param ctx Javalin コンテキスト
     */
    private void handleOrderBook(Context ctx) {
        String pairId = ctx.queryParam("pair");
        if (pairId == null || pairId.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing_pair"));
            return;
        }

        // ── スナップショット取得（ロック内） ──────────────────────────────────
        List<Map<String, Object>> bidsSnap;
        List<Map<String, Object>> asksSnap;
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            Pair pair = sm.getData().getPairs().get(pairId);
            if (pair == null) {
                ctx.status(404).json(Map.of("error", "pair_not_found"));
                return;
            }
            OrderBook ob = pair.getOrderBook();
            bidsSnap = serializeOrders(ob.getBids());
            asksSnap = serializeOrders(ob.getAsks());
        } finally {
            sm.unlock();
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("pair", pairId);
        resp.put("bids", bidsSnap);
        resp.put("asks", asksSnap);
        ctx.status(200).json(resp);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GET /api/executions?pair=<pairId>[&since=<unixSec>]
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 指定ペアの約定履歴を返す。
     *
     * <h4>クエリパラメータ</h4>
     * <ul>
     *   <li>{@code pair} （必須）: ペアID</li>
     *   <li>{@code since} （任意）: この Unix 秒より新しい約定のみ返す（差分ポーリング用）</li>
     * </ul>
     *
     * <h4>レスポンス例（200）</h4>
     * <pre>{@code
     * {
     *   "pair": "DIAMOND/EMERALD",
     *   "executions": [
     *     { "timestamp": 1716100000, "price": "4.3000", "amount": "0.5000" }
     *   ]
     * }
     * }</pre>
     *
     * <h4>エラー</h4>
     * <ul>
     *   <li>400 — {@code pair} パラメータなし</li>
     *   <li>404 — 存在しないペアID</li>
     * </ul>
     *
     * @param ctx Javalin コンテキスト
     */
    private void handleExecutions(Context ctx) {
        String pairId = ctx.queryParam("pair");
        if (pairId == null || pairId.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing_pair"));
            return;
        }

        // since パラメータ（省略時は 0 → 全件返却）
        long since = 0L;
        String sinceParam = ctx.queryParam("since");
        if (sinceParam != null && !sinceParam.isBlank()) {
            try {
                since = Long.parseLong(sinceParam.strip());
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "invalid_since"));
                return;
            }
        }

        // ── ペアの存在確認（ロック内） ────────────────────────────────────────
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            if (!sm.getData().getPairs().containsKey(pairId)) {
                ctx.status(404).json(Map.of("error", "pair_not_found"));
                return;
            }
        } finally {
            sm.unlock();
        }

        // ── H2 から約定履歴を取得（ロック外） ────────────────────────────────
        final long sinceF = since;
        List<Execution> execs = executionRepo.findByPairSince(pairId, sinceF);

        List<Map<String, Object>> execSnap = new ArrayList<>(execs.size());
        int legacySequence = 0;
        for (Execution ex : execs) {
            Map<String, Object> m = new LinkedHashMap<>();
            String executionId = ex.getExecutionId();
            if (executionId == null || executionId.isBlank()) {
                executionId = "legacy-" + ex.getTimestamp() + "-" + (legacySequence++);
            }
            m.put("execution_id", executionId);
            m.put("buyer_uuid", ex.getBuyerUuid());
            m.put("seller_uuid", ex.getSellerUuid());
            m.put("buy_order_id", ex.getBuyOrderId());
            m.put("sell_order_id", ex.getSellOrderId());
            m.put("timestamp", ex.getTimestamp());
            m.put("price",     bdStr(ex.getPrice()));
            m.put("amount",    bdStr(ex.getAmount()));
            m.put("atm_id",    ex.getAtmId());
            m.put("atm_grade", ex.getAtmGrade());
            execSnap.add(m);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("pair",       pairId);
        resp.put("executions", execSnap);
        ctx.status(200).json(resp);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  内部ユーティリティ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@link Order} リストをロック内でコピーし、JSON 用 Map リストに変換する。
     * ロック外で呼ぶために事前にスナップショットとして返す。
     *
     * @param orders 元のリスト（ロック内から渡すこと）
     * @return JSON 直列化用 Map のリスト
     */
    private static List<Map<String, Object>> serializeOrders(List<Order> orders) {
        List<Map<String, Object>> result = new ArrayList<>(orders.size());
        for (Order o : orders) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("order_id",   o.getOrderId());
            m.put("uuid",       o.getUuid());
            m.put("type",       o.getType().name());
            m.put("side",       o.getSide().name());
            if (o.getPrice() != null) {
                m.put("price", bdStr(o.getPrice()));
            }
            m.put("amount",     bdStr(o.getAmount()));
            if (o.getMaxSpend() != null) {
                m.put("max_spend", bdStr(o.getMaxSpend()));
            }
            if (o.getTriggerPrice() != null) {
                m.put("trigger_price", bdStr(o.getTriggerPrice()));
            }
            m.put("filled",     bdStr(o.getFilled()));
            m.put("status",     o.getStatus().name());
            m.put("created_at", o.getCreatedAt());
            result.add(m);
        }
        return result;
    }

    /**
     * {@link BigDecimal} を scale=4 の文字列に変換する。
     * {@code null} の場合は {@code "0.0000"} を返す。
     *
     * @param bd 変換対象
     * @return 文字列表現（例: {@code "4.3000"}）
     */
    private static String bdStr(BigDecimal bd) {
        if (bd == null) return "0.0000";
        return bd.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 手数料率を scale=4 の文字列に変換する（例: {@code "0.0010"}）。
     */
    private static String feeStr(BigDecimal bd) {
        if (bd == null) return "0.0000";
        return bd.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
