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
 * 【職責】Outbox（發件匣）寫入與發送：append 與訂單同交易；publishPending 另開交易送 Kafka。
 * 【技巧】先 send 成功再 markPublished，避免「標已發送但其實沒進 broker」。
 * 【概念】「提交後發訊」：匣＝本庫表；寄＝Kafka。中文敘述見 {@code docs/outbox-發件匣.md}。
 * <p>為何 Job 建構子寫 {@link OutboxRelay} 卻會拿到本類？
 * 因為本類同時 {@code implements OutboxRelay}，又標了 {@code @Service}，
 * Spring 把「本物件」登錄成 {@code OutboxRelay} 型別的 Bean；
 * {@link OutboxRelayJob} 只要這個介面時，容器就把本實例塞進去（依賴注入）。
 * 【使用】業務路徑呼叫 {@link #append}；排程呼叫 {@link #publishPending}（見 {@link OutboxRelayJob}）。
 */
@Service
public class OutboxPublisherService implements OutboxPort, OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final KafkaMessageSender kafkaMessageSender;

    /**
     * 【職責】注入 Outbox 儲存與 Kafka 發送埠。
     */
    public OutboxPublisherService(OutboxEventRepository outboxEventRepository,
                                  ObjectMapper objectMapper,
                                  KafkaMessageSender kafkaMessageSender) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.kafkaMessageSender = kafkaMessageSender;
    }

    /**
     * 【職責】在目前訂單 TX 內追加一列 unpublished Outbox。
     * 【技巧】只落庫、不送 Kafka；保證與訂單同進同退。
     * 【使用】由 {@code SagaOrchestrator.start}／{@code OrderSagaEventHandler.onReserved} 呼叫。
     * <pre>
     * outboxPort.append("trading.saga.commands", sagaId, SagaMessage.of(...));
     * // TX commit 後，下一次 tick 才 publishPending
     * </pre>
     *
     * @param topic   Kafka topic
     * @param key     通常為 sagaId（分區鍵）
     * @param message 命令／事件信封
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
     * 【職責】掃描 unpublished 列，送 Kafka 後標記已發送。
     * 【技巧】一批最多 50 筆；失敗丟例外讓排程下一輪重試（該列仍 unpublished）。
     * 【使用】勿在 HTTP 執行緒呼叫；由 {@link OutboxRelayJob#tick} 定時觸發。
     * <pre>
     * // OutboxRelayJob
     * {@literal @}Scheduled(...)
     * void tick() { outboxRelay.publishPending(); }
     * </pre>
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
