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
 * 【邊界】只寫訂單庫。
 */
@Service
public class OrderMarkFailedAction implements CompensationAction {

    private final SagaInstanceRepository sagaInstanceRepository;
    private final TradeOrderRepository orderRepository;
    private final SagaStepRepository sagaStepRepository;

    /**
     * 建構補償動作。
     */
    public OrderMarkFailedAction(SagaInstanceRepository sagaInstanceRepository,
                                 TradeOrderRepository orderRepository,
                                 SagaStepRepository sagaStepRepository) {
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.orderRepository = orderRepository;
        this.sagaStepRepository = sagaStepRepository;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "ORDER_MARK_FAILED";
    }

    /**
     * {@inheritDoc}
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
