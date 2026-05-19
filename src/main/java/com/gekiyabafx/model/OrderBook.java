package com.gekiyabafx.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 1つの取引ペアにおけるオーダーブック（板）。
 *
 * <p>{@code storage.json} の {@code pairs.<ID>.order_book} に対応する。
 * {@code bids}（買い板）と {@code asks}（売り板）をそれぞれ {@link List} で保持する。</p>
 *
 * <p>並び順の管理責務はマッチングエンジン（Step 13）側が担う。
 * このクラスは純粋なデータ保持コンテナ。</p>
 */
public final class OrderBook {

    /**
     * 買い注文のリスト。
     * マッチングエンジンが価格降順（最高値優先）・同価格内は時刻昇順で管理する。
     */
    private List<Order> bids;

    /**
     * 売り注文のリスト。
     * マッチングエンジンが価格昇順（最安値優先）・同価格内は時刻昇順で管理する。
     */
    private List<Order> asks;

    /** GSON デシリアライズ用のデフォルトコンストラクタ。空リストで初期化する。 */
    public OrderBook() {
        this.bids = new ArrayList<>();
        this.asks = new ArrayList<>();
    }

    public List<Order> getBids() { return bids; }
    public List<Order> getAsks() { return asks; }

    public void setBids(List<Order> bids) { this.bids = bids; }
    public void setAsks(List<Order> asks) { this.asks = asks; }

    @Override
    public String toString() {
        return "OrderBook{bids=" + bids.size() + " orders, asks=" + asks.size() + " orders}";
    }
}
