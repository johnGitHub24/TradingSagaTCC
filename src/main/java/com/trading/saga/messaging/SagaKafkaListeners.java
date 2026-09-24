package com.trading.saga.messaging;

import com.trading.saga.expansion.DomainEventConsumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 【職責】Kafka listener：command → 帳戶 TCC；event → 訂單編排。
 * 【技巧】兩個 {@link DomainEventConsumer} 用 {@code @Qualifier} 分開，預留拆進程。
 * 【使用】應用啟動後自動訂閱；無需業務程式呼叫。測試用內嵌／EmbeddedKafka 即可觸發。
 *
 * <p>【怎麼運作】
 * <ol>
 *   <li>註冊：{@code @Component} → Spring 建立本類 Bean。</li>
 *   <li>注入：建構子要兩個 {@link DomainEventConsumer}。容器裡有兩個實作
 *       （{@link AccountCommandHandler}、{@link OrderSagaEventHandler}），
 *       必須用 {@code @Qualifier("bean名")} 指名，否則啟動報 ambiguous。
 *       Bean 名預設＝類名首字母小寫（{@code accountCommandHandler}／{@code orderSagaEventHandler}）。</li>
 *   <li>進線：{@code @KafkaListener} 收到訊息 → {@link #onCommand}／{@link #onEvent}。</li>
 *   <li>轉送：只呼叫 {@code onMessage}，本類不做業務判斷。</li>
 * </ol>
 */
@Component
public class SagaKafkaListeners {

    private final DomainEventConsumer accountCommandHandler;
    private final DomainEventConsumer orderSagaEventHandler;

    /**
     * 【職責】綁定兩個消費者 Bean（建構子注入＋{@code @Qualifier}）。
     * 【技巧】介面相同時必須 Qualifier；對照 {@link OutboxRelayJob} 只有一個實作就不必。
     * 【概念】不是 Listener「自己 new Handler」——Spring 先建好兩個 Handler，再塞進本建構子。
     *
     * @param accountCommandHandler 帳戶參與者（bean 名 accountCommandHandler）
     * @param orderSagaEventHandler 訂單編排（bean 名 orderSagaEventHandler）
     */
    public SagaKafkaListeners(@Qualifier("accountCommandHandler") DomainEventConsumer accountCommandHandler,
                              @Qualifier("orderSagaEventHandler") DomainEventConsumer orderSagaEventHandler) {
        this.accountCommandHandler = accountCommandHandler;
        this.orderSagaEventHandler = orderSagaEventHandler;
    }

    /**
     * 【職責】消費 TCC 命令 topic。
     * 【使用】訊息由 Outbox {@code publishPending} 送入；payload 反序列化為 {@link SagaMessage}。
     *
     * @param message 命令信封（RESERVE／CONFIRM／CANCEL_FUNDS）
     */
    @KafkaListener(topics = "${trading.kafka.command-topic}", groupId = "saga-account-tcc")
    public void onCommand(SagaMessage message) {
        accountCommandHandler.onMessage(message);
    }

    /**
     * 【職責】消費 TCC 結果 event topic。
     * 【使用】由帳戶側 {@code AccountCommandHandler} 發布後進入。
     *
     * @param message 事件信封（FUNDS_RESERVED／CONFIRMED／FAILED／CANCELLED）
     */
    @KafkaListener(topics = "${trading.kafka.event-topic}", groupId = "saga-order-orchestrator")
    public void onEvent(SagaMessage message) {
        orderSagaEventHandler.onMessage(message);
    }
}
