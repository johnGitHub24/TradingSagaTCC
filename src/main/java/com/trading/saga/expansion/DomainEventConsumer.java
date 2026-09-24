package com.trading.saga.expansion;

import com.trading.saga.messaging.SagaMessage;

/**
 * 【職責】領域訊息消費契約。本版同 JVM {@code @KafkaListener}；未來可獨立 consumer 組。
 * 【使用】實作類：{@code AccountCommandHandler}（command）、{@code OrderSagaEventHandler}（event）；
 * 由 {@code SagaKafkaListeners} 注入並轉送。
 * 【邊界】不改 topic 名稱與 {@link SagaMessage} 欄位。
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
