package com.trading.saga.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 【職責】訂單庫的 Saga 步驟日誌，供前台時間軸與除錯。
 * 【邊界】不是 Kafka 本體；Kafka 軌跡另見記憶體 EventLog。
 */
@Entity
@Table(name = "saga_steps")
@Getter
@NoArgsConstructor
public class SagaStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "saga_id", nullable = false, length = 64)
    private String sagaId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 512)
    private String detail;

    @Column(nullable = false)
    private Instant at;

    private SagaStep(String sagaId, String name, String detail) {
        this.sagaId = sagaId;
        this.name = name;
        this.detail = detail;
        this.at = Instant.now();
    }

    /**
     * 記錄一步。
     */
    public static SagaStep of(String sagaId, String name, String detail) {
        return new SagaStep(sagaId, name, detail);
    }
}
