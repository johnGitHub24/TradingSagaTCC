package com.trading.saga.expansion;

/**
 * 【職責】Outbox 發送契約。本版同 JVM 輪詢；未來可換成獨立 relay 進程。
 * 【邊界】不改 outbox 表語意（unpublished → published）。
 */
public interface OutboxRelay {

    /**
     * 發送一批待發事件到 Kafka。
     */
    void publishPending();
}
