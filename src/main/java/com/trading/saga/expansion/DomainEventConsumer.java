package com.trading.saga.expansion;

import com.trading.saga.messaging.SagaMessage;

/**
 * 【職責】領域訊息消費契約。本版同 JVM {@code @KafkaListener}；未來可獨立 consumer 組。
 * 【使用】實作類：{@code AccountCommandHandler}（command）、{@code OrderSagaEventHandler}（event）；
 * 由 {@code SagaKafkaListeners} 注入並轉送。
 * 【邊界】不改 topic 名稱與 {@link SagaMessage} 欄位。
 *
 * <p>【怎麼運作／注入】兩個實作都標 {@code @Service}，都會變成 {@code DomainEventConsumer} Bean。
 * {@link com.trading.saga.messaging.SagaKafkaListeners} 建構子因此必須 {@code @Qualifier} 指名，
 * 否則 Spring 不知道 command 線要哪個、event 線要哪個（對照：OutboxRelay 只有一個實作就不必 Qualifier）。
 */
public interface DomainEventConsumer {

    /**
     * 【職責】處理一則 Saga 命令或事件。
     * 【使用】Kafka listener 每消費一筆呼叫一次；實作內自行依 {@code message.type()} 分支。
     *
     * @param message 命令或事件信封
     */
    void onMessage(SagaMessage message);
}
