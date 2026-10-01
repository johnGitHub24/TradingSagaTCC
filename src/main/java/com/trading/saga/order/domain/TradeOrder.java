package com.trading.saga.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 【職責】訂單庫（orderdb）{@code trade_orders} 的交易單；金額在下單當下算死，後續只改 status。
 * 【技巧】私有建構子＋靜態工廠 {@link #pending}：外部無法 new 出「非 PENDING」或「amount 與 qty×price 不符」的訂單；
 * 狀態只能經 {@link #markFilled}／{@link #markFailed} 改變，沒有 setter。
 * {@code @NoArgsConstructor} 只為滿足 JPA 反射建立實體，業務程式不應使用。
 * 【概念】PENDING 表示 Saga 進行中；FILLED／FAILED 是終態。帳戶餘額不在這張表（在 accountdb {@code accounts}），
 * 訂單側只靠 Kafka 事件得知扣款結果。訂單狀態是「給使用者看的結果」，流程細節看 {@link SagaInstance}。
 * 【使用】建立：{@code SagaOrchestrator.start}；收尾：{@code OrderSagaEventHandler.onConfirmed}（成功）、
 * {@code OrderMarkFailedAction.compensate}（失敗）；查詢投影：{@link com.trading.saga.order.dto.TradeResponse#from}。
 * 【邊界】不檢查狀態轉換合法性（終態冪等由呼叫端先看 Saga {@code isTerminal()} 把關）；
 * 本表無 {@code @Version}，併發保護靠「同一 sagaId 的 Kafka 訊息同分區依序消費」。
 */
@Entity
@Table(name = "trade_orders")
@Getter
@NoArgsConstructor
public class TradeOrder {

    /** 訂單主鍵：{@code SagaOrchestrator} 以 UUID 字串產生（非自增），下單當下即可回傳給前端，不必等 DB 產號。 */
    @Id
    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    /** 對應的 Saga 流程 id（與 {@link SagaInstance#getSagaId()} 一對一）；同庫但不設 FK，靠應用層維持關聯。 */
    @Column(name = "saga_id", nullable = false, length = 64)
    private String sagaId;

    /** 下單帳戶；指向 accountdb {@code accounts}，跨庫所以只存字串、不可能有 FK。 */
    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, length = 32)
    private String symbol;

    /** BUY／SELL（由 {@link com.trading.saga.order.dto.TradeRequest} 驗證）；本版 TCC 一律凍結 amount，不因方向而異。 */
    @Column(nullable = false, length = 8)
    private String side;

    /** 數量：金額類一律 {@link BigDecimal}＋precision 19／scale 4，避免 double 浮點誤差。 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    /** 單價：同 quantity 用 BigDecimal 定點小數。 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    /** 名目金額＝quantity × price，建構時算定並落庫；之後 RESERVE／CONFIRM 命令都帶這個值，確保 Try 與 Confirm 金額一致。 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** 訂單狀態；{@code EnumType.STRING} 存名稱而非序號，enum 調整順序或插入新值時舊資料不會錯位，H2 Console 也直接可讀。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    /** 教學開關：true 時 {@code OrderSagaEventHandler.onReserved} 會把它帶進 CONFIRM_FUNDS，帳戶側改走 Cancel（TCC-002）。存在訂單上是因為 Confirm 命令在事件回來後才組。 */
    @Column(name = "force_fail", nullable = false)
    private boolean forceFail;

    /** 建立時間（UTC {@link Instant}）；{@code findAllByOrderByCreatedAtDesc} 用它排序訂單列表。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private TradeOrder(String orderId, String sagaId, String accountId, String symbol, String side,
                       BigDecimal quantity, BigDecimal price, boolean forceFail) {
        this.orderId = orderId;
        this.sagaId = sagaId;
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.amount = quantity.multiply(price);
        this.status = OrderStatus.PENDING;
        this.forceFail = forceFail;
        this.createdAt = Instant.now();
    }

    /**
     * 【職責】建立 PENDING 訂單（尚未 persist）。
     * 【技巧】靜態工廠取代 public 建構子：名稱直接說明「建出來就是 PENDING」，並集中算 amount。
     * 【概念】此刻帳戶還沒動；HTTP 回 202 時訂單停在 PENDING，結果要等 Kafka／TCC 回來。
     * 【使用】僅 {@code SagaOrchestrator.start} 呼叫；amount＝quantity×price。
     * <pre>
     * TradeOrder.pending(orderId, sagaId, "ACC-001", "BTCUSDT", "BUY", qty, price, false);
     * </pre>
     *
     * @param orderId   訂單 id（呼叫端產生的 UUID）
     * @param sagaId    同一筆交易的 Saga id（同時是 Kafka key 與 TCC 預留票主鍵）
     * @param accountId 下單帳戶（呼叫前已由 {@code AccountLookup.requireExists} 確認存在）
     * @param symbol    商品代號
     * @param side      BUY／SELL
     * @param quantity  數量（不可為 null，否則計算 amount 會 NPE；HTTP 入口已 {@code @NotNull} 驗證）
     * @param price     單價（同上）
     * @param forceFail 是否教學補償路徑（寫入訂單，後續 Confirm 會帶上）
     * @return 狀態 PENDING、createdAt＝現在的新實體
     */
    public static TradeOrder pending(String orderId, String sagaId, String accountId, String symbol,
                                     String side, BigDecimal quantity, BigDecimal price, boolean forceFail) {
        return new TradeOrder(orderId, sagaId, accountId, symbol, side, quantity, price, forceFail);
    }

    /**
     * 【職責】Saga 成功收尾：PENDING → FILLED。
     * 【概念】此時帳戶側預留票已 CONFIRMED（錢已扣）；同一 TX 內 Saga 也轉 COMPLETED。
     * 【使用】僅 {@code OrderSagaEventHandler.onConfirmed}；勿在補償路徑呼叫。
     */
    public void markFilled() {
        this.status = OrderStatus.FILLED;
    }

    /**
     * 【職責】補償：標失敗（不碰帳戶庫）。
     * 【概念】FAILED 是「失敗路徑已正確收尾」，不代表系統出錯；錢是否曾凍結要看預留票。
     * 【使用】僅 {@code OrderMarkFailedAction.compensate}；帳戶還原靠 TCC Cancel。
     */
    public void markFailed() {
        this.status = OrderStatus.FAILED;
    }
}
