package com.trading.saga.order.dto;

import com.trading.saga.order.domain.SagaInstance;
import com.trading.saga.order.domain.SagaStatus;
import com.trading.saga.order.domain.SagaStep;

import java.time.Instant;
import java.util.List;

/**
 * 【職責】Saga 查詢 DTO（含步驟時間軸），{@code GET /api/v1/sagas/{sagaId}} 的回應。
 * 【技巧】外層 record 組合 {@link SagaInstance}＋多筆 {@link SagaStep}；內層巢狀 record {@link StepResponse} 只保留顯示所需欄位
 * （不外露 step 的 id／sagaId）。步驟用 {@code stream().map(StepResponse::from).toList()} 轉成不可變 List。
 * 【概念】前台 {@code app.js} 的 {@code pollSaga} 反覆打這支 API，看到 status 為 COMPLETED／COMPENSATED／FAILED 就停止輪詢；
 * steps 讓人看得出流程是在哪一步分岔（成功或補償）。
 * 【使用】由 {@code TradeQueryService.getSaga} 組裝。
 *
 * @param sagaId  流程 id
 * @param orderId 對應訂單 id
 * @param status  Saga 目前狀態（判斷是否結束的依據，見 {@link SagaStatus#isTerminal()}）
 * @param steps   步驟時間軸，依時間由舊到新（順序由 Repository 查詢保證）
 */
public record SagaResponse(
        String sagaId,
        String orderId,
        SagaStatus status,
        List<StepResponse> steps
) {
    /**
     * 【職責】組合 Saga 實例與其步驟。
     * 【邊界】不重新排序 steps，保留呼叫端傳入順序（{@code findBySagaIdOrderByAtAscIdAsc} 已排好）。
     *
     * @param saga  Saga 實例
     * @param steps 該 Saga 的步驟（已排序）
     * @return 對外 DTO
     */
    public static SagaResponse from(SagaInstance saga, List<SagaStep> steps) {
        return new SagaResponse(
                saga.getSagaId(),
                saga.getOrderId(),
                saga.getStatus(),
                steps.stream().map(StepResponse::from).toList()
        );
    }

    /**
     * 【職責】時間軸上的單一步驟（前台 index.html 以 {@code currentSaga.steps} 逐列顯示）。
     *
     * @param name   步驟代號（如 ORDER_CREATED、RESERVE_COMMANDED、CONFIRM_COMMANDED、SAGA_COMPLETED、ORDER_MARK_FAILED）
     * @param detail 補充說明（可能為 null）
     * @param at     發生時間（UTC）
     */
    public record StepResponse(String name, String detail, Instant at) {
        /**
         * 【職責】從步驟實體投影。
         *
         * @param step 步驟實體
         * @return 顯示用 DTO
         */
        public static StepResponse from(SagaStep step) {
            return new StepResponse(step.getName(), step.getDetail(), step.getAt());
        }
    }
}
