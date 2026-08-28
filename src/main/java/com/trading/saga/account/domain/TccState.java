package com.trading.saga.account.domain;

/**
 * 【職責】單筆 TCC 預留的兩階段狀態。
 * 【概念】TRYING＝已凍結未確認；CONFIRMED＝已扣款；CANCELLED＝已補償。
 */
public enum TccState {
    TRYING,
    CONFIRMED,
    CANCELLED
}
