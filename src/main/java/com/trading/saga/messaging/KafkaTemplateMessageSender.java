package com.trading.saga.messaging;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 【職責】KafkaTemplate 發送；同時寫入記憶體軌跡給前台。
 * <p>【技巧】{@code get(3s)} 讓教學路徑失敗時立刻爆，而不是 silently drop。
 * <p>【概念】{@code KafkaTemplate.send} 本身是非同步（回傳 Future）；在這裡同步等 broker ack，
 * <br>呼叫端才能確定「真的寄出去了」再做下一步：
 *         <ul>
 *           <li>{@link OutboxPublisherService#publishPending}：send 成功才 {@code markPublished}；
 *               失敗例外往外拋，該列維持 unpublished，下一輪排程重試（at-least-once）。</li>
 *           <li>{@link AccountCommandHandler}：TCC 結果事件直接送 event topic（帳戶側不走 Outbox）。</li>
 *         </ul>
 * <p>【邊界】不做重試、不做序列化（交給 yml 設定的 {@code JsonSerializer}）；只在送出成功後才記軌跡。
 *
 * <p>【怎麼運作】標 {@code @Component} + {@code implements KafkaMessageSender} →
 * <br>登錄成可注入的寄信 Bean；呼叫端寫介面 {@link KafkaMessageSender} 即可（建構子注入）。
 */
@Component
public class KafkaTemplateMessageSender implements KafkaMessageSender {

    private final KafkaTemplate<String, SagaMessage> kafkaTemplate;
    private final EventLogService eventLogService;

    /**
     * 【職責】注入 KafkaTemplate 與前台軌跡（建構子注入）。
     * <p>【概念】Spring 自動提供 {@code KafkaTemplate} Bean；你不必 new。
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
     * 【職責】同步送出一則 Saga 訊息，成功後記入 {@link EventLogService}。
     * <p>【技巧】所有例外（逾時、broker 拒收、執行緒中斷）統一包成 {@link IllegalStateException}，
     * <br>訊息帶 topic 與 key，排查時一眼知道哪筆卡住。
     *
     * @param topic   目標 topic（command 或 event）
     * @param key     partition key（本專案慣例為 sagaId）
     * @param message 要送出的訊息
     * @throws IllegalStateException 3 秒內未收到 broker ack 或送出失敗
     */
    @Override
    public void send(String topic, String key, SagaMessage message) {
        try {
            // 同步等待 ack：讓 Outbox 只在真正送達後才標記已發送
            kafkaTemplate.send(topic, key, message).get(3, TimeUnit.SECONDS);
            // 放在 get 之後：只有成功送出的訊息才出現在前台軌跡
            eventLogService.record(topic, message);
        } catch (Exception ex) {
            throw new IllegalStateException("kafka send failed topic=" + topic + " key=" + key, ex);
        }
    }
}
