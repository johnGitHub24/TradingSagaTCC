package com.trading.saga.messaging;

import com.trading.saga.expansion.OutboxRelay;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 【職責】排程驅動 Outbox Relay；本身不碰 Repository。
 * 【概念】這是擴增成獨立進程前的同 JVM 版本。
 * 【使用】啟用 {@code @EnableScheduling} 後自動跑；間隔 {@code trading.outbox.poll-ms}（預設 200）。
 */
@Component
public class OutboxRelayJob {

    private final OutboxRelay outboxRelay;

    /**
     * 【職責】注入 Relay 埠（本版即 {@link OutboxPublisherService}）。
     */
    public OutboxRelayJob(OutboxRelay outboxRelay) {
        this.outboxRelay = outboxRelay;
    }

    /**
     * 【職責】輪詢 unpublished Outbox 並送 Kafka。
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
