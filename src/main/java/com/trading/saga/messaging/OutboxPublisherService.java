package com.trading.saga.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.saga.expansion.OutboxRelay;
import com.trading.saga.order.domain.OutboxEvent;
import com.trading.saga.order.infrastructure.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 【職責】Outbox 寫入與發送：append 與訂單同交易；publishPending 另開交易送 Kafka。
 * 【技巧】先 send 成功再 markPublished，避免「標已發送但其實沒進 broker」。
 * 【概念】這是「提交後發訊」的最小實作；獨立 relay 進程只要換 {@link OutboxRelay} 部署方式。
 */
@Service
public class OutboxPublisherService implements OutboxPort, OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final KafkaMessageSender kafkaMessageSender;

    /**
     * 建構 Outbox 服務。
     */
    public OutboxPublisherService(OutboxEventRepository outboxEventRepository,
                                  ObjectMapper objectMapper,
                                  KafkaMessageSender kafkaMessageSender) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.kafkaMessageSender = kafkaMessageSender;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void append(String topic, String key, SagaMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            outboxEventRepository.save(OutboxEvent.unpublished(topic, key, json));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialize outbox payload", ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional("orderTransactionManager")
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByIdAsc();
        for (OutboxEvent event : pending) {
            try {
                SagaMessage message = objectMapper.readValue(event.getPayload(), SagaMessage.class);
                kafkaMessageSender.send(event.getTopic(), event.getMessageKey(), message);
                event.markPublished();
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("cannot deserialize outbox id=" + event.getId(), ex);
            }
        }
    }
}
