package com.trading.saga.saga;

import com.trading.saga.expansion.CompensationAction;
import com.trading.saga.order.domain.SagaInstance;
import com.trading.saga.order.domain.SagaStatus;
import com.trading.saga.order.domain.SagaStep;
import com.trading.saga.order.domain.TradeOrder;
import com.trading.saga.order.infrastructure.SagaInstanceRepository;
import com.trading.saga.order.infrastructure.SagaStepRepository;
import com.trading.saga.order.infrastructure.TradeOrderRepository;
import com.trading.saga.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 【職責】訂單庫補償：把進行中的單標 FAILED、Saga 標 COMPENSATED。
 * 【技巧】終態冪等直接 return；合法中段才 {@code COMPENSATING → COMPENSATED}。
 * 【概念】補償不是 rollback 帳戶庫（帳戶由 TCC Cancel 自己還原）。
 * 【使用】由 {@code OrderSagaEventHandler} 在 FUNDS_FAILED／FUNDS_CANCELLED 時呼叫。
 * 【邊界】只寫訂單庫。
 *
 * <p>【怎麼運作／注入】{@code @Service} + {@code implements CompensationAction} →
 * 登錄成補償 Bean；{@link com.trading.saga.messaging.OrderSagaEventHandler} 建構子寫介面即可拿到本類
 * （與 OutboxPublisherService 當 OutboxRelay 同一套路）。
 */
@Service
public class OrderMarkFailedAction implements CompensationAction {

    private final SagaInstanceRepository sagaInstanceRepository;
    private final TradeOrderRepository orderRepository;
    private final SagaStepRepository sagaStepRepository;

    /**
     * 【職責】注入訂單庫寫入埠（建構子注入）。
     * 【使用】Spring 建構；單元測試 Mock 三個 Repository。
     */
    public OrderMarkFailedAction(SagaInstanceRepository sagaInstanceRepository,
                                 TradeOrderRepository orderRepository,
                                 SagaStepRepository sagaStepRepository) {
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.orderRepository = orderRepository;
        this.sagaStepRepository = sagaStepRepository;
    }

    /**
     * 【職責】回傳步驟名稱（寫入 saga_steps）。
     * 【使用】{@code SagaStep.of(sagaId, name(), "order FAILED")}；勿當業務分支條件。
     */
    @Override
    public String name() {
        return "ORDER_MARK_FAILED";
    }

    /**
     * 【職責】將進行中的 Saga／訂單收成補償終態。
     * 【技巧】已 COMPLETED／COMPENSATED／FAILED → 直接 return。
     * 【概念】訂單 FAILED ≠ 帳戶一定失敗；帳戶可能已 Cancel 還原。
     * 【使用】Case SAGA-002／TCC-002；前台應顯示 Saga COMPENSATED＋訂單 FAILED。
     * <pre>
     * compensationAction.compensate(sagaId);
     * // order.status = FAILED；saga.status = COMPENSATED
     * </pre>
     *
     * @param sagaId 流程 id
     */
    @Override
    @Transactional("orderTransactionManager")
    public void compensate(String sagaId) {
        SagaInstance saga = sagaInstanceRepository.findById(sagaId)
                .orElseThrow(() -> new ResourceNotFoundException("Saga not found: " + sagaId));
        if (saga.getStatus().isTerminal()) {
            return;
        }
        TradeOrder order = orderRepository.findById(saga.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + saga.getOrderId()));
        order.markFailed();
        saga.transitionTo(SagaStatus.COMPENSATING);
        saga.transitionTo(SagaStatus.COMPENSATED);
        sagaStepRepository.save(SagaStep.of(sagaId, name(), "order FAILED"));
    }
}
