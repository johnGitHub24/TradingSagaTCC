package com.trading.saga.order;

import com.trading.saga.common.ResourceNotFoundException;
import com.trading.saga.order.infrastructure.SagaInstanceRepository;
import com.trading.saga.order.infrastructure.SagaStepRepository;
import com.trading.saga.order.infrastructure.TradeOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 【職責】查單單元層，與 TRADE-001 成對（不存在 → 404 語意例外）。
 * <br>覆蓋 {@link TradeQueryService#getOrder}（Service 層）；整合層對照為
 * <br>{@code TradeSagaIntegrationTest} 的 {@code GET /api/v1/trades/missing-order → 404}。
 * <p>【技巧】Mockito 假 Repository＋{@code @InjectMocks} 自動以建構子注入；
 * <br>以 {@code given(...).willReturn(Optional.empty())} 模擬「查無資料」，
 * <br>再用 {@code assertThatThrownBy} 同時斷言例外型別與訊息內容。
 * <p>【概念】Service 只負責丟出「找不到」的領域例外 {@link ResourceNotFoundException}，
 * <br>不決定 HTTP 狀態碼；轉成 404 JSON 是 {@code GlobalExceptionHandler} 的工作（見其單元測試）。
 * <br>這樣分層後，本測試不必啟動 Web 層也能驗證 TRADE-001 的業務語意。
 */
// 啟用 Mockito：自動建立 @Mock 欄位並注入 @InjectMocks；嚴格模式下未使用的 stub 會讓測試失敗
@ExtendWith(MockitoExtension.class)
// 測試報告／IDE 上顯示的名稱
@DisplayName("TradeQueryService unit")
class TradeQueryServiceTest {

    // 訂單 Repository（訂單庫）：本測試唯一會被呼叫的依賴，用來模擬 findById 查無資料
    @Mock
    private TradeOrderRepository orderRepository;
    // Saga 主檔 Repository：getOrder 用不到，但建構子需要，故仍提供假物件
    @Mock
    private SagaInstanceRepository sagaInstanceRepository;
    // Saga 步驟 Repository：同上，只為滿足建構子參數
    @Mock
    private SagaStepRepository sagaStepRepository;
    // 被測物件：Mockito 依型別把上面三個 mock 塞進 TradeQueryService 的建構子
    @InjectMocks
    private TradeQueryService queryService;

    /**
     * TRADE-001：Given 訂單庫查不到 "missing"，When 呼叫 getOrder，
     * <br>Then 丟 {@link ResourceNotFoundException} 且訊息含該訂單 id。
     */
    @Test
    @DisplayName("TRADE-001: get unknown order → ResourceNotFoundException")
    void getOrder_unknown_throws() {
        // ===== Given：訂單庫查無此單 =====
        // findById("missing") 回傳空 Optional；用具體值 "missing" 而非 any()，確保 Service 傳入的就是呼叫端給的 id
        given(orderRepository.findById("missing")).willReturn(Optional.empty());

        // ===== When／Then：查單必須丟「找不到」領域例外 =====
        // getOrder 對空 Optional 呼叫 orElseThrow；lambda 讓 AssertJ 執行並捕捉例外（沒丟例外則測試失敗）
        assertThatThrownBy(() -> queryService.getOrder("missing"))
                // 型別必須是 ResourceNotFoundException，GlobalExceptionHandler 才會轉成 404
                .isInstanceOf(ResourceNotFoundException.class)
                // 實際訊息為 "Order not found: missing"；只斷言含 id，避免綁死前綴文字
                .hasMessageContaining("missing");
    }
}
