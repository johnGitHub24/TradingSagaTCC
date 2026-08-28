package com.trading.saga.messaging;

/**
 * 【職責】訂單庫寫入 Outbox 的埠（與 Kafka 解耦，利於單元測）。
 */
public interface OutboxPort {

    /**
     * 與業務同一交易 append 一列 unpublished。
     *
     * @param topic   Kafka topic
     * @param key     通常為 sagaId
     * @param message 信封
     */
    void append(String topic, String key, SagaMessage message);
}
