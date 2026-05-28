package com.gekiyabafx.engine;

import com.gekiyabafx.config.PluginConfig;
import com.gekiyabafx.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MatchingEngine} の単体テスト。
 *
 * <p>テスト対象シナリオ:</p>
 * <ol>
 *   <li>指値 BUY / 指値 SELL — 完全約定</li>
 *   <li>指値 BUY / 指値 SELL — 部分約定（buy &lt; sell）</li>
 *   <li>指値 BUY / 指値 SELL — 部分約定（buy &gt; sell）</li>
 *   <li>成行 BUY — 板と即時約定・未使用 quote 返還</li>
 *   <li>成行 SELL — 板と即時約定</li>
 *   <li>価格不一致 — 板に積まれるだけで約定しない</li>
 *   <li>残高不足 — {@link InsufficientBalanceException} がスローされる</li>
 *   <li>指値キャンセル — ロック返還が正しい</li>
 *   <li>Price-Time Priority — 先着優先</li>
 *   <li>成行 BUY maxSpend ぴったり消費</li>
 * </ol>
 */
class MatchingEngineTest {

    // ── テスト用定数 ─────────────────────────────────────────────────────────
    private static final String PAIR_ID = "DIAMOND/EMERALD";
    private static final String BASE    = "diamond";
    private static final String QUOTE   = "emerald";

    private PluginConfig config;
    private StorageData  data;
    private Pair         pair;

