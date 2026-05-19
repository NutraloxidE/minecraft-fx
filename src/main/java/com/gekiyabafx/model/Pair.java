package com.gekiyabafx.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 1つの取引ペアの全データを保持するモデル。
 *
 * <p>{@code storage.json} の {@code pairs.<ID>} オブジェクトに対応する。
 * ペアID（例: {@code "DIAMOND/EMERALD"}）は {@code StorageData.pairs} マップのキーとして管理され、
 * このクラス自体は ID フィールドを持たない。</p>
 *
 * <p>数値フィールドはすべて {@code BigDecimal}（scale=4, HALF_UP）。
 * {@code lastPrice} が {@code null} の場合は約定実績なし。</p>
 */
public final class Pair {

    /**
     * Base アイテムの Minecraft Material 名（小文字スネークケース）。
     * 例: {@code "diamond"}
     */
    private String base;

    /**
     * Quote アイテムの Minecraft Material 名（小文字スネークケース）。
     * 例: {@code "emerald"}
     */
    private String quote;

    /**
     * ペアの有効/無効フラグ。
     * {@code false} の場合、新規注文を受け付けない。
     * 既存の未約定注文はキャンセル扱いで返金される。
     */
    private boolean enabled;

    /**
     * このペアの最小注文数量。
     * JSON上は文字列（例: {@code "0.0001"}）。
     */
    private BigDecimal minAmount;

    /**
     * このペアの最小注文価格。
     * JSON上は文字列（例: {@code "0.0001"}）。
     */
    private BigDecimal minPrice;

    /**
     * 現在のオーダーブック（板）。
     * GSON シリアライズ時のフィールド名: {@code order_book}
     */
    private OrderBook orderBook;

    /**
     * 注文履歴（FILLED / CANCELLED になった注文）。
     * 最大保持件数は {@code config.yml} の {@code order-history-max-per-pair} に従う。
     * GSON シリアライズ時のフィールド名: {@code order_history}
     */
    private List<Order> orderHistory;

    /**
     * 生の約定履歴。ローソク足集計はフロントエンドが行う。
     * 最大保持件数は {@code config.yml} の {@code executions-max-per-pair} に従う。
     */
    private List<Execution> executions;

    /**
     * 直近の約定価格。約定実績がない場合は {@code null}。
     * JSON上は文字列（例: {@code "4.3000"}）または {@code null}。
     * GSON シリアライズ時のフィールド名: {@code last_price}
     */
    private BigDecimal lastPrice;

    /** GSON デシリアライズ用のデフォルトコンストラクタ。コレクションを空で初期化する。 */
    public Pair() {
        this.orderBook    = new OrderBook();
        this.orderHistory = new ArrayList<>();
        this.executions   = new ArrayList<>();
    }

    /**
     * 新規ペア作成用コンストラクタ。
     *
     * @param base      Base アイテム名
     * @param quote     Quote アイテム名
     * @param enabled   有効フラグ（管理者APIで追加する際は {@code false} から始める仕様）
     * @param minAmount 最小注文数量
     * @param minPrice  最小注文価格
     */
    public Pair(String base, String quote, boolean enabled, BigDecimal minAmount, BigDecimal minPrice) {
        this.base         = base;
        this.quote        = quote;
        this.enabled      = enabled;
        this.minAmount    = minAmount;
        this.minPrice     = minPrice;
        this.orderBook    = new OrderBook();
        this.orderHistory = new ArrayList<>();
        this.executions   = new ArrayList<>();
        this.lastPrice    = null;
    }

    // ─── ゲッター ──────────────────────────────────────────────────────────────

    public String getBase()               { return base; }
    public String getQuote()              { return quote; }
    public boolean isEnabled()            { return enabled; }
    public BigDecimal getMinAmount()      { return minAmount; }
    public BigDecimal getMinPrice()       { return minPrice; }
    public OrderBook getOrderBook()       { return orderBook; }
    public List<Order> getOrderHistory()  { return orderHistory; }
    public List<Execution> getExecutions(){ return executions; }
    public BigDecimal getLastPrice()      { return lastPrice; }

    // ─── セッター ──────────────────────────────────────────────────────────────

    public void setBase(String base)                        { this.base = base; }
    public void setQuote(String quote)                      { this.quote = quote; }
    public void setEnabled(boolean enabled)                 { this.enabled = enabled; }
    public void setMinAmount(BigDecimal minAmount)          { this.minAmount = minAmount; }
    public void setMinPrice(BigDecimal minPrice)            { this.minPrice = minPrice; }
    public void setOrderBook(OrderBook orderBook)           { this.orderBook = orderBook; }
    public void setOrderHistory(List<Order> orderHistory)   { this.orderHistory = orderHistory; }
    public void setExecutions(List<Execution> executions)   { this.executions = executions; }
    public void setLastPrice(BigDecimal lastPrice)          { this.lastPrice = lastPrice; }

    @Override
    public String toString() {
        return "Pair{base='" + base + '\''
                + ", quote='" + quote + '\''
                + ", enabled=" + enabled
                + ", lastPrice=" + lastPrice
                + '}';
    }
}
