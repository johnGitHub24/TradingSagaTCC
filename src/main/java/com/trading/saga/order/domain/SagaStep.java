package com.trading.saga.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 【職責】訂單庫的 Saga 步驟日誌，供前台時間軸與除錯。
 * 【技巧】只追加（append-only）：建立後沒有任何修改方法；一筆 Saga 對多筆步驟，以 sagaId 關聯（不設 JPA 關聯／FK）。
 * 【概念】{@link SagaInstance#getStatus()} 只告訴你「現在在哪」；本表告訴你「怎麼走到這裡」，
 * 是一份可讀的流程軌跡，不是狀態來源（判斷流程是否結束請看 Saga 狀態）。
 * 【使用】寫入時機與 name：{@code SagaOrchestrator.start}（ORDER_CREATED、RESERVE_COMMANDED）、
 * {@code OrderSagaEventHandler}（CONFIRM_COMMANDED、SAGA_COMPLETED）、
 * {@code OrderMarkFailedAction.compensate}（ORDER_MARK_FAILED）；
 * 讀取：{@code TradeQueryService.getSaga} → {@link com.trading.saga.order.dto.SagaResponse} 的 steps，前台 index.html 顯示時間軸。
 * 【邊界】不是 Kafka 本體；Kafka 軌跡另見記憶體 EventLog（{@code EventLogService}）。
 */
@Entity
// saga_id 索引：TradeQueryService.getSaga 以 findBySagaIdOrderByAtAscIdAsc 查時間軸，避免全表掃描（hbm2ddl 建表時一併建立）
@Table(name = "saga_steps", indexes = @Index(name = "idx_saga_steps_saga_id", columnList = "saga_id"))
@Getter
@NoArgsConstructor
public class SagaStep {

    /** 自增主鍵；同一毫秒內多步時，查詢用 {@code at, id} 雙排序，id 保證寫入順序不亂。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所屬 Saga（{@code saga_instances.saga_id}）；查時間軸的條件欄位。 */
    @Column(name = "saga_id", nullable = false, length = 64)
    private String sagaId;

    /** 步驟代號（如 ORDER_CREATED、CONFIRM_COMMANDED）；常數定義在寫入端類別，僅供顯示，勿當業務分支條件。 */
    @Column(nullable = false, length = 64)
    private String name;

    /** 補充說明（如金額、命令型別、"order FAILED"）；可為 null，上限 512 字。 */
    @Column(length = 512)
    private String detail;

    /** 步驟發生時間（UTC）；時間軸排序主鍵。 */
    @Column(nullable = false)
    private Instant at;

    private SagaStep(String sagaId, String name, String detail) {
        this.sagaId = sagaId;
        this.name = name;
        this.detail = detail;
        this.at = Instant.now();
    }

    /**
     * 【職責】記錄一步（at＝現在，尚未 persist）。
     * 【使用】與狀態變更在同一 order TX 內 {@code sagaStepRepository.save(SagaStep.of(...))}，
     * 讓軌跡與狀態同進同退（TX rollback 時不會留下假步驟）。
     * <pre>
     * SagaStep.of(sagaId, "ORDER_CREATED", "order PENDING amount=10000");
     * </pre>
     *
     * @param sagaId 所屬 Saga
     * @param name   步驟代號
     * @param detail 補充說明（可為 null）
     * @return 新步驟實體
     */
    public static SagaStep of(String sagaId, String name, String detail) {
        return new SagaStep(sagaId, name, detail);
    }
}
