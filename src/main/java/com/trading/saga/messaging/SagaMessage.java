package com.trading.saga.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 【職責】跨庫 Saga 的 Kafka／Outbox 信封（命令與事件同一形狀）。
 * 【技巧】Jackson 無型別標頭也可反序列化；key 用 sagaId。
 * 【概念】訂單庫只產 command；帳戶庫只產 event。兩邊都不直寫對方的表。
 */
public record SagaMessage(
        String messageId,
        String sagaId,
        String orderId,
        String accountId,
        String type,
        BigDecimal amount,
        String symbol,
        boolean forceFail,
        Instant occurredAt
) {

    /**
     * 組一則新訊息。
     */
    public static SagaMessage of(String sagaId, String orderId, String accountId, String type,
                                 BigDecimal amount, String symbol, boolean forceFail) {
        return new SagaMessage(
                UUID.randomUUID().toString(),
                sagaId,
                orderId,
                accountId,
                type,
                amount,
                symbol,
                forceFail,
                Instant.now()
        );
    }
}
