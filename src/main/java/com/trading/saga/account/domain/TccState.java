package com.trading.saga.account.domain;

/**
 * 【職責】單筆 TCC 預留票（{@code tcc_reservations}）的資金狀態。
 * 【概念】TRYING＝已凍結未確認；CONFIRMED＝已扣款；CANCELLED＝已退回。
 * 只記「錢的狀況」，不記流程；「現在在等誰」是 Saga 的事（{@code SagaStatus}，在訂單庫）。
 *
 * <p>【狀態圖】
 * <pre>
 * （無票）──Try 成功──► TRYING ──Confirm──► CONFIRMED
 *                          └──Cancel───► CANCELLED
 * Try 失敗（餘額不足）：不寫票，停在「無票」
 * </pre>
 *
 * <p>【與 Saga 的對應】預留票先變，帳戶再發 Kafka 事件，訂單側收到後才更新 Saga：
 * <pre>
 * 預留票 TRYING     → 發 FUNDS_RESERVED  → Saga ACCOUNT_CONFIRMING
 * 預留票 CONFIRMED  → 發 FUNDS_CONFIRMED → Saga COMPLETED＋訂單 FILLED
 * 預留票 CANCELLED  → 發 FUNDS_CANCELLED → Saga COMPENSATED＋訂單 FAILED
 * （無票）Try 失敗  → 發 FUNDS_FAILED    → Saga COMPENSATED＋訂單 FAILED
 * </pre>
 * CANCELLED（單筆錢已退）≠ Saga COMPENSATED（整個流程失敗已收尾）。
 * 完整對照見 {@code docs/狀態對照-Saga-TCC-訂單.md}。
 */
public enum TccState {

    /** 已凍結（available → frozen），等待 Confirm 或 Cancel。由 {@code AccountTccService.tryReserve} 寫入。 */
    TRYING,

    /** 已扣款（frozen 消失，total 下降）。終態；之後不能再 Cancel。 */
    CONFIRMED,

    /** 已退回（frozen → available）。終態；再 Cancel 為空操作（冪等）。 */
    CANCELLED
}
