package com.trading.saga.messaging;

import com.trading.saga.expansion.DomainEventConsumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 【職責】Kafka listener：command → 帳戶 TCC；event → 訂單編排。
 * 【技巧】兩個 {@link DomainEventConsumer} 用 {@code @Qualifier} 分開，預留拆進程。
 */
@Component
public class SagaKafkaListeners {

    private final DomainEventConsumer accountCommandHandler;
    private final DomainEventConsumer orderSagaEventHandler;

    /**
     * @param accountCommandHandler 帳戶參與者
     * @param orderSagaEventHandler 訂單編排
     */
    public SagaKafkaListeners(@Qualifier("accountCommandHandler") DomainEventConsumer accountCommandHandler,
                              @Qualifier("orderSagaEventHandler") DomainEventConsumer orderSagaEventHandler) {
        this.accountCommandHandler = accountCommandHandler;
        this.orderSagaEventHandler = orderSagaEventHandler;
    }

    /**
     * 消費 TCC 命令。
     *
     * @param message 信封
     */
    @KafkaListener(topics = "${trading.kafka.command-topic}", groupId = "saga-account-tcc")
    public void onCommand(SagaMessage message) {
        accountCommandHandler.onMessage(message);
    }

    /**
     * 消費 TCC 結果事件。
     *
     * @param message 信封
     */
    @KafkaListener(topics = "${trading.kafka.event-topic}", groupId = "saga-order-orchestrator")
    public void onEvent(SagaMessage message) {
        orderSagaEventHandler.onMessage(message);
    }
}
