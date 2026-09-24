package com.trading.saga.expansion;

/**
 * 【職責】Outbox 發送契約。本版同 JVM 輪詢；未來可換成獨立 relay 進程。
 * 【使用】由 {@code OutboxRelayJob.tick} 呼叫 {@link #publishPending}；實作類 {@code OutboxPublisherService}。
 * 【邊界】不改 outbox 表語意（unpublished → published）。
 */
public interface OutboxRelay {

    /**
     * 【職責】發送一批待發事件到 Kafka。
     * 【使用】排程觸發；勿在下單 HTTP 執行緒同步呼叫（會拉長請求時間）。
     */
    void publishPending();
}
