package com.trading.saga.order.dto;

import com.trading.saga.order.domain.SagaInstance;
import com.trading.saga.order.domain.SagaStatus;
import com.trading.saga.order.domain.SagaStep;

import java.time.Instant;
import java.util.List;

/**
 * 【職責】Saga 查詢 DTO（含步驟時間軸）。
 */
public record SagaResponse(
        String sagaId,
        String orderId,
        SagaStatus status,
        List<StepResponse> steps
) {
    /**
     * 組合實例與步驟。
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
     * 單步。
     */
    public record StepResponse(String name, String detail, Instant at) {
        /**
         * 從實體投影。
         */
        public static StepResponse from(SagaStep step) {
            return new StepResponse(step.getName(), step.getDetail(), step.getAt());
        }
    }
}
