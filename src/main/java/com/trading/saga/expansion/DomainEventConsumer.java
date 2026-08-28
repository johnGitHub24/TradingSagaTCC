package com.trading.saga.expansion;

import com.trading.saga.messaging.SagaMessage;

/**
 * 【職責】領域事件消費契約。本版同 JVM {@code @KafkaListener}；未來可獨立 consumer 組。
 * 【邊界】不改 topic 名稱與 {@link SagaMessage} 欄位。
 */
public interface DomainEventConsumer {

    /**
     * 處理一則 Saga 訊息。
     *
     * @param message 命令或事件
     */
    void onMessage(SagaMessage message);
}
