package com.trading.saga.expansion;

import java.math.BigDecimal;

/**
 * 【職責】TCC 資源契約：新增參與者（庫存等）時實作此介面，不必改雙庫邊界。
 * 【技巧】Try／Confirm／Cancel 皆以 sagaId 當冪等 Key（預留票主鍵）。
 * 【概念】TCC 管「單資源兩階段」；Saga 管「多資源先後與補償」。
 * 【概念·預留票】Try 成功會留下一張以 sagaId 為 PK 的預留票，供後續 Confirm／Cancel 對帳與冪等。
 * 【使用】由 {@code AccountCommandHandler} 依 Kafka command 呼叫；業務程式勿直接從 Controller 呼叫。
 * 【邊界】實作只能碰自己的庫。
 *
 * <p>【怎麼運作／注入】角色＝本介面；演員＝{@link com.trading.saga.account.AccountTccService}
 * （{@code @Service} + {@code implements TccResource}）。
 * {@link com.trading.saga.messaging.AccountCommandHandler} 建構子只要 {@code TccResource}，Spring 自動注入。
 */
public interface TccResource {

    /**
     * 【職責】Try：檢查並預留資金（available → frozen）。
     * 【技巧】以 {@code sagaId} 查預留表做冪等；已存在 TRYING／CONFIRMED 則不再扣款。
     * 【概念】Try 成功≠扣款完成；真正扣款在 Confirm。
     * 【使用】僅在收到 {@code RESERVE_FUNDS} command 時呼叫。
     * <pre>
     * boolean ok = tcc.tryReserve(sagaId, "ACC-001", amount);
     * // ok=true  → 發 FUNDS_RESERVED
     * // ok=false → 發 FUNDS_FAILED（例如餘額不足），Saga 走補償
     * </pre>
     *
     * @param sagaId    冪等 Key／預留主鍵（一筆 Saga 一列）
     * @param accountId 帳戶 id
     * @param amount    預留金額（正數）
     * @return true 預留成功；false 應走補償（例如餘額不足）
     */
    boolean tryReserve(String sagaId, String accountId, BigDecimal amount);

    /**
     * 【職責】Confirm：消耗預留（凍結消失＝真正扣款）。
     * 【技巧】{@code forceFail=true} 時本教學版改呼叫 Cancel，用來練補償。
     * 【概念】冪等：已 CONFIRMED 再 Confirm 直接回 true。
     * 【使用】僅在收到 {@code CONFIRM_FUNDS} command 時呼叫。
     * <pre>
     * boolean ok = tcc.confirm(sagaId, false); // Happy Path → FUNDS_CONFIRMED
     * boolean ok = tcc.confirm(sagaId, true);  // 教學失敗 → Cancel → FUNDS_CANCELLED
     * </pre>
     *
     * @param sagaId    與 Try 相同的冪等 Key
     * @param forceFail true 時改走 Cancel（教學用）
     * @return true 確認成功；false 已改 Cancel／失敗
     */
    boolean confirm(String sagaId, boolean forceFail);

    /**
     * 【職責】Cancel：釋放預留（frozen → available）。
     * 【技巧】無預留列或已 CANCELLED → 直接 return（冪等）。
     * 【概念】補償時帳戶靠 Cancel 還原；訂單庫的 FAILED 由 CompensationAction 另寫。
     * 【使用】forceFail Confirm 內部會呼叫；亦可由 {@code CANCEL_FUNDS} command 觸發。
     * <pre>
     * tcc.cancel(sagaId); // 可重入，不會二次加回 available
     * </pre>
     *
     * @param sagaId 與 Try 相同的冪等 Key
     */
    void cancel(String sagaId);
}
