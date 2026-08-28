package com.trading.saga.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 【職責】訂單庫內的 Saga 實例：跨庫流程的進度，不存帳戶餘額。
 * 【技巧】{@link #transitionTo(SagaStatus)} 委派 enum 邊；非法轉移丟 {@link IllegalStateException}。
 * 【概念】編排者擁有這張表；參與者（帳戶）只回 Kafka 事件，不寫這張表。
 */
@Entity
@Table(name = "saga_instances")
@Getter
@NoArgsConstructor
public class SagaInstance {

    @Id
    @Column(name = "saga_id", nullable = false, length = 64)
    private String sagaId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SagaStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private SagaInstance(String sagaId, String orderId) {
        this.sagaId = sagaId;
        this.orderId = orderId;
        this.status = SagaStatus.STARTED;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 開啟一筆新 Saga（狀態 STARTED）。
     *
     * @param sagaId  全域流程 id（Kafka key）
     * @param orderId 對應訂單
     * @return 尚未持久化的實體
     */
    public static SagaInstance start(String sagaId, String orderId) {
        return new SagaInstance(sagaId, orderId);
    }

    /**
     * 依狀態機前進一步。
     *
     * @param next 目標
     */
    public void transitionTo(SagaStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("illegal saga transition " + status + " -> " + next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }
}
