package com.gekiyabafx.model;

/**
 * 注文のステータス。
 */
public enum OrderStatus {
    /** 未約定（板に乗っている状態） */
    OPEN,
    /** 部分約定（残量あり） */
    PARTIALLY_FILLED,
    /** 全量約定済み */
    FILLED,
    /** キャンセル済み */
    CANCELLED
}
