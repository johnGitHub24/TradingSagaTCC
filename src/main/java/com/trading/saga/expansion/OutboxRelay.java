package com.trading.saga.expansion;

/**
 * 【職責】Outbox「寄信」契約：把發件匣裡尚未寄出的列送到 Kafka。
 * 【技巧】只宣告方法、不寫實作；本版唯一實作是
 * {@link com.trading.saga.messaging.OutboxPublisherService}（標了 {@code @Service}）。
 * 【概念】這是「角色（介面）」，不是「演員（類別）」。
 * {@link com.trading.saga.messaging.OutboxRelayJob} 建構子只要 {@code OutboxRelay}，
 * Spring 啟動時在容器裡找「誰實作了這個介面」——找到 {@code OutboxPublisherService} 就注入進去。
 * 教學上：Job＝定時郵差；本介面＝「怎麼寄」的插座；Publisher＝實際寄信的人。
 * 【使用】由 {@code OutboxRelayJob.tick} 呼叫 {@link #publishPending}。
 * 【邊界】不改 outbox 表語意（unpublished → published）；本版同 JVM 輪詢，未來可換成獨立 relay 進程。
 */
public interface OutboxRelay {

    /**
     * 【職責】發送一批待發事件到 Kafka。
     * 【使用】排程觸發；勿在下單 HTTP 執行緒同步呼叫（會拉長請求時間）。
     */
    void publishPending();
}
