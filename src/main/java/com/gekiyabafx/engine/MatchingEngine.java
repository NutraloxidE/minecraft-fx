package com.gekiyabafx.engine;

import com.gekiyabafx.config.PluginConfig;
import com.gekiyabafx.model.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Price-Time Priority マッチングエンジン。
 *
 * <h3>責務</h3>
 * <ul>
 *   <li>発注時の残高ロック（ホット→ロック）</li>
 *   <li>指値・成行の板マッチング（部分約定対応）</li>
 *   <li>約定時の残高決済（ロック解放＋相手ホットへ移動）</li>
 *   <li>注文キャンセル時のロック返還（ロック→ホット）</li>
 * </ul>
 *
 * <h3>呼び出し規約</h3>
 * <p>全メソッドは {@link com.gekiyabafx.storage.StorageManager} の
 * ロック（{@code lock()} / {@code unlock()}）内から呼ぶこと。</p>
 *
 * <h3>板の順序</h3>
 * <ul>
 *   <li>bids（買い板）: 価格降順 → 時刻昇順</li>
 *   <li>asks（売り板）: 価格昇順 → 時刻昇順</li>
 * </ul>
 */
public final class MatchingEngine {

    // 価格降順・時刻昇順（bids）
    private static final Comparator<Order> BID_COMPARATOR =
            Comparator.comparing(Order::getPrice, Comparator.reverseOrder())
                      .thenComparingLong(Order::getCreatedAt);

    // 価格昇順・時刻昇順（asks）
    private static final Comparator<Order> ASK_COMPARATOR =
            Comparator.comparing(Order::getPrice)
                      .thenComparingLong(Order::getCreatedAt);

    private MatchingEngine() {}

    // ─────────────────────────────────────────────────────────────────────────
    //  公開 API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 注文を発注し、板とのマッチングを実行する。
     *
     * <ol>
     *   <li>残高をロックする（不足なら {@link InsufficientBalanceException}）。</li>
     *   <li>反対側の板に対してマッチングを実行する。</li>
     *   <li>成行注文：残量・未消費ロックを返還して即時クローズ。</li>
     *   <li>指値注文：残量があれば板に追加、なければ FILLED でクローズ。</li>
     *   <li>約定一覧・lastPrice を更新する。</li>
     * </ol>
     *
     * @param pairId   ペアID（例: {@code "DIAMOND/EMERALD"}）
     * @param pair     ペアオブジェクト
     * @param incoming 発注する注文（UUID / orderId / type / side / price / amount / maxSpend を設定済みであること）
     * @param data     StorageData（残高操作に使用）
     * @param config   プラグイン設定（履歴上限に使用）
     * @return {@link MatchResult}（発生した約定一覧を含む）
     * @throws InsufficientBalanceException ホット残高が不足している場合
     */
    public static MatchResult placeOrder(
            String pairId,
            Pair pair,
            Order incoming,
            StorageData data,
            PluginConfig config
    ) throws InsufficientBalanceException {

        // ① 残高をロックする
        lockBalance(pairId, pair, incoming, data);

        // ② マッチングを実行する
        List<Execution> newExecs = new ArrayList<>();
        BigDecimal spentQuote = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        if (incoming.getSide() == OrderSide.BUY) {
            spentQuote = matchBuy(pairId, pair, incoming, data, newExecs, config);
        } else {
            matchSell(pairId, pair, incoming, data, newExecs, config);
        }

        long now = Instant.now().getEpochSecond();

        // ③ 約定後の残量を処理する
        if (incoming.getType() == OrderType.MARKET) {
            // 成行：未消費ロックを返還して即時クローズ
            refundMarketLock(pairId, pair, incoming, data, spentQuote);
            incoming.setStatus(newExecs.isEmpty() ? OrderStatus.CANCELLED : OrderStatus.FILLED);
            incoming.setClosedAt(now);
            addToHistory(pair, incoming, config);
        } else {
            // 指値：残量があれば板に積む、なければ FILLED
            boolean hasRemaining = incoming.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0;
            if (hasRemaining) {
                incoming.setStatus(
                        incoming.getFilled().compareTo(BigDecimal.ZERO) > 0
                                ? OrderStatus.PARTIALLY_FILLED
                                : OrderStatus.OPEN
                );
                if (incoming.getSide() == OrderSide.BUY) {
                    pair.getOrderBook().getBids().add(incoming);
                    pair.getOrderBook().getBids().sort(BID_COMPARATOR);
                } else {
                    pair.getOrderBook().getAsks().add(incoming);
                    pair.getOrderBook().getAsks().sort(ASK_COMPARATOR);
                }
            } else {
                incoming.setStatus(OrderStatus.FILLED);
                incoming.setClosedAt(now);
                addToHistory(pair, incoming, config);
            }
        }

        // ④ lastPrice を更新する（約定履歴は H2ExecutionRepository に委譲）
        if (!newExecs.isEmpty()) {
            pair.setLastPrice(newExecs.get(newExecs.size() - 1).getPrice());
        }

        return new MatchResult(newExecs);
    }

