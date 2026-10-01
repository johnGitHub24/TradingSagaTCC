package com.trading.saga.order.infrastructure;

import com.trading.saga.order.domain.SagaStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【職責】訂單庫 saga_steps（Saga 步驟軌跡）存取。只做查詢／寫入，不含商業規則。
 * 【技巧】繼承 {@link JpaRepository} 由 Spring Data 自動實作；自訂查詢以方法命名推導，不寫 JPQL。
 * 【概念】綁定 orderTransactionManager（見 {@code OrderDataSourceConfig}），步驟與 Saga 狀態變更在同一 TX 內寫入。
 * 【使用】寫入（{@code save}）：{@code SagaOrchestrator}、{@code OrderSagaEventHandler}、{@code OrderMarkFailedAction}；
 * 讀取：{@code TradeQueryService.getSaga}。
 */
public interface SagaStepRepository extends JpaRepository<SagaStep, Long> {

    /**
     * 【職責】取出某筆 Saga 的完整步驟時間軸。
     * 【技巧】Spring Data 命名推導：{@code findBySagaId}＝WHERE saga_id = ?；
     * {@code OrderByAtAscIdAsc}＝先依時間、再依自增 id 升冪——同一毫秒寫入的多步（如 ORDER_CREATED 與 RESERVE_COMMANDED）
     * 也能維持實際寫入順序。
     * 【使用】僅 {@code TradeQueryService.getSaga}，結果交給 {@code SagaResponse.from} 組成前台時間軸。
     *
     * @param sagaId 流程 id
     * @return 該 Saga 的步驟，舊到新；查無時為空列表
     */
    List<SagaStep> findBySagaIdOrderByAtAscIdAsc(String sagaId);
}
