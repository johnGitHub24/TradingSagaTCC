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
 * <p>【技巧】{@link #transitionTo(SagaStatus)} 委派 enum 邊；非法轉移丟 {@link IllegalStateException}。
 * <br>沒有 status setter，狀態只能經狀態機前進；靜態工廠 {@link #start} 保證初始值一定是 STARTED。
 * <p>【概念】編排者擁有這張表；參與者（帳戶）只回 Kafka 事件，不寫這張表。
 * <br>Saga 記「流程走到哪」，帳戶 TCC 預留票記「錢凍在哪」；兩者以同一個 sagaId 串起來，但分屬不同庫。
 * <p>【使用】建立：{@code SagaOrchestrator.start}；推進：{@code OrderSagaEventHandler}（ACCOUNT_CONFIRMING／COMPLETED）、
 * <br>{@code OrderMarkFailedAction.compensate}（COMPENSATING→COMPENSATED）；查詢：{@code TradeQueryService.getSaga}。
 * <p>【邊界】本表無 {@code @Version}：重送冪等靠呼叫端先判斷 {@code isTerminal()}／目前狀態，
 * <br>併發則依賴「key＝sagaId 的 Kafka 訊息同分區依序消費」。
 */
@Entity
@Table(name = "saga_instances")
@Getter
@NoArgsConstructor
public class SagaInstance {

    /** 全域流程 id（UUID）；同時是 Kafka 訊息 key、Outbox message_key、帳戶庫 TCC 預留票主鍵——兩庫唯一的共同語言。 */
    @Id
    @Column(name = "saga_id", nullable = false, length = 64)
    private String sagaId;

    /** 這筆 Saga 驅動的訂單（{@code trade_orders.order_id}）；同庫但不設 FK，事件處理時用它回查訂單。 */
    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    /** 流程狀態；{@code EnumType.STRING} 存名稱，enum 增減值不會讓舊列錯位，前台輪詢也直接拿字串比對終態。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SagaStatus status;

    /** Saga 建立時間（UTC）。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 最後一次狀態轉換時間；每次 {@link #transitionTo} 成功都會更新，可用來找「卡太久沒動」的 Saga。 */
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
     * 【職責】開啟一筆新 Saga（狀態 STARTED，createdAt＝updatedAt＝現在）。
     * <p>【概念】STARTED 只是初始值；{@code SagaOrchestrator.start} 會在同一 TX 內立刻轉 ACCOUNT_TRYING，外部幾乎看不到。
     * <p>【使用】僅 {@code SagaOrchestrator.start}。
     *
     * @param sagaId  全域流程 id（Kafka key）
     * @param orderId 對應訂單
     * @return 尚未持久化的實體
     */
    public static SagaInstance start(String sagaId, String orderId) {
        return new SagaInstance(sagaId, orderId);
    }

    /**
     * 【職責】依狀態機前進一步，並刷新 updatedAt。
     * <p>【技巧】合法性全交給 {@link SagaStatus#canTransitionTo}（唯一規則表），本方法只負責「不合法就丟、合法就改」。
     * <p>【概念】終態（COMPLETED／COMPENSATED／FAILED）的任何轉移都不合法，所以即使 Kafka 重送漏過呼叫端檢查，
     * <br>也改不動已結束的 Saga（會丟例外而非靜默覆寫）。
     * <p>【使用】正常流程的一跳：
     * <pre>
     * saga.transitionTo(SagaStatus.ACCOUNT_CONFIRMING); // ACCOUNT_TRYING → ACCOUNT_CONFIRMING
     * saga.transitionTo(SagaStatus.COMPENSATING);       // 任一非終態 → COMPENSATING
     * </pre>
     *
     * @param next 目標狀態
     * @throws IllegalStateException 目前狀態不允許跳到 {@code next}（狀態與 updatedAt 維持不變）
     */
    public void transitionTo(SagaStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("illegal saga transition " + status + " -> " + next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }
}
