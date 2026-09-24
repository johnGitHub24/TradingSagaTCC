package com.trading.saga.messaging;

/**
 * 【職責】訂單庫寫入 Outbox 的埠（與 Kafka 解耦，利於單元測）。
 * 【使用】業務 Service 注入此介面並 {@link #append}；實作類為 {@link OutboxPublisherService}。
 */
public interface OutboxPort {

    /**
     * 【職責】與業務同一交易 append 一列 unpublished。
     * 【使用】必須在 {@code @Transactional("orderTransactionManager")} 內呼叫。
     *
     * @param topic   Kafka topic
     * @param key     通常為 sagaId
     * @param message 信封
     */
    void append(String topic, String key, SagaMessage message);
}
