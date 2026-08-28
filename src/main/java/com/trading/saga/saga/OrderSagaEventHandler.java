package com.trading.saga.messaging;

import com.trading.saga.expansion.CompensationAction;
import com.trading.saga.expansion.DomainEventConsumer;
import com.trading.saga.order.domain.OrderStatus;
import com.trading.saga.order.domain.SagaInstance;
import com.trading.saga.order.domain.SagaStatus;
import com.trading.saga.order.domain.SagaStep;
import com.trading.saga.order.domain.TradeOrder;
import com.trading.saga.order.infrastructure.SagaInstanceRepository;
import com.trading.saga.order.infrastructure.SagaStepRepository;
import com.trading.saga.order.infrastructure.TradeOrderRepository;
import com.trading.saga.common.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 【職責】訂單側消費帳戶事件：Confirm 命令、完成訂單、或觸發補償。
 * 【技巧】終態直接略過，避免 Kafka 重送把已完成 Saga 再轉一次。
 * 【概念】編排者只根據事件推進自己的庫。
 */
@Service
public class OrderSagaEventHandler implements DomainEventConsumer {

    public static final String STEP_CONFIRM_COMMANDED = "CONFIRM_COMMANDED";
    public static final String STEP_COMPLETED = "SAGA_COMPLETED";

    private final SagaInstanceRepository sagaInstanceRepository;
    private final TradeOrderRepository orderRepository;
    private final SagaStepRepository sagaStepRepository;
    private final OutboxPort outboxPort;
    private final CompensationAction compensationAction;
    private final String commandTopic;

    /**
     * 建構事件處理。
     */
    public OrderSagaEventHandler(SagaInstanceRepository sagaInstanceRepository,
                                 TradeOrderRepository orderRepository,
                                 SagaStepRepository sagaStepRepository,
                                 OutboxPort outboxPort,
                                 CompensationAction compensationAction,
                                 @Value("${trading.kafka.command-topic}") String commandTopic) {
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.orderRepository = orderRepository;
        this.sagaStepRepository = sagaStepRepository;
        this.outboxPort = outboxPort;
        this.compensationAction = compensationAction;
        this.commandTopic = commandTopic;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional("orderTransactionManager")
    public void onMessage(SagaMessage message) {
        String type = message.type();
        if (SagaMessageTypes.FUNDS_RESERVED.equals(type)) {
            onReserved(message);
        } else if (SagaMessageTypes.FUNDS_CONFIRMED.equals(type)) {
            onConfirmed(message);
        } else if (SagaMessageTypes.FUNDS_FAILED.equals(type)
                || SagaMessageTypes.FUNDS_CANCELLED.equals(type)) {
            compensationAction.compensate(message.sagaId());
        }
    }

    private void onReserved(SagaMessage message) {
        SagaInstance saga = requireSaga(message.sagaId());
        if (saga.getStatus().isTerminal() || saga.getStatus() == SagaStatus.ACCOUNT_CONFIRMING) {
            return;
        }
        if (saga.getStatus() == SagaStatus.STARTED) {
            saga.transitionTo(SagaStatus.ACCOUNT_TRYING);
        }
        saga.transitionTo(SagaStatus.ACCOUNT_CONFIRMING);
        TradeOrder order = requireOrder(saga.getOrderId());
        SagaMessage confirm = SagaMessage.of(
                saga.getSagaId(), order.getOrderId(), order.getAccountId(),
                SagaMessageTypes.CONFIRM_FUNDS, order.getAmount(), order.getSymbol(), order.isForceFail());
        outboxPort.append(commandTopic, saga.getSagaId(), confirm);
        sagaStepRepository.save(SagaStep.of(saga.getSagaId(), STEP_CONFIRM_COMMANDED, SagaMessageTypes.CONFIRM_FUNDS));
    }

    private void onConfirmed(SagaMessage message) {
        SagaInstance saga = requireSaga(message.sagaId());
        if (saga.getStatus().isTerminal()) {
            return;
        }
        TradeOrder order = requireOrder(saga.getOrderId());
        order.markFilled();
        saga.transitionTo(SagaStatus.COMPLETED);
        sagaStepRepository.save(SagaStep.of(saga.getSagaId(), STEP_COMPLETED, OrderStatus.FILLED.name()));
    }

    private SagaInstance requireSaga(String sagaId) {
        return sagaInstanceRepository.findById(sagaId)
                .orElseThrow(() -> new ResourceNotFoundException("Saga not found: " + sagaId));
    }

    private TradeOrder requireOrder(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
    }
}
