package com.trading.saga.order.infrastructure;

import com.trading.saga.order.domain.SagaInstance;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 【職責】訂單庫 saga_instances 存取。只做查詢／寫入，不含商業規則（狀態機在 {@link SagaInstance#transitionTo}）。
 * 【技巧】繼承 {@link JpaRepository}（主鍵型別 String＝sagaId），沒有自訂方法：內建 {@code save}／{@code findById} 就夠用。
 * 【概念】綁定 orderTransactionManager（見 {@code OrderDataSourceConfig}）；Saga 進度只存在訂單庫，帳戶側不寫這張表。
 * 收到 Kafka 事件時以 {@code findById(message.sagaId())} 找回 Saga，再推進狀態，由 JPA dirty checking 在 commit 時寫回。
 * 【使用】{@code SagaOrchestrator.start}（save）、{@code OrderSagaEventHandler}／{@code OrderMarkFailedAction}（findById 推進）、
 * {@code TradeQueryService.getSaga}（查詢）。
 */
public interface SagaInstanceRepository extends JpaRepository<SagaInstance, String> {
}
