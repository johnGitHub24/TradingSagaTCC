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
 *
 * <p>【怎麼運作】
 * <ol>
 *   <li>註冊：{@code @Service} + {@code implements DomainEventConsumer}
 *       → Bean 名 {@code accountCommandHandler}，供 Listener {@code @Qualifier} 注入。</li>
 *   <li>注入：建構子要 {@link TccResource}（實作＝{@link com.trading.saga.account.AccountTccService}）、
 *       {@link KafkaMessageSender}（實作＝{@link KafkaTemplateMessageSender}）、
 *       以及 {@code @Value} 的 event topic。沒有無參建構子。</li>
 *   <li>進線：{@link SagaKafkaListeners#onCommand} → {@link #onMessage}。</li>
 *   <li>分派：RESERVE／CONFIRM／CANCEL → TCC → {@link #publish} 直接送 event topic
 *       （帳戶側結果事件本版不走 Outbox，與訂單側「提交後發訊」對稱但路徑不同）。</li>
 * </ol>
 */
@Service
public class AccountCommandHandler implements DomainEventConsumer {

    private final TccResource tccResource;
    private final KafkaMessageSender kafkaMessageSender;
    private final String eventTopic;

    /**
     * 【職責】綁定 TCC 與 event topic（建構子注入，見類別上方【怎麼運作】）。
     * 【技巧】{@link TccResource}／{@link KafkaMessageSender} 皆為介面；Spring 找唯一實作塞入。
     * 【概念】和 {@link OrderSagaEventHandler}、{@link OutboxRelayJob} 同一套 DI。
     *
     * @param tccResource         帳戶 TCC（本版＝AccountTccService）
     * @param kafkaMessageSender  實際寄 Kafka
     * @param eventTopic          {@code trading.kafka.event-topic}
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
