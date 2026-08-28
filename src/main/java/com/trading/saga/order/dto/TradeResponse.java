package com.trading.saga.order.dto;

import com.trading.saga.order.domain.OrderStatus;
import com.trading.saga.order.domain.TradeOrder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 【職責】訂單對外 DTO。
 */
public record TradeResponse(
        String orderId,
        String sagaId,
        String accountId,
        String symbol,
        String side,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal amount,
        OrderStatus status,
        boolean forceFail,
        Instant createdAt
) {
    /**
     * 從實體投影。
     */
    public static TradeResponse from(TradeOrder order) {
        return new TradeResponse(
                order.getOrderId(),
                order.getSagaId(),
                order.getAccountId(),
                order.getSymbol(),
                order.getSide(),
                order.getQuantity(),
                order.getPrice(),
                order.getAmount(),
                order.getStatus(),
                order.isForceFail(),
                order.getCreatedAt()
        );
    }
}
