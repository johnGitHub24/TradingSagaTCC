package com.trading.saga.messaging;

import java.time.Instant;

/**
 * 【職責】前台 Kafka 軌跡一筆。
 */
public record EventLogEntry(
        String topic,
        String type,
        String sagaId,
        String payload,
        Instant at
) {
}
