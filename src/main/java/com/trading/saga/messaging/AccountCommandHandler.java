package com.trading.saga.messaging;

import com.trading.saga.expansion.TccResource;
import com.trading.saga.expansion.DomainEventConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 【職責】帳戶側消費 command：TCC Try／Confirm／Cancel，再發 event。
 * 【概念】這是 TCC 參與者；成功與否用事件告訴編排者，不寫訂單庫。
 */
@Service
public class AccountCommandHandler implements DomainEventConsumer {

    private final TccResource tccResource;
    private final KafkaMessageSender kafkaMessageSender;
    private final String eventTopic;

    /**
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
     * {@inheritDoc}
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

    private void publish(SagaMessage source, String eventType) {
        SagaMessage event = SagaMessage.of(
                source.sagaId(), source.orderId(), source.accountId(), eventType,
                source.amount(), source.symbol(), source.forceFail());
        kafkaMessageSender.send(eventTopic, source.sagaId(), event);
    }
}
