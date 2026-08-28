package com.trading.saga.messaging;

import com.trading.saga.expansion.OutboxRelay;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 【職責】排程驅動 Outbox Relay；本身不碰 Repository。
 * 【概念】這是擴增成獨立進程前的同 JVM 版本。
 */
@Component
public class OutboxRelayJob {

    private final OutboxRelay outboxRelay;

    /**
     * @param outboxRelay 發送埠
     */
    public OutboxRelayJob(OutboxRelay outboxRelay) {
        this.outboxRelay = outboxRelay;
    }

    /**
     * 輪詢 unpublished 列。
     */
    @Scheduled(fixedDelayString = "${trading.outbox.poll-ms:200}")
    public void tick() {
        outboxRelay.publishPending();
    }
}
