package com.gekiyabafx.model;

/**
 * 注文の種別。
 */
public enum OrderType {
    /** 指値注文 — price を指定して板に乗せる */
    LIMIT,
    /** 成行注文 — 即時約定を目的とし、板の先頭から消費する */
    MARKET
}
