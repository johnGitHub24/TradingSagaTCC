package com.trading.saga.messaging;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 【職責】KafkaTemplate 發送；同時寫入記憶體軌跡給前台。
 * 【技巧】{@code get(3s)} 讓教學路徑失敗時立刻爆，而不是 silently drop。
 */
@Component
public class KafkaTemplateMessageSender implements KafkaMessageSender {

    private final KafkaTemplate<String, SagaMessage> kafkaTemplate;
    private final EventLogService eventLogService;

    /**
     * @param kafkaTemplate   Spring Kafka
     * @param eventLogService 前台軌跡
     */
    public KafkaTemplateMessageSender(KafkaTemplate<String, SagaMessage> kafkaTemplate,
                                      EventLogService eventLogService) {
        this.kafkaTemplate = kafkaTemplate;
        this.eventLogService = eventLogService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void send(String topic, String key, SagaMessage message) {
        try {
            kafkaTemplate.send(topic, key, message).get(3, TimeUnit.SECONDS);
            eventLogService.record(topic, message);
        } catch (Exception ex) {
            throw new IllegalStateException("kafka send failed topic=" + topic + " key=" + key, ex);
        }
    }
}
