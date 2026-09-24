package com.trading.saga.expansion;

/**
 * 【職責】補償動作契約：失敗路徑要還原的一步。
 * 【概念】Saga 補償是「反向業務」，不是 DB rollback 跨庫。
 * 【使用】本專案實作 {@code OrderMarkFailedAction}；由事件處理器在失敗 event 時呼叫。
 * 【邊界】每個實作只動自己的庫。
 *
 * <p>【怎麼運作／注入】角色＝本介面；演員＝{@link com.trading.saga.saga.OrderMarkFailedAction}
 * （{@code @Service} + {@code implements CompensationAction}）。
 * {@link com.trading.saga.messaging.OrderSagaEventHandler} 建構子只要 {@code CompensationAction}，
 * Spring 找唯一實作注入（與 OutboxRelay → Publisher 相同）。
 */
public interface CompensationAction {

    /**
     * 【職責】步驟顯示名稱（寫入 saga_steps.name）。
     * 【使用】日誌／Demo 時間軸；勿當 switch 分支的唯一依據。
     *
     * @return 例如 {@code ORDER_MARK_FAILED}
     */
    String name();

    /**
     * 【職責】執行補償（冪等：已終態應直接 return）。
     * 【使用】
     * <pre>
     * compensationAction.compensate(message.sagaId());
     * </pre>
     *
     * @param sagaId 流程 id
     */
    void compensate(String sagaId);
}
