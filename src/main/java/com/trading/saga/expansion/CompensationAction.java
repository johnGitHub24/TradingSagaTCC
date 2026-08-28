package com.trading.saga.expansion;

/**
 * 【職責】補償動作契約：失敗路徑要還原的一步。
 * 【概念】Saga 補償是「反向業務」，不是 DB rollback 跨庫。
 * 【邊界】每個實作只動自己的庫。
 */
public interface CompensationAction {

    /**
     * @return 步驟名稱（寫入 saga_steps）
     */
    String name();

    /**
     * 執行補償。
     *
     * @param sagaId 流程 id
     */
    void compensate(String sagaId);
}
