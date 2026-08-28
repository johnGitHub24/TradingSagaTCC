package com.trading.saga.messaging;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 【職責】記憶體 ring buffer，給前台看 Kafka 走過哪些 type。
 * 【邊界】不持久化；重啟即空。正式觀測應改接 topic UI／OpenTelemetry（擴增）。
 */
@Service
public class EventLogService {

    private static final int MAX = 100;
    private final ConcurrentLinkedDeque<EventLogEntry> entries = new ConcurrentLinkedDeque<>();

    /**
     * 記錄一則已送出的訊息。
     */
    public void record(String topic, SagaMessage message) {
        entries.addFirst(new EventLogEntry(
                topic, message.type(), message.sagaId(), message.toString(), Instant.now()));
        while (entries.size() > MAX) {
            entries.removeLast();
        }
    }

    /**
     * @return 新到舊副本
     */
    public List<EventLogEntry> list() {
        return new ArrayList<>(entries);
    }
}
