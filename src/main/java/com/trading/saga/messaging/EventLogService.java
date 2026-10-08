package com.trading.saga.messaging;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 【職責】記憶體 ring buffer，給前台看 Kafka 走過哪些 type。
 * <p>【技巧】{@link ConcurrentLinkedDeque}：Outbox 排程執行緒與 Kafka Listener 執行緒會同時寫入，
 * <br>HTTP 執行緒同時讀取，無鎖 Deque 讓三方不必 {@code synchronized}。
 * <br>新訊息加在頭、超過上限從尾巴丟，維持「新到舊、最多 100 筆」。
 * <p>【概念】這是觀察用的旁路紀錄，不參與 Saga 狀態判斷；遺失或清空都不影響交易正確性。
 * <p>【邊界】不持久化；重啟即空。正式觀測應改接 topic UI／OpenTelemetry（擴增）。
 *
 * <p>【怎麼運作】{@code @Service} 無建構子參數 → Spring 用預設建構子建 Bean；
 * <br>再被 {@link KafkaTemplateMessageSender}／Controller 建構子注入使用。
 */
@Service
public class EventLogService {

    /** 保留筆數上限；與 {@code API規格書.md} 的「最多 100 筆」一致。 */
    private static final int MAX = 100;
    private final ConcurrentLinkedDeque<EventLogEntry> entries = new ConcurrentLinkedDeque<>();

    /**
     * 【職責】記錄一則已送出的訊息。
     * <p>【技巧】{@code payload} 存 {@code message.toString()}（record 的自動字串），方便前台直接顯示；
     * <br>時間戳在記錄當下取，代表「送出成功的時間」而非業務發生時間。
     * <p>【邊界】併發寫入時上限是「近似」100：size 檢查與移除不是原子操作，可能短暫多一兩筆，觀察用途可接受。
     *
     * @param topic   實際送往的 Kafka topic
     * @param message 已送出的 Saga 訊息
     */
    public void record(String topic, SagaMessage message) {
        entries.addFirst(new EventLogEntry(
                topic, message.type(), message.sagaId(), message.toString(), Instant.now()));
        while (entries.size() > MAX) {
            entries.removeLast();
        }
    }

    /**
     * 【職責】取得目前軌跡。
     * <p>【技巧】回傳複製出來的 {@link ArrayList}，呼叫端（序列化成 JSON）不會碰到仍在變動的內部 Deque。
     *
     * @return 新到舊副本
     */
    public List<EventLogEntry> list() {
        return new ArrayList<>(entries);
    }
}