    /**
     * 指値注文をキャンセルし、ロック残高をホットへ返還する。
     *
     * <p>板（{@code orderBook}）からの除去は呼び出し側が行うこと。</p>
     *
     * @param pairId ペアID
     * @param pair   ペアオブジェクト
     * @param order  キャンセルする注文（LIMIT のみ。MARKET は板に残らない）
     * @param data   StorageData
     * @param config プラグイン設定
     */
    public static void cancelOrder(
            String pairId,
            Pair pair,
            Order order,
            StorageData data,
            PluginConfig config
    ) {
        // 既にクローズ済みの注文への二重キャンセルを防ぐ（無限アイテム増殖バグ対策）
        if (order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.FILLED) {
            return;
        }

        PlayerData pd = data.getPlayers().get(order.getUuid());
        if (pd == null) return;

        String base  = pair.getBase();
        String quote = pair.getQuote();

        if (order.getSide() == OrderSide.BUY) {
            // 残数量 × 指値価格 分の quote ロックを返還
            BigDecimal refund = order.getPrice()
                    .multiply(order.getRemainingAmount())
                    .setScale(4, RoundingMode.HALF_UP);
            returnLock(pairId, quote, refund, pd);
        } else {
            // 残数量分の base ロックを返還
            returnLock(pairId, base, order.getRemainingAmount(), pd);
        }

        long now = Instant.now().getEpochSecond();
        order.setStatus(OrderStatus.CANCELLED);
        order.setClosedAt(now);
        addToHistory(pair, order, config);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  残高ロック
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 発注に必要な残高をホットからロックへ移動する。
     *
     * <table border="1">
     *   <tr><th>注文種別</th><th>ロックするアイテム</th><th>ロック量</th></tr>
     *   <tr><td>LIMIT BUY</td><td>quote</td><td>price × amount</td></tr>
     *   <tr><td>LIMIT SELL</td><td>base</td><td>amount</td></tr>
     *   <tr><td>MARKET BUY</td><td>quote</td><td>maxSpend</td></tr>
     *   <tr><td>MARKET SELL</td><td>base</td><td>amount</td></tr>
     * </table>
     *
     * @throws InsufficientBalanceException ホット残高が不足している場合
     */
    private static void lockBalance(
            String pairId, Pair pair, Order order, StorageData data
    ) throws InsufficientBalanceException {

        PlayerData pd = data.getPlayers().get(order.getUuid());
        if (pd == null) {
            throw new InsufficientBalanceException(
                    "プレイヤーデータが存在しません: " + order.getUuid());
        }

        String base  = pair.getBase();
        String quote = pair.getQuote();
        String lockItem;
        BigDecimal lockAmount;

        if (order.getSide() == OrderSide.BUY) {
            lockItem = quote;
            lockAmount = (order.getType() == OrderType.MARKET)
                    ? order.getMaxSpend().setScale(4, RoundingMode.HALF_UP)
                    : order.getPrice().multiply(order.getAmount()).setScale(4, RoundingMode.HALF_UP);
        } else {
            lockItem = base;
            lockAmount = order.getAmount().setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal hot = pd.getHotBalance(lockItem);
        if (hot.compareTo(lockAmount) < 0) {
            throw new InsufficientBalanceException(
                    "残高不足: " + lockItem
                            + " 必要=" + lockAmount.toPlainString()
                            + " 保有=" + hot.toPlainString());
        }

        pd.setHotBalance(lockItem,
                hot.subtract(lockAmount).setScale(4, RoundingMode.HALF_UP));
        pd.setLockedBalance(pairId, lockItem,
                pd.getLockedBalance(pairId, lockItem).add(lockAmount).setScale(4, RoundingMode.HALF_UP));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  マッチングループ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * BUY 注文を asks（売り板、価格昇順）とマッチングする。
     *
     * <ul>
     *   <li>LIMIT BUY: {@code buy.price >= ask.price} の asks とマッチ。</li>
     *   <li>MARKET BUY: {@code maxSpend - spentQuote > 0} の間、price 問わずマッチ。
     *       各 ask との exec amount は {@code remainSpend / execPrice}（切り捨て）上限。</li>
     * </ul>
     *
     * @return 消費した quote 合計（MARKET BUY のロック返還計算用）
     */
    private static BigDecimal matchBuy(
            String pairId, Pair pair, Order buy,
            StorageData data, List<Execution> execs, PluginConfig config
    ) {
        BigDecimal spentQuote = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        Iterator<Order> it = pair.getOrderBook().getAsks().iterator();
        long now = Instant.now().getEpochSecond();

        while (it.hasNext()) {
            Order ask = it.next();

            // LIMIT: 指値より高い ask は不一致（asks は価格昇順なので以降もすべて不一致）
            if (buy.getType() == OrderType.LIMIT
                    && buy.getPrice().compareTo(ask.getPrice()) < 0) {
                break;
            }

            BigDecimal execPrice = ask.getPrice();
            BigDecimal askRemain = ask.getRemainingAmount();
            BigDecimal execAmount;

            if (buy.getType() == OrderType.MARKET) {
                BigDecimal remainSpend = buy.getMaxSpend()
                        .subtract(spentQuote)
                        .setScale(4, RoundingMode.HALF_UP);
                if (remainSpend.compareTo(BigDecimal.ZERO) <= 0) break;

                // remainSpend で買える最大量（切り捨て）
                BigDecimal maxBuyable = remainSpend.divide(execPrice, 4, RoundingMode.DOWN);
                if (maxBuyable.compareTo(BigDecimal.ZERO) <= 0) break;

                execAmount = maxBuyable.min(askRemain).setScale(4, RoundingMode.HALF_UP);
            } else {
                // LIMIT BUY
                BigDecimal buyRemain = buy.getRemainingAmount();
                if (buyRemain.compareTo(BigDecimal.ZERO) <= 0) break;
                execAmount = buyRemain.min(askRemain).setScale(4, RoundingMode.HALF_UP);
            }

            if (execAmount.compareTo(BigDecimal.ZERO) <= 0) break;

            settle(pairId, pair, buy, ask, execPrice, execAmount, data, config, true);
            spentQuote = spentQuote.add(
                    execPrice.multiply(execAmount).setScale(4, RoundingMode.HALF_UP));
            execs.add(new Execution(now, execPrice, execAmount));

            // ask が全量約定したら板から除去
            if (ask.getRemainingAmount().compareTo(BigDecimal.ZERO) == 0) {
                ask.setStatus(OrderStatus.FILLED);
                ask.setClosedAt(now);
                it.remove();
                addToHistory(pair, ask, config);
            } else {
                ask.setStatus(OrderStatus.PARTIALLY_FILLED);
            }

            // buy 残量チェック（MARKET: 残 spend、LIMIT: 残 amount）
            boolean buyExhausted;
            if (buy.getType() == OrderType.MARKET) {
                BigDecimal remainSpend = buy.getMaxSpend().subtract(spentQuote)
                        .setScale(4, RoundingMode.HALF_UP);
                buyExhausted = remainSpend.compareTo(BigDecimal.ZERO) <= 0;
            } else {
                buyExhausted = buy.getRemainingAmount().compareTo(BigDecimal.ZERO) <= 0;
            }
            if (buyExhausted) break;
        }

        return spentQuote;
    }

    /**
     * SELL 注文を bids（買い板、価格降順）とマッチングする。
     *
     * <ul>
     *   <li>LIMIT SELL: {@code sell.price <= bid.price} の bids とマッチ。</li>
     *   <li>MARKET SELL: price 問わず残量がなくなるまでマッチ。</li>
     * </ul>
     */
    private static void matchSell(
            String pairId, Pair pair, Order sell,
            StorageData data, List<Execution> execs, PluginConfig config
    ) {
        Iterator<Order> it = pair.getOrderBook().getBids().iterator();
        long now = Instant.now().getEpochSecond();

        while (it.hasNext() && sell.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0) {
            Order bid = it.next();

            // LIMIT: 指値より低い bid は不一致（bids は価格降順なので以降もすべて不一致）
            if (sell.getType() == OrderType.LIMIT
                    && sell.getPrice().compareTo(bid.getPrice()) > 0) {
                break;
            }

            BigDecimal execPrice  = bid.getPrice();
            BigDecimal sellRemain = sell.getRemainingAmount();
            BigDecimal bidRemain  = bid.getRemainingAmount();
            BigDecimal execAmount = sellRemain.min(bidRemain).setScale(4, RoundingMode.HALF_UP);

            if (execAmount.compareTo(BigDecimal.ZERO) <= 0) break;

            settle(pairId, pair, bid, sell, execPrice, execAmount, data, config, false);
            execs.add(new Execution(now, execPrice, execAmount));

            // bid が全量約定したら板から除去
            if (bid.getRemainingAmount().compareTo(BigDecimal.ZERO) == 0) {
                bid.setStatus(OrderStatus.FILLED);
                bid.setClosedAt(now);
                it.remove();
                addToHistory(pair, bid, config);
            } else {
                bid.setStatus(OrderStatus.PARTIALLY_FILLED);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  約定決済（残高移動）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 1回の約定を決済する。
     *
     * <ul>
     *   <li>買い手: {@code locked_quote -= cost}, {@code hot_base += execAmount - baseFee}</li>
     *   <li>売り手: {@code locked_base -= execAmount}, {@code hot_quote += cost - quoteFee}</li>
     *   <li>baseFee → {@code svc:treasury-fee} の base 残高へ加算</li>
     *   <li>quoteFee → {@code svc:treasury-fee} の quote 残高へ加算</li>
     *   <li>incoming（Taker）は Taker レート、板の相手（Maker）は Maker レートを適用する。</li>
     * </ul>
     *
     * @param isBuyTaker {@code true} なら buy が Taker（incoming）、sell が Maker
     */
    private static void settle(
            String pairId, Pair pair,
            Order buy, Order sell,
            BigDecimal execPrice, BigDecimal execAmount,
            StorageData data, PluginConfig config,
            boolean isBuyTaker
    ) {
        String base  = pair.getBase();
        String quote = pair.getQuote();
        BigDecimal cost = execPrice.multiply(execAmount).setScale(4, RoundingMode.HALF_UP);

        // ─── 手数料率の決定 ───────────────────────────────────────────────────
        // buy（base 受取）側の手数料率: buy が Taker なら taker レート、Maker なら maker レート
        BigDecimal buyFeeRate = config.resolveFeeRate(base,
                isBuyTaker ? config.getFeeTaker() : config.getFeeMaker());
        // sell（quote 受取）側の手数料率: sell が Taker なら taker レート、Maker なら maker レート
        BigDecimal sellFeeRate = config.resolveFeeRate(quote,
                isBuyTaker ? config.getFeeMaker() : config.getFeeTaker());

        // ─── 手数料額の計算 ───────────────────────────────────────────────────
        BigDecimal baseFee  = execAmount.multiply(buyFeeRate).setScale(4, RoundingMode.HALF_UP);
        BigDecimal quoteFee = cost.multiply(sellFeeRate).setScale(4, RoundingMode.HALF_UP);

        // ─── 買い手: locked_quote → hot_base（手数料差し引き後） ──────────────
        PlayerData buyer = data.getPlayers().get(buy.getUuid());
        if (buyer != null) {
            buyer.setLockedBalance(pairId, quote,
                    buyer.getLockedBalance(pairId, quote)
                         .subtract(cost).max(BigDecimal.ZERO)
                         .setScale(4, RoundingMode.HALF_UP));
            buyer.setHotBalance(base,
                    buyer.getHotBalance(base)
                         .add(execAmount).subtract(baseFee)
                         .setScale(4, RoundingMode.HALF_UP));
        }

        // ─── 売り手: locked_base → hot_quote（手数料差し引き後） ──────────────
        PlayerData seller = data.getPlayers().get(sell.getUuid());
        if (seller != null) {
            seller.setLockedBalance(pairId, base,
                    seller.getLockedBalance(pairId, base)
                          .subtract(execAmount).max(BigDecimal.ZERO)
                          .setScale(4, RoundingMode.HALF_UP));
            seller.setHotBalance(quote,
                    seller.getHotBalance(quote)
                          .add(cost).subtract(quoteFee)
                          .setScale(4, RoundingMode.HALF_UP));
        }

        // ─── treasury-fee への手数料振り込み ─────────────────────────────────
        final String TREASURY_ID = "svc:treasury-fee";
        PlayerData treasury = data.getPlayers().get(TREASURY_ID);
        if (treasury == null) {
            treasury = new PlayerData("[SERVICE] treasury-fee");
            data.getPlayers().put(TREASURY_ID, treasury);
        }
        if (baseFee.compareTo(BigDecimal.ZERO) > 0) {
            treasury.setHotBalance(base,
                    treasury.getHotBalance(base).add(baseFee)
                            .setScale(4, RoundingMode.HALF_UP));
        }
        if (quoteFee.compareTo(BigDecimal.ZERO) > 0) {
            treasury.setHotBalance(quote,
                    treasury.getHotBalance(quote).add(quoteFee)
                            .setScale(4, RoundingMode.HALF_UP));
        }

        // ─── 約定済み数量を更新 ───────────────────────────────────────────────
        buy.setFilled(buy.getFilled().add(execAmount).setScale(4, RoundingMode.HALF_UP));
        sell.setFilled(sell.getFilled().add(execAmount).setScale(4, RoundingMode.HALF_UP));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  成行注文の未使用ロック返還
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 成行注文完了後、未消費のロック残高をホットに返還する。
     *
     * <ul>
     *   <li>MARKET BUY: {@code maxSpend - spentQuote} 分の quote を返還。</li>
     *   <li>MARKET SELL: 未約定の base 残量を返還（板が空で全量マッチしなかった場合）。</li>
     * </ul>
     */
    private static void refundMarketLock(
            String pairId, Pair pair, Order order, StorageData data, BigDecimal spentQuote
    ) {
        PlayerData pd = data.getPlayers().get(order.getUuid());
        if (pd == null) return;

        if (order.getSide() == OrderSide.BUY) {
            BigDecimal refund = order.getMaxSpend()
                    .subtract(spentQuote)
                    .setScale(4, RoundingMode.HALF_UP);
            if (refund.compareTo(BigDecimal.ZERO) > 0) {
                returnLock(pairId, pair.getQuote(), refund, pd);
            }
        } else {
            BigDecimal refund = order.getRemainingAmount().setScale(4, RoundingMode.HALF_UP);
            if (refund.compareTo(BigDecimal.ZERO) > 0) {
                returnLock(pairId, pair.getBase(), refund, pd);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  内部ユーティリティ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ロック残高をホットへ返還する。
     * ロック量がわずかな浮動小数点誤差で負にならないよう {@code max(0)} を適用する。
     */
    private static void returnLock(String pairId, String item, BigDecimal amount, PlayerData pd) {
        pd.setLockedBalance(pairId, item,
                pd.getLockedBalance(pairId, item)
                  .subtract(amount).max(BigDecimal.ZERO)
                  .setScale(4, RoundingMode.HALF_UP));
        pd.setHotBalance(item,
                pd.getHotBalance(item).add(amount)
                  .setScale(4, RoundingMode.HALF_UP));
    }

    /**
     * 注文履歴に追加し、上限（{@code orderHistoryMaxPerPair}）を超えた分を
     * 古い順（先頭）から削除する。
     */
    private static void addToHistory(Pair pair, Order order, PluginConfig config) {
        pair.getOrderHistory().add(order);
        trimList(pair.getOrderHistory(), config.getOrderHistoryMaxPerPair());
    }

    /** リストが {@code maxSize} を超えていたら先頭から削除する（古い順削除）。 */
    private static <T> void trimList(List<T> list, int maxSize) {
        while (list.size() > maxSize) {
            list.remove(0);
        }
    }
}
