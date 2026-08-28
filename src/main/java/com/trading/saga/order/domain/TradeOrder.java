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
 * 【職責】訂單庫的交易單；金額在下單當下算死，後續只改 status。
 * 【概念】PENDING 表示 Saga 進行中；FILLED／FAILED 是終態。帳戶餘額不在這張表。
 */
@Entity
@Table(name = "trade_orders")
@Getter
@NoArgsConstructor
public class TradeOrder {

    @Id
    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "saga_id", nullable = false, length = 64)
    private String sagaId;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false, length = 8)
    private String side;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "force_fail", nullable = false)
    private boolean forceFail;

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
     * 建立 PENDING 訂單。
     */
    public static TradeOrder pending(String orderId, String sagaId, String accountId, String symbol,
                                     String side, BigDecimal quantity, BigDecimal price, boolean forceFail) {
        return new TradeOrder(orderId, sagaId, accountId, symbol, side, quantity, price, forceFail);
    }

    /**
     * Saga 成功收尾。
     */
    public void markFilled() {
        this.status = OrderStatus.FILLED;
    }

    /**
     * 補償：標失敗（不碰帳戶庫）。
     */
    public void markFailed() {
        this.status = OrderStatus.FAILED;
    }
}
