package com.trading.saga.saga;

import com.trading.saga.order.domain.OrderStatus;
import com.trading.saga.order.domain.SagaInstance;
import com.trading.saga.order.domain.SagaStatus;
import com.trading.saga.order.domain.TradeOrder;
import com.trading.saga.order.infrastructure.SagaInstanceRepository;
import com.trading.saga.order.infrastructure.SagaStepRepository;
import com.trading.saga.order.infrastructure.TradeOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 【職責】補償動作單元層：與 SAGA-002／TCC-002 同一「訂單 FAILED + Saga COMPENSATED」。
 * 覆蓋 {@link OrderMarkFailedAction#compensate(String)}（訂單庫側補償；由 {@code OrderSagaEventHandler}
 * 在收到 {@code FUNDS_FAILED}／{@code FUNDS_CANCELLED} 時呼叫）。
 * 【技巧】
 * <ul>
 *   <li>{@code @InjectMocks}：Mockito 以建構子把三個 Repository mock 注入被測物件。</li>
 *   <li>Saga／訂單用真的領域物件（{@link SagaInstance#start}、{@link TradeOrder#pending}）建立，
 *       再用 {@code given(findById).willReturn(Optional.of(...))} 交給被測程式；
 *       因為是同一個 Java 實例，補償後可直接讀它們的狀態做斷言。</li>
 *   <li>{@code verify(mock, never())}：驗證某方法「一次都沒被呼叫」，用來證明終態時提早 return。</li>
 * </ul>
 * 【概念】補償不是回滾帳戶庫：帳戶的錢由 TCC Cancel 自己還原；本動作只把訂單庫收尾成
 * 「訂單 FAILED、Saga COMPENSATING → COMPENSATED」，並記一筆 {@code ORDER_MARK_FAILED} 步驟。
 * Saga 已是終態（COMPLETED／COMPENSATED／FAILED）時直接 return，讓 Kafka 重送同一事件也無害（冪等）。
 */
// 啟用 Mockito：處理 @Mock／@InjectMocks，並在每個 Test 結束檢查是否有多餘的 stub（嚴格模式）
@ExtendWith(MockitoExtension.class)
// 測試報告／IDE 上顯示的名稱
@DisplayName("OrderMarkFailedAction unit")
class OrderMarkFailedActionTest {

    // Saga 主檔 Repository（訂單庫 saga_instances）：假的，findById 回傳什麼由各 Test 的 given(...) 決定
    @Mock
    private SagaInstanceRepository sagaInstanceRepository;
    // 訂單 Repository（訂單庫 trade_orders）：假的，用來提供待補償的訂單，或驗證「沒被查」
    @Mock
    private TradeOrderRepository orderRepository;
    // Saga 步驟 Repository（流水帳）：假的，只驗證補償時有寫入一筆步驟
    @Mock
    private SagaStepRepository sagaStepRepository;
    // 被測物件：Mockito 依型別把上面三個 mock 傳入 OrderMarkFailedAction 的建構子
    @InjectMocks
    private OrderMarkFailedAction action;

    /** SAGA-002／TCC-002：Given 進行中的 Saga（ACCOUNT_TRYING）與 PENDING 訂單，When compensate，Then 訂單 FAILED、Saga COMPENSATED、寫一筆步驟。 */
    @Test
    @DisplayName("SAGA-002 / TCC-002: compensate marks order FAILED and saga COMPENSATED")
    void compensate_marksFailed() {
        // ===== Given：準備一筆進行中的 Saga 與它的訂單 =====
        // 建立 Saga：sagaId=saga-2、對應 orderId=order-2，初始狀態 STARTED
        SagaInstance saga = SagaInstance.start("saga-2", "order-2");
        // 模擬 SagaOrchestrator.start 已把 Saga 推到 ACCOUNT_TRYING（等帳戶 Try 結果）；SAGA-002 的 FUNDS_FAILED 就在這個階段到達
        saga.transitionTo(SagaStatus.ACCOUNT_TRYING);
        // 建立 PENDING 訂單：數量 1 × 價格 999999（對齊 SAGA-002 餘額不足 fixture），forceFail=false
        TradeOrder order = TradeOrder.pending(
                "order-2", "saga-2", "ACC-001", "BTCUSDT", "BUY",
                BigDecimal.ONE, new BigDecimal("999999"), false);
        // 依 sagaId 查 Saga 時，回傳上面那個實例
        given(sagaInstanceRepository.findById("saga-2")).willReturn(Optional.of(saga));
        // 被測程式會用 saga.getOrderId()（＝order-2）查訂單，回傳上面那個訂單實例
        given(orderRepository.findById("order-2")).willReturn(Optional.of(order));

        // ===== When：執行補償 =====
        // 內部流程：查 Saga → 非終態 → 查訂單 → markFailed → COMPENSATING → COMPENSATED → 寫步驟
        action.compensate("saga-2");

        // ===== Then①：訂單已標失敗 =====
        // 訂單從 PENDING 變 FAILED（不碰帳戶庫；帳戶還原是 TCC Cancel 的工作）
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        // ===== Then②：Saga 已收成補償終態 =====
        // 經 COMPENSATING 中間態後落在 COMPENSATED；同一方法內連跳兩步，外部查詢幾乎看不到 COMPENSATING
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATED);
        // ===== Then③：步驟流水帳有寫一筆 =====
        // 驗證 save 被呼叫剛好 1 次（內容為 ORDER_MARK_FAILED／"order FAILED"）；any() 表示這裡不檢查步驟內容
        verify(sagaStepRepository).save(any());
    }

    /** 冪等：Given Saga 已是終態 COMPENSATED，When 再次 compensate，Then 直接 return、不去查訂單。 */
    @Test
    @DisplayName("compensate is idempotent on terminal saga")
    void compensate_terminal_noop() {
        // ===== Given：準備一筆已補償完成（終態）的 Saga =====
        // 建立 Saga：sagaId=saga-9、orderId=order-9，初始 STARTED
        SagaInstance saga = SagaInstance.start("saga-9", "order-9");
        // 依合法狀態機一步步走：STARTED → ACCOUNT_TRYING
        saga.transitionTo(SagaStatus.ACCOUNT_TRYING);
        // ACCOUNT_TRYING → COMPENSATING（任何非終態都可轉補償）
        saga.transitionTo(SagaStatus.COMPENSATING);
        // COMPENSATING → COMPENSATED（終態，isTerminal() 為 true）
        saga.transitionTo(SagaStatus.COMPENSATED);
        // 依 sagaId 查 Saga 時回傳這個終態實例；刻意不 stub orderRepository，因為預期不會被呼叫
        given(sagaInstanceRepository.findById("saga-9")).willReturn(Optional.of(saga));

        // ===== When：模擬 Kafka 重送 FUNDS_FAILED，第二次呼叫補償 =====
        // 看到終態應直接 return，不丟例外（若再 transitionTo 會因終態不可轉而拋 IllegalStateException）
        action.compensate("saga-9");

        // ===== Then：確認提早結束 =====
        // never()＝呼叫次數必須為 0；any() 表示不論傳哪個 orderId 都算。證明沒有走到「查訂單 → 標 FAILED」那段
        verify(orderRepository, never()).findById(any());
    }
}
