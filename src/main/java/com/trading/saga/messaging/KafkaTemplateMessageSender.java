package com.trading.saga.messaging;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 【職責】KafkaTemplate 發送；同時寫入記憶體軌跡給前台。
 * 【技巧】{@code get(3s)} 讓教學路徑失敗時立刻爆，而不是 silently drop。
 *
 * <p>【怎麼運作】標 {@code @Component} + {@code implements KafkaMessageSender} →
 * 登錄成可注入的寄信 Bean；呼叫端寫介面 {@link KafkaMessageSender} 即可（建構子注入）。
 */
@Component
public class KafkaTemplateMessageSender implements KafkaMessageSender {

    private final KafkaTemplate<String, SagaMessage> kafkaTemplate;
    private final EventLogService eventLogService;

    /**
     * 【職責】注入 KafkaTemplate 與前台軌跡（建構子注入）。
     * 【概念】Spring 自動提供 {@code KafkaTemplate} Bean；你不必 new。
     *
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
