package com.trading.saga.order.domain;

/**
 * 【職責】訂單在訂單庫內的生命週期（與 SagaStatus 分離）。
 * 【技巧】以 {@code @Enumerated(EnumType.STRING)} 存進 {@code trade_orders.status}（存名稱，不怕調整順序）；
 * 轉換只透過 {@link TradeOrder#markFilled()}／{@link TradeOrder#markFailed()}，本 enum 不自帶規則表。
 * 【概念】訂單狀態是「給使用者看的結果」，只有三個值；流程細節看 {@link SagaStatus}。
 * 判斷一筆交易結束要同時看兩者：成功＝Saga COMPLETED＋訂單 FILLED；失敗＝Saga COMPENSATED＋訂單 FAILED。
 * 完整對照見 {@code docs/狀態對照-Saga-TCC-訂單.md}。
 * 【邊界】合法路徑只有 PENDING → FILLED 或 PENDING → FAILED；「終態不可再改」由呼叫端先看 Saga
 * {@code isTerminal()} 保證，本 enum 不檢查。
 */
public enum OrderStatus {

    /** 下單當下由 {@code TradeOrder.pending} 設定；Saga 未到終態前都停在這裡。 */
    PENDING,

    /** 成交：{@code OrderSagaEventHandler.onConfirmed} 收到 FUNDS_CONFIRMED 時設定。 */
    FILLED,

    /** 失敗已收尾：{@code OrderMarkFailedAction.compensate} 設定；帳戶錢由 TCC 另外處理，不代表系統出錯。 */
    FAILED
}
