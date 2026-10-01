package com.trading.saga.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 【職責】訂單庫 Outbox（**發件匣**）：與訂單／Saga 同一交易寫入，提交後才進 Kafka。
 * 【技巧】publishedAt == null 表示待發送（還在匣裡）；Relay 送出後才填。
 * 用靜態工廠 {@link #unpublished} 建立，保證新列一定是「待發」，不會誤建成已發。
 * 【概念】一列＝匣裡的一封信（topic／key／payload）。避免「DB 成功但 Kafka 失敗」雙寫不一致。
 * 中文理解見 {@code docs/outbox-發件匣.md}。帳戶庫本版不寫 Outbox（擴增點）。
 * 【使用】寫入：{@code OutboxPublisherService.append}（由 {@code SagaOrchestrator.start}／
 * {@code OrderSagaEventHandler.onReserved} 在同一 order TX 內呼叫）；
 * 發送：{@code OutboxRelayJob.tick} → {@code OutboxPublisherService.publishPending} 掃待發列送 Kafka 後 {@link #markPublished}。
 * 【邊界】投遞語意是 at-least-once：{@code publishPending} 一批在同一 TX，若中途某封送失敗整批 rollback，
 * 前面已送出的信 publishedAt 也回到 null，下一輪會再送一次；接收端靠 Saga 終態／預留票狀態做冪等。
 * 已發送列目前不清理（本版無歸檔 Job）。
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor
public class OutboxEvent {

    /** 自增主鍵；Relay 依 id 由小到大送出，等同「寫入順序」，讓同一 Saga 的命令先寫先寄。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 目標 Kafka topic；本版只會是 command topic（{@code trading.kafka.command-topic}）。 */
    @Column(nullable = false, length = 128)
    private String topic;

    /** Kafka 訊息 key，實務上＝sagaId：同一 Saga 的訊息落在同一分區，消費端依序處理。 */
    @Column(name = "message_key", nullable = false, length = 64)
    private String messageKey;

    /** {@code SagaMessage} 序列化後的 JSON；用 {@code @Lob} 不設長度上限，Relay 送出前再反序列化回物件。 */
    @Lob
    @Column(nullable = false)
    private String payload;

    /** 投遞進匣的時間（與業務 TX 同時）；可用來觀察「進匣到寄出」的延遲。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 寄出時間；NULL＝待發（Relay 查詢條件 {@code PublishedAtIsNull}），非 NULL＝已確認送進 broker。 */
    @Column(name = "published_at")
    private Instant publishedAt;

    private OutboxEvent(String topic, String messageKey, String payload) {
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    /**
     * 【職責】建立一封「待發」的信（publishedAt＝null，尚未 persist）。
     * 【概念】只是落庫進匣，還沒碰 Kafka；要等外層 order TX commit 後，Relay 才看得到這列。
     * 【使用】僅 {@code OutboxPublisherService.append}。
     *
     * @param topic      目標 Kafka topic
     * @param messageKey Kafka key（通常為 sagaId）
     * @param payload    已序列化的 {@code SagaMessage} JSON
     * @return 待發實體
     */
    public static OutboxEvent unpublished(String topic, String messageKey, String payload) {
        return new OutboxEvent(topic, messageKey, payload);
    }

    /**
     * 【職責】標記已送到 Kafka（填入 publishedAt＝現在）。
     * 【技巧】呼叫端必須在 Kafka send 成功（同步等 ack）之後才呼叫，順序反過來會「標已發但其實沒寄出」而漏信。
     * 【使用】僅 {@code OutboxPublisherService.publishPending}。
     */
    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    /**
     * 【職責】是否仍在匣裡等待 Relay。
     * 【使用】主要給單元測試斷言（{@code OutboxPublisherServiceTest}）；Relay 查詢本身走 Repository 條件，不逐列呼叫本方法。
     *
     * @return true＝publishedAt 為 null（待發）
     */
    public boolean isUnpublished() {
        return publishedAt == null;
    }
}
