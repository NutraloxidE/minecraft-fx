package com.gekiyabafx.model;

import java.math.BigDecimal;

/**
 * 1件の注文レコード。
 *
 * <p>オーダーブック（{@code order_book.bids} / {@code order_book.asks}）および
 * 注文履歴（{@code order_history}）の両方で使用する。
 * ステータスによってどちらの配列に属するかが決まる：
 * <ul>
 *   <li>{@link OrderStatus#OPEN} / {@link OrderStatus#PARTIALLY_FILLED} → {@code order_book}</li>
 *   <li>{@link OrderStatus#FILLED} / {@link OrderStatus#CANCELLED} → {@code order_history}</li>
 * </ul>
 * </p>
 *
 * <p>数値は全て {@code BigDecimal}（scale=4, HALF_UP）。
 * GSON シリアライズ時は BigDecimalTypeAdapter により文字列として出力される。</p>
 */
public final class Order {

    /**
     * 注文ID（UUID v4 文字列）。
     * 発注時に {@code java.util.UUID.randomUUID().toString()} で生成する。
     */
    private String orderId;

    /**
     * 発注プレイヤーの UUID 文字列（ハイフン区切り）。
     * プレイヤー側APIでは Authorization ヘッダーのトークンから取得するが、
     * storage.json 上では注文レコード内にも保持して履歴の追跡を可能にする。
     */
    private String uuid;

    /** 注文種別（LIMIT / MARKET）。 */
    private OrderType type;

    /** 売買方向（BUY / SELL）。 */
    private OrderSide side;

    /**
     * 指値価格。
     * {@link OrderType#MARKET} の場合は {@code null}。
     * JSON上は文字列（例: {@code "4.2000"}）または {@code null}。
     */
    private BigDecimal price;

    /**
     * 注文数量（base アイテムの量）。
     * {@link OrderType#MARKET} かつ {@link OrderSide#BUY} の場合は
     * amount の代わりに {@code maxSpend} を使用するが、
     * このフィールドはマッチング処理中に約定済み数量の追跡に使用する。
     * JSON上は文字列（例: {@code "0.5000"}）。
     */
    private BigDecimal amount;

    /**
     * 成行 BUY 注文時の最大支払い quote 量。
     * {@link OrderType#MARKET} かつ {@link OrderSide#BUY} の場合のみ有効。
     * それ以外は {@code null}。
     * JSON上は文字列または {@code null}。
     */
    private BigDecimal maxSpend;

    /**
     * 約定済み数量（累計）。
     * 初期値は {@code "0.0000"}。マッチングのたびに加算される。
     * JSON上は文字列（例: {@code "0.0000"}）。
     */
    private BigDecimal filled;

    /** 注文ステータス。 */
    private OrderStatus status;

    /**
     * 発注日時のUnixタイムスタンプ（秒）。
     * GSON シリアライズ時のフィールド名: {@code created_at}
     */
    private long createdAt;

    /**
     * 決済（FILLED / CANCELLED）日時のUnixタイムスタンプ（秒）。
     * OPEN / PARTIALLY_FILLED 状態では {@code 0}（JSON上は省略可だが0として保持）。
     * GSON シリアライズ時のフィールド名: {@code closed_at}
     */
    private long closedAt;

    /** 注文時に紐付いた ATM ID（ATM 未使用なら null）。 */
    private String atmId;

    /** 注文時に紐付いた ATM grade（ATM 未使用なら null）。 */
    private String atmGrade;

    /** GSON デシリアライズ用のデフォルトコンストラクタ。 */
    public Order() {}

    /**
     * 新規注文作成用コンストラクタ（指値）。
     *
     * @param orderId   注文ID（UUID v4）
     * @param uuid      発注プレイヤーのUUID
     * @param type      注文種別
     * @param side      売買方向
     * @param price     指値価格（MARKET の場合は {@code null}）
     * @param amount    注文数量
     * @param maxSpend  成行BUY時の最大支払い（それ以外は {@code null}）
     * @param createdAt 発注Unixタイムスタンプ（秒）
     */
    public Order(
            String orderId,
            String uuid,
            OrderType type,
            OrderSide side,
            BigDecimal price,
            BigDecimal amount,
            BigDecimal maxSpend,
            long createdAt
    ) {
        this.orderId   = orderId;
        this.uuid      = uuid;
        this.type      = type;
        this.side      = side;
        this.price     = price;
        this.amount    = amount;
        this.maxSpend  = maxSpend;
        this.filled    = BigDecimal.ZERO.setScale(4, java.math.RoundingMode.HALF_UP);
        this.status    = OrderStatus.OPEN;
        this.createdAt = createdAt;
        this.closedAt  = 0L;
    }

    // ─── ゲッター ──────────────────────────────────────────────────────────────

    public String getOrderId()        { return orderId; }
    public String getUuid()           { return uuid; }
    public OrderType getType()        { return type; }
    public OrderSide getSide()        { return side; }
    public BigDecimal getPrice()      { return price; }
    public BigDecimal getAmount()     { return amount; }
    public BigDecimal getMaxSpend()   { return maxSpend; }
    public BigDecimal getFilled()     { return filled; }
    public OrderStatus getStatus()    { return status; }
    public long getCreatedAt()        { return createdAt; }
    public long getClosedAt()         { return closedAt; }
    public String getAtmId()          { return atmId; }
    public String getAtmGrade()       { return atmGrade; }

    // ─── セッター ──────────────────────────────────────────────────────────────

    public void setOrderId(String orderId)        { this.orderId = orderId; }
    public void setUuid(String uuid)              { this.uuid = uuid; }
    public void setType(OrderType type)           { this.type = type; }
    public void setSide(OrderSide side)           { this.side = side; }
    public void setPrice(BigDecimal price)        { this.price = price; }
    public void setAmount(BigDecimal amount)      { this.amount = amount; }
    public void setMaxSpend(BigDecimal maxSpend)  { this.maxSpend = maxSpend; }
    public void setFilled(BigDecimal filled)      { this.filled = filled; }
    public void setStatus(OrderStatus status)     { this.status = status; }
    public void setCreatedAt(long createdAt)      { this.createdAt = createdAt; }
    public void setClosedAt(long closedAt)        { this.closedAt = closedAt; }
    public void setAtmId(String atmId)            { this.atmId = atmId; }
    public void setAtmGrade(String atmGrade)      { this.atmGrade = atmGrade; }

    // ─── ユーティリティ ────────────────────────────────────────────────────────

    /**
     * まだ約定していない残り数量を返す。
     * {@code amount - filled} を計算する。
     *
     * @return 未約定残量（scale=4, HALF_UP）
     */
    public BigDecimal getRemainingAmount() {
        return amount.subtract(filled).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public String toString() {
        return "Order{orderId='" + orderId + '\''
                + ", uuid='" + uuid + '\''
                + ", type=" + type
                + ", side=" + side
                + ", price=" + price
                + ", amount=" + amount
                + ", filled=" + filled
                + ", status=" + status
                + ", createdAt=" + createdAt
                + '}';
    }
}
