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
 * 【職責】訂單庫 Outbox：與訂單／Saga 同一交易寫入，提交後才進 Kafka。
 * 【技巧】publishedAt == null 表示待發送；Relay 送出後才填。
 * 【概念】避免「DB 成功但 Kafka 失敗」造成雙寫不一致；帳戶庫本版不寫 Outbox（擴增點）。
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String topic;

    @Column(name = "message_key", nullable = false, length = 64)
    private String messageKey;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    private OutboxEvent(String topic, String messageKey, String payload) {
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    /**
     * 尚未發送的 Outbox 列。
     */
    public static OutboxEvent unpublished(String topic, String messageKey, String payload) {
        return new OutboxEvent(topic, messageKey, payload);
    }

    /**
     * 標記已送到 Kafka。
     */
    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    /**
     * @return 是否仍待 Relay
     */
    public boolean isUnpublished() {
        return publishedAt == null;
    }
}
