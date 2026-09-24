package com.trading.saga.messaging;

import com.trading.saga.expansion.TccResource;
import com.trading.saga.expansion.DomainEventConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 【職責】帳戶側消費 command：TCC Try／Confirm／Cancel，再發 event。
 * 【技巧】command 成功與否一律轉成 event 告訴編排者；本類不寫訂單庫。
 * 【概念】TCC 參與者角色；與 {@link OrderSagaEventHandler} 成對。
 * 【使用】由 {@link SagaKafkaListeners#onCommand} 轉送；勿手動 new 後呼叫（需 Spring 注入的 TCC／Kafka）。
 */
@Service
public class AccountCommandHandler implements DomainEventConsumer {

    private final TccResource tccResource;
    private final KafkaMessageSender kafkaMessageSender;
    private final String eventTopic;

    /**
     * 【職責】綁定 TCC 與 event topic。
     *
     * @param eventTopic {@code trading.kafka.event-topic}
     */
    public AccountCommandHandler(TccResource tccResource,
                                 KafkaMessageSender kafkaMessageSender,
                                 @Value("${trading.kafka.event-topic}") String eventTopic) {
        this.tccResource = tccResource;
        this.kafkaMessageSender = kafkaMessageSender;
        this.eventTopic = eventTopic;
    }

    /**
     * 【職責】依 message.type 分派 TCC，並對 event topic 回覆結果。
     * 【技巧】RESERVE→RESERVED／FAILED；CONFIRM→CONFIRMED／CANCELLED／FAILED；CANCEL→CANCELLED。
     * 【概念】編排者只聽 event，不直接看帳戶表。
     * 【使用】Kafka listener 自動呼叫；整合測試經由真實／內嵌 broker 觸發。
     * <pre>
     * // type=RESERVE_FUNDS → tryReserve → FUNDS_RESERVED | FUNDS_FAILED
     * // type=CONFIRM_FUNDS  → confirm    → FUNDS_CONFIRMED | FUNDS_CANCELLED | FUNDS_FAILED
     * // type=CANCEL_FUNDS  → cancel     → FUNDS_CANCELLED
     * </pre>
     *
     * @param message 命令信封（含 sagaId／amount／forceFail）
     */
    @Override
    public void onMessage(SagaMessage message) {
        String type = message.type();
        if (SagaMessageTypes.RESERVE_FUNDS.equals(type)) {
            boolean ok = tccResource.tryReserve(message.sagaId(), message.accountId(), message.amount());
            publish(message, ok ? SagaMessageTypes.FUNDS_RESERVED : SagaMessageTypes.FUNDS_FAILED);
            return;
        }
        if (SagaMessageTypes.CONFIRM_FUNDS.equals(type)) {
            boolean ok = tccResource.confirm(message.sagaId(), message.forceFail());
            String eventType = ok
                    ? SagaMessageTypes.FUNDS_CONFIRMED
                    : (message.forceFail() ? SagaMessageTypes.FUNDS_CANCELLED : SagaMessageTypes.FUNDS_FAILED);
            publish(message, eventType);
            return;
        }
        if (SagaMessageTypes.CANCEL_FUNDS.equals(type)) {
            tccResource.cancel(message.sagaId());
            publish(message, SagaMessageTypes.FUNDS_CANCELLED);
        }
    }

    /**
     * 【職責】把結果事件送到 event topic（key＝sagaId）。
     * 【使用】僅內部；測試可 spy {@link KafkaMessageSender}。
     */
    private void publish(SagaMessage source, String eventType) {
        SagaMessage event = SagaMessage.of(
                source.sagaId(), source.orderId(), source.accountId(), eventType,
                source.amount(), source.symbol(), source.forceFail());
        kafkaMessageSender.send(eventTopic, source.sagaId(), event);
    }
}
