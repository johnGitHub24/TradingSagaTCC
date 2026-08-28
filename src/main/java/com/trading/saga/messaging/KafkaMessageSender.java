package com.trading.saga.messaging;

/**
 * 【職責】實際把信封丟進 Kafka 的窄埠（單元測可 Mock）。
 */
public interface KafkaMessageSender {

    /**
     * 同步送出（教學版等 send 完成，方便前台立刻看到軌跡）。
     *
     * @param topic   topic
     * @param key     key
     * @param message 信封
     */
    void send(String topic, String key, SagaMessage message);
}
