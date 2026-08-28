package com.trading.saga.order.domain;

/**
 * 【職責】編排式 Saga 生命週期；終態不可再轉。
 * 【技巧】{@link #canTransitionTo(SagaStatus)} 集中合法邊，避免各處 if 散落。
 * 【概念】Saga 記「流程走到哪」；帳戶 TCC 記「錢凍在哪」。兩者不同庫。
 */
public enum SagaStatus {
    STARTED,
    ACCOUNT_TRYING,
    ACCOUNT_CONFIRMING,
    COMPLETED,
    COMPENSATING,
    COMPENSATED,
    FAILED;

    /**
     * @param next 目標狀態
     * @return 是否允許這一跳
     */
    public boolean canTransitionTo(SagaStatus next) {
        return switch (this) {
            case STARTED -> next == ACCOUNT_TRYING || next == COMPENSATING;
            case ACCOUNT_TRYING -> next == ACCOUNT_CONFIRMING || next == COMPENSATING;
            case ACCOUNT_CONFIRMING -> next == COMPLETED || next == COMPENSATING;
            case COMPENSATING -> next == COMPENSATED || next == FAILED;
            case COMPLETED, COMPENSATED, FAILED -> false;
        };
    }

    /**
     * @return 前台可停止輪詢的終態
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == COMPENSATED || this == FAILED;
    }
}
