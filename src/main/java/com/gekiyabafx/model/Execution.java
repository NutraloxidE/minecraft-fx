package com.gekiyabafx.model;

import java.math.BigDecimal;

/**
 * 1件の約定レコード。
 *
 * <p>{@code storage.json} の {@code pairs.<ID>.executions} 配列の各要素に対応する。
 * ローソク足集計はフロントエンドの責務であり、バックエンドはこの生データを保持・返却するだけ。</p>
 *
 * <p>数値は全て {@code BigDecimal}（scale=4, HALF_UP）。</p>
 */
public final class Execution {

    /** 約定の一意ID（後方互換のため旧データでは null を許容）。 */
    private String executionId;

    /** 買い手 UUID（後方互換のため旧データでは null を許容）。 */
    private String buyerUuid;

    /** 売り手 UUID（後方互換のため旧データでは null を許容）。 */
    private String sellerUuid;

    /** 買い注文 ID（後方互換のため旧データでは null を許容）。 */
    private String buyOrderId;

    /** 売り注文 ID（後方互換のため旧データでは null を許容）。 */
    private String sellOrderId;

    /**
     * 約定が発生したUnixタイムスタンプ（秒）。
     * GSON シリアライズ時のフィールド名: {@code timestamp}
     */
    private long timestamp;

    /**
     * 約定価格。
     * GSON シリアライズ時のフィールド名: {@code price}
     * JSON上は文字列として出力される（例: {@code "4.3000"}）。
     */
    private BigDecimal price;

    /**
     * 約定数量。
     * GSON シリアライズ時のフィールド名: {@code amount}
     * JSON上は文字列として出力される（例: {@code "0.5000"}）。
     */
    private BigDecimal amount;

    /** ATM経由で発生した約定の場合のATM ID。 */
    private String atmId;

    /** ATM経由で発生した約定の場合のATMグレード。 */
    private String atmGrade;

    /** GSON デシリアライズ用のデフォルトコンストラクタ。 */
    public Execution() {}

    /**
     * 全フィールドを指定するコンストラクタ。
     *
     * @param timestamp 約定Unixタイムスタンプ（秒）
     * @param price     約定価格（{@code null} 不可）
     * @param amount    約定数量（{@code null} 不可）
     */
    public Execution(long timestamp, BigDecimal price, BigDecimal amount) {
        this.timestamp = timestamp;
        this.price     = price;
        this.amount    = amount;
    }

    public Execution(long timestamp, BigDecimal price, BigDecimal amount, String atmId, String atmGrade) {
        this(timestamp, price, amount);
        this.atmId = atmId;
        this.atmGrade = atmGrade;
    }

    public Execution(
            long timestamp,
            BigDecimal price,
            BigDecimal amount,
            String atmId,
            String atmGrade,
            String executionId,
            String buyerUuid,
            String sellerUuid,
            String buyOrderId,
            String sellOrderId
    ) {
        this.timestamp = timestamp;
        this.price = price;
        this.amount = amount;
        this.atmId = atmId;
        this.atmGrade = atmGrade;
        this.executionId = executionId;
        this.buyerUuid = buyerUuid;
        this.sellerUuid = sellerUuid;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
    }

    public long getTimestamp()       { return timestamp; }
    public BigDecimal getPrice()     { return price; }
    public BigDecimal getAmount()    { return amount; }
    public String getAtmId()         { return atmId; }
    public String getAtmGrade()      { return atmGrade; }
    public String getExecutionId()   { return executionId; }
    public String getBuyerUuid()     { return buyerUuid; }
    public String getSellerUuid()    { return sellerUuid; }
    public String getBuyOrderId()    { return buyOrderId; }
    public String getSellOrderId()   { return sellOrderId; }

    public void setTimestamp(long timestamp)      { this.timestamp = timestamp; }
    public void setPrice(BigDecimal price)        { this.price = price; }
    public void setAmount(BigDecimal amount)      { this.amount = amount; }
    public void setAtmId(String atmId)            { this.atmId = atmId; }
    public void setAtmGrade(String atmGrade)      { this.atmGrade = atmGrade; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
    public void setBuyerUuid(String buyerUuid)     { this.buyerUuid = buyerUuid; }
    public void setSellerUuid(String sellerUuid)   { this.sellerUuid = sellerUuid; }
    public void setBuyOrderId(String buyOrderId)   { this.buyOrderId = buyOrderId; }
    public void setSellOrderId(String sellOrderId) { this.sellOrderId = sellOrderId; }

    @Override
    public String toString() {
        return "Execution{timestamp=" + timestamp
            + ", price=" + price
            + ", amount=" + amount
            + ", executionId='" + executionId + '\''
            + ", buyerUuid='" + buyerUuid + '\''
            + ", sellerUuid='" + sellerUuid + '\''
            + ", buyOrderId='" + buyOrderId + '\''
            + ", sellOrderId='" + sellOrderId + '\''
            + ", atmId='" + atmId + '\''
            + ", atmGrade='" + atmGrade + '\''
            + '}';
    }
}
