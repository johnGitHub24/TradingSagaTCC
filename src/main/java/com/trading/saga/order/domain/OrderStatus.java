package com.trading.saga.order.domain;

/**
 * 【職責】訂單在訂單庫內的生命週期（與 SagaStatus 分離）。
 */
public enum OrderStatus {
    PENDING,
    FILLED,
    FAILED
}
