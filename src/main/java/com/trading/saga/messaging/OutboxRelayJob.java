package com.trading.saga.messaging;

import com.trading.saga.expansion.OutboxRelay;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 【職責】排程驅動 Outbox Relay（發件匣轉送）；本身不碰 Repository。
 * 【技巧】只呼叫 {@link OutboxRelay#publishPending}，不直接操作 outbox 表。
 * 【概念】Outbox 中文＝「發件匣」：業務先把要發的 Kafka 訊息寫進本庫，
 * 本 Job 像定時郵差，把匣裡尚未寄出的信送到 Kafka（提交後發訊）。
 * 【使用】啟用 {@code @EnableScheduling} 後自動跑；間隔 {@code trading.outbox.poll-ms}（預設 200）。
 * 學習敘述見專案 {@code docs/outbox-發件匣.md}。
 *
 * <p>【Spring 注入怎麼接上】本類標 {@code @Component} → 啟動時 Spring 要 new 它。
 * 建構子參數是介面 {@link OutboxRelay}，容器裡唯一實作是 {@link OutboxPublisherService}
 * （{@code @Service} + {@code implements OutboxRelay}）→ 自動塞進來。
 * 程式裡沒有 {@code new OutboxPublisherService(...)}；這叫建構子依賴注入（DI）。
 */
@Component
public class OutboxRelayJob {

    /** 郵差手上的「寄信插座」；執行期實際型別是 {@link OutboxPublisherService}。 */
    private final OutboxRelay outboxRelay;

    /**
     * 【職責】由 Spring 注入 Relay 埠（本版＝{@link OutboxPublisherService}）。
     * 【技巧】參數型別寫介面、不寫實作類；Spring 依「型別＋唯一 Bean」對上。
     * 【概念】不是 Job「自己變聰明去找」：是容器啟動掃描 Bean 時，
     * 看到「需要一個 OutboxRelay」→ 找到唯一演員 Publisher → 呼叫本建構子傳入。
     * IDE：對參數型別右鍵 Find Implementations → 應只看到 Publisher。
     * 除錯：斷點看 {@code outboxRelay.getClass()} → {@code OutboxPublisherService}。
     * 若日後有兩個 {@code OutboxRelay} 實作，啟動會失敗（ambiguous），需 {@code @Primary}／{@code @Qualifier}。
     *
     * @param outboxRelay Spring 注入的寄信實作（本版即 Publisher）
     */
    public OutboxRelayJob(OutboxRelay outboxRelay) {
        this.outboxRelay = outboxRelay;
    }

    /**
     * 【職責】輪詢發件匣中 unpublished 列並送 Kafka。
     * 【使用】勿手動高頻呼叫（已有排程）；整合測試可縮短 poll-ms 加速。
     * <pre>
     * // application.yml
     * trading.outbox.poll-ms: 50   # 測試用
     * </pre>
     */
    @Scheduled(fixedDelayString = "${trading.outbox.poll-ms:200}")
    public void tick() {
        outboxRelay.publishPending();
    }
}