    // ── セットアップ ──────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        config = testConfig();
        data   = new StorageData();
        pair   = new Pair(BASE, QUOTE, true,
                new BigDecimal("0.0001"), new BigDecimal("0.0001"));
        data.getPairs().put(PAIR_ID, pair);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  1. 指値 完全約定
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("指値 BUY と指値 SELL が完全約定する")
    void limitBuySell_fullMatch() throws Exception {
        String seller = newPlayer("seller", BASE, "10.0000", QUOTE, "0.0000");
        String buyer  = newPlayer("buyer",  BASE, "0.0000",  QUOTE, "50.0000");

        // 先に SELL を板に積む
        Order sellOrder = limitOrder(seller, OrderSide.SELL, "4.0000", "2.0000");
        MatchResult r1 = MatchingEngine.placeOrder(PAIR_ID, pair, sellOrder, data, config);
        assertTrue(r1.getExecutions().isEmpty(), "板に積まれるだけ");
        assertEquals(1, pair.getOrderBook().getAsks().size());

        // BUY で約定させる
        Order buyOrder = limitOrder(buyer, OrderSide.BUY, "4.0000", "2.0000");
        MatchResult r2 = MatchingEngine.placeOrder(PAIR_ID, pair, buyOrder, data, config);

        assertEquals(1, r2.getExecutions().size());
        assertEquals(bd("2.0000"), r2.getExecutions().get(0).getAmount());
        assertEquals(bd("4.0000"), r2.getExecutions().get(0).getPrice());

        // 板が空になっている
        assertTrue(pair.getOrderBook().getAsks().isEmpty());
        assertTrue(pair.getOrderBook().getBids().isEmpty());

        // 残高確認
        PlayerData pd = data.getPlayers().get(buyer);
        assertEquals(bd("2.0000"), pd.getHotBalance(BASE));
        assertEquals(bd("42.0000"), pd.getHotBalance(QUOTE));  // 50 - 4×2 = 42

        PlayerData ps = data.getPlayers().get(seller);
        assertEquals(bd("8.0000"), ps.getHotBalance(BASE));    // 10 - 2 = 8
        assertEquals(bd("8.0000"), ps.getHotBalance(QUOTE));   // 0 + 4×2 = 8
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  2. 部分約定 — buy < sell
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("buy(1) < sell(3) → 部分約定、ask が PARTIALLY_FILLED で残る")
    void limitBuy_partialFill_askRemains() throws Exception {
        String seller = newPlayer("seller", BASE, "3.0000", QUOTE, "0.0000");
        String buyer  = newPlayer("buyer",  BASE, "0.0000", QUOTE, "10.0000");

        Order sellOrder = limitOrder(seller, OrderSide.SELL, "2.0000", "3.0000");
        MatchingEngine.placeOrder(PAIR_ID, pair, sellOrder, data, config);

        Order buyOrder = limitOrder(buyer, OrderSide.BUY, "2.0000", "1.0000");
        MatchResult r = MatchingEngine.placeOrder(PAIR_ID, pair, buyOrder, data, config);

        assertEquals(1, r.getExecutions().size());
        assertEquals(bd("1.0000"), r.getExecutions().get(0).getAmount());

        // ask は残量 2.0000 で PARTIALLY_FILLED
        assertEquals(1, pair.getOrderBook().getAsks().size());
        assertEquals(OrderStatus.PARTIALLY_FILLED, pair.getOrderBook().getAsks().get(0).getStatus());
        assertEquals(bd("2.0000"), pair.getOrderBook().getAsks().get(0).getRemainingAmount());

        // BUY 側は FILLED で履歴へ
        assertEquals(1, pair.getOrderHistory().size());
        assertEquals(OrderStatus.FILLED, pair.getOrderHistory().get(0).getStatus());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  3. 部分約定 — buy > sell
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("buy(3) > sell(1) → 部分約定、bid が PARTIALLY_FILLED で残る")
    void limitSell_partialFill_bidRemains() throws Exception {
        String seller = newPlayer("seller", BASE, "1.0000", QUOTE, "0.0000");
        String buyer  = newPlayer("buyer",  BASE, "0.0000", QUOTE, "10.0000");

        // 先に BUY を板に積む
        Order buyOrder = limitOrder(buyer, OrderSide.BUY, "2.0000", "3.0000");
        MatchingEngine.placeOrder(PAIR_ID, pair, buyOrder, data, config);

        // SELL で部分約定
        Order sellOrder = limitOrder(seller, OrderSide.SELL, "2.0000", "1.0000");
        MatchResult r = MatchingEngine.placeOrder(PAIR_ID, pair, sellOrder, data, config);

        assertEquals(1, r.getExecutions().size());
        assertEquals(bd("1.0000"), r.getExecutions().get(0).getAmount());

        // bid は残量 2.0000 で PARTIALLY_FILLED
        assertEquals(1, pair.getOrderBook().getBids().size());
        assertEquals(OrderStatus.PARTIALLY_FILLED, pair.getOrderBook().getBids().get(0).getStatus());
        assertEquals(bd("2.0000"), pair.getOrderBook().getBids().get(0).getRemainingAmount());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  4. 成行 BUY — 未使用 quote 返還
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("成行 BUY — maxSpend で板を部分消費し、未使用 quote がホットへ返還される")
    void marketBuy_refundUnusedQuote() throws Exception {
        String seller = newPlayer("seller", BASE, "10.0000", QUOTE, "0.0000");
        String buyer  = newPlayer("buyer",  BASE, "0.0000",  QUOTE, "100.0000");

        // ask: price=4, amount=3
        Order sellOrder = limitOrder(seller, OrderSide.SELL, "4.0000", "3.0000");
        MatchingEngine.placeOrder(PAIR_ID, pair, sellOrder, data, config);

        // 成行 BUY: maxSpend=20 → 20/4=5 だが ask は 3 しかないので 3 しか買えない
        Order marketBuy = marketBuyOrder(buyer, "20.0000");
        MatchResult r = MatchingEngine.placeOrder(PAIR_ID, pair, marketBuy, data, config);

        assertEquals(1, r.getExecutions().size());
        assertEquals(bd("3.0000"), r.getExecutions().get(0).getAmount());

        PlayerData pd = data.getPlayers().get(buyer);
        // 支払い: 4×3=12, 返還: 20-12=8 → 残 hot_quote = 100-12=88
        assertEquals(bd("88.0000"), pd.getHotBalance(QUOTE));
        assertEquals(bd("3.0000"),  pd.getHotBalance(BASE));

        // 成行は板に残らない
        assertTrue(pair.getOrderBook().getBids().isEmpty());
        // locked_quote = 0
        assertEquals(bd("0.0000"), pd.getLockedBalance(PAIR_ID, QUOTE));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  5. 成行 SELL
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("成行 SELL — 全量約定")
    void marketSell_fullMatch() throws Exception {
        String buyer  = newPlayer("buyer",  BASE, "0.0000", QUOTE, "20.0000");
        String seller = newPlayer("seller", BASE, "2.0000", QUOTE, "0.0000");

        Order buyOrder = limitOrder(buyer, OrderSide.BUY, "5.0000", "2.0000");
        MatchingEngine.placeOrder(PAIR_ID, pair, buyOrder, data, config);

        Order marketSell = marketSellOrder(seller, "2.0000");
        MatchResult r = MatchingEngine.placeOrder(PAIR_ID, pair, marketSell, data, config);

        assertEquals(1, r.getExecutions().size());
        assertEquals(bd("2.0000"), r.getExecutions().get(0).getAmount());

        PlayerData ps = data.getPlayers().get(seller);
        assertEquals(bd("0.0000"), ps.getHotBalance(BASE));
        assertEquals(bd("10.0000"), ps.getHotBalance(QUOTE));  // 5×2=10
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  6. 価格不一致 — 板に積まれるだけ
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("buy.price < ask.price → 約定しない、両方板に残る")
    void priceNoMatch_noExecution() throws Exception {
        String seller = newPlayer("seller", BASE, "5.0000", QUOTE, "0.0000");
        String buyer  = newPlayer("buyer",  BASE, "0.0000", QUOTE, "30.0000");

        Order sellOrder = limitOrder(seller, OrderSide.SELL, "5.0000", "1.0000");
        MatchResult r1 = MatchingEngine.placeOrder(PAIR_ID, pair, sellOrder, data, config);
        assertTrue(r1.getExecutions().isEmpty());

        Order buyOrder = limitOrder(buyer, OrderSide.BUY, "4.0000", "1.0000");
        MatchResult r2 = MatchingEngine.placeOrder(PAIR_ID, pair, buyOrder, data, config);
        assertTrue(r2.getExecutions().isEmpty());

        assertEquals(1, pair.getOrderBook().getBids().size());
        assertEquals(1, pair.getOrderBook().getAsks().size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  7. 残高不足
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("quote 不足の BUY → InsufficientBalanceException")
    void insufficientBalance_throwsException() {
        String buyer = newPlayer("buyer", BASE, "0.0000", QUOTE, "1.0000");

        Order buyOrder = limitOrder(buyer, OrderSide.BUY, "5.0000", "2.0000"); // 必要 10, 保有 1
        assertThrows(InsufficientBalanceException.class, () ->
                MatchingEngine.placeOrder(PAIR_ID, pair, buyOrder, data, config));

        // 残高は変わっていない
        assertEquals(bd("1.0000"), data.getPlayers().get(buyer).getHotBalance(QUOTE));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  8. キャンセル — ロック返還
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("指値 BUY キャンセル → locked_quote がホットへ戻る")
    void cancelLimitBuy_refundsLock() throws Exception {
        String buyer = newPlayer("buyer", BASE, "0.0000", QUOTE, "20.0000");

        Order buyOrder = limitOrder(buyer, OrderSide.BUY, "4.0000", "2.0000"); // lock = 8
        MatchingEngine.placeOrder(PAIR_ID, pair, buyOrder, data, config);

        PlayerData pd = data.getPlayers().get(buyer);
        assertEquals(bd("12.0000"), pd.getHotBalance(QUOTE));   // 20-8=12
        assertEquals(bd("8.0000"),  pd.getLockedBalance(PAIR_ID, QUOTE));

        // 板から除去してキャンセル
        pair.getOrderBook().getBids().remove(buyOrder);
        MatchingEngine.cancelOrder(PAIR_ID, pair, buyOrder, data, config);

        assertEquals(bd("20.0000"), pd.getHotBalance(QUOTE));
        assertEquals(bd("0.0000"),  pd.getLockedBalance(PAIR_ID, QUOTE));
        assertEquals(OrderStatus.CANCELLED, buyOrder.getStatus());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  9. Price-Time Priority — 先着優先
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("同価格の asks は時刻昇順で先着が先に約定する")
    void priceTimePriority_earlierAskFilledFirst() throws Exception {
        String s1 = newPlayer("seller1", BASE, "5.0000", QUOTE, "0.0000");
        String s2 = newPlayer("seller2", BASE, "5.0000", QUOTE, "0.0000");
        String buyer = newPlayer("buyer", BASE, "0.0000", QUOTE, "100.0000");

        // seller1 が先（createdAt が小さい）
        Order ask1 = limitOrderAt(s1, OrderSide.SELL, "3.0000", "2.0000", 1000L);
        Order ask2 = limitOrderAt(s2, OrderSide.SELL, "3.0000", "2.0000", 2000L);
        MatchingEngine.placeOrder(PAIR_ID, pair, ask1, data, config);
        MatchingEngine.placeOrder(PAIR_ID, pair, ask2, data, config);

        // buyer が 2 だけ買う → ask1 から先に約定するはず
        Order buyOrder = limitOrderAt(buyer, OrderSide.BUY, "3.0000", "2.0000", 3000L);
        MatchResult r = MatchingEngine.placeOrder(PAIR_ID, pair, buyOrder, data, config);

        assertEquals(1, r.getExecutions().size());
        // ask1 が FILLED、ask2 が残る
        assertEquals(0, pair.getOrderBook().getBids().size());
        assertEquals(1, pair.getOrderBook().getAsks().size());
        assertEquals(s2, pair.getOrderBook().getAsks().get(0).getUuid());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  10. 成行 BUY — maxSpend ぴったり消費
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("成行 BUY — maxSpend ちょうどで全量約定、ロック残 0")
    void marketBuy_exactSpend() throws Exception {
        String seller = newPlayer("seller", BASE, "5.0000", QUOTE, "0.0000");
        String buyer  = newPlayer("buyer",  BASE, "0.0000", QUOTE, "12.0000");

        Order sellOrder = limitOrder(seller, OrderSide.SELL, "4.0000", "3.0000");
        MatchingEngine.placeOrder(PAIR_ID, pair, sellOrder, data, config);

        Order marketBuy = marketBuyOrder(buyer, "12.0000"); // 12/4=3
        MatchResult r = MatchingEngine.placeOrder(PAIR_ID, pair, marketBuy, data, config);

        assertEquals(1, r.getExecutions().size());
        assertEquals(bd("3.0000"), r.getExecutions().get(0).getAmount());

        PlayerData pd = data.getPlayers().get(buyer);
        assertEquals(bd("3.0000"), pd.getHotBalance(BASE));
        assertEquals(bd("0.0000"), pd.getHotBalance(QUOTE));
        assertEquals(bd("0.0000"), pd.getLockedBalance(PAIR_ID, QUOTE));
    }

    @Test
    @DisplayName("STOP_MARKET SELL はトリガー到達後に成行実行される")
    void stopMarketSell_triggersAndExecutes() throws Exception {
        String bidMaker = newPlayer("bidMaker", BASE, "0.0000", QUOTE, "20.0000");
        String triggerSeller = newPlayer("triggerSeller", BASE, "2.0000", QUOTE, "0.0000");
        String firstSeller = newPlayer("firstSeller", BASE, "1.0000", QUOTE, "0.0000");

        // 価格 3.0 の買い板を 2.0 積む
        Order bid = limitOrder(bidMaker, OrderSide.BUY, "3.0000", "2.0000");
        MatchingEngine.placeOrder(PAIR_ID, pair, bid, data, config);

        // 逆指値成行 SELL（lastPrice <= 4.0 で発火）
        Order stopSell = new Order(
                UUID.randomUUID().toString(), triggerSeller,
                OrderType.STOP_MARKET, OrderSide.SELL,
                null,
                new BigDecimal("1.0000"),
                null,
                Instant.now().getEpochSecond()
        );
        stopSell.setTriggerPrice(new BigDecimal("4.0000"));
        MatchingEngine.placeConditionalOrder(PAIR_ID, pair, stopSell, data, config);

        // 別の SELL 成行で lastPrice=3.0 を作る → STOP が発火する
        Order marketSell = marketSellOrder(firstSeller, "1.0000");
        MatchingEngine.placeOrder(PAIR_ID, pair, marketSell, data, config);

        assertTrue(pair.getConditionalOrders().isEmpty());
        assertEquals(OrderStatus.FILLED, stopSell.getStatus());

        PlayerData triggerPd = data.getPlayers().get(triggerSeller);
        assertEquals(bd("1.0000"), triggerPd.getHotBalance(BASE));
        assertEquals(bd("3.0000"), triggerPd.getHotBalance(QUOTE));
        assertEquals(bd("0.0000"), triggerPd.getLockedBalance(PAIR_ID, BASE));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ヘルパーメソッド
    // ─────────────────────────────────────────────────────────────────────────

    /** PluginConfig のテスト用インスタンス（履歴上限を大きめに設定）。 */
    private static PluginConfig testConfig() {
        return PluginConfig.forTest(
                "127.0.0.1",
                3010,
                false,
                300L,
                1800L,
                10000,
                500
        );
    }

    /**
     * プレイヤーデータを StorageData に登録し、UUID を返す。
     *
     * @param name     プレイヤー名
     * @param baseItem base アイテム名
     * @param baseAmt  ホット残高（base）
     * @param quoteItem quote アイテム名
     * @param quoteAmt ホット残高（quote）
     */
    private String newPlayer(
            String name, String baseItem, String baseAmt,
            String quoteItem, String quoteAmt
    ) {
        String uuid = UUID.randomUUID().toString();
        PlayerData pd = new PlayerData(name);
        pd.setHotBalance(baseItem,  new BigDecimal(baseAmt));
        pd.setHotBalance(quoteItem, new BigDecimal(quoteAmt));
        data.getPlayers().put(uuid, pd);
        return uuid;
    }

    private Order limitOrder(String uuid, OrderSide side, String price, String amount) {
        return limitOrderAt(uuid, side, price, amount, Instant.now().getEpochSecond());
    }

    private Order limitOrderAt(String uuid, OrderSide side, String price, String amount, long createdAt) {
        return new Order(
                UUID.randomUUID().toString(), uuid,
                OrderType.LIMIT, side,
                new BigDecimal(price),
                new BigDecimal(amount),
                null,
                createdAt
        );
    }

    private Order marketBuyOrder(String uuid, String maxSpend) {
        return new Order(
                UUID.randomUUID().toString(), uuid,
                OrderType.MARKET, OrderSide.BUY,
                null,
                BigDecimal.ZERO,
                new BigDecimal(maxSpend),
                Instant.now().getEpochSecond()
        );
    }

    private Order marketSellOrder(String uuid, String amount) {
        return new Order(
                UUID.randomUUID().toString(), uuid,
                OrderType.MARKET, OrderSide.SELL,
                null,
                new BigDecimal(amount),
                null,
                Instant.now().getEpochSecond()
        );
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v).setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
