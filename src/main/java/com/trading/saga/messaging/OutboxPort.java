package com.trading.saga.messaging;

/**
 * 【職責】訂單庫寫入 Outbox 的埠（與 Kafka 解耦，利於單元測）。
 * <p>【使用】業務 Service 注入此介面並 {@link #append}；實作類為 {@link OutboxPublisherService}。
 *
 * <p>【怎麼運作／注入】角色＝介面；演員＝{@link OutboxPublisherService}
 * <br>（同時 {@code implements OutboxPort, OutboxRelay} + {@code @Service}）。
 * <br>{@link com.trading.saga.saga.SagaOrchestrator}／{@link OrderSagaEventHandler} 建構子只要 {@code OutboxPort}，
 * <br>Spring 找到唯一實作就注入——與 {@link com.trading.saga.expansion.OutboxRelay} → RelayJob 同一套。
 */
public interface OutboxPort {

    /**
     * 【職責】與業務同一交易 append 一列 unpublished。
     * <p>【使用】必須在 {@code @Transactional("orderTransactionManager")} 內呼叫。
     *
     * @param topic   Kafka topic
     * @param key     通常為 sagaId
     * @param message 信封
     */
    void append(String topic, String key, SagaMessage message);
}
