package com.trading.saga.order.dto;

import com.trading.saga.account.dto.AccountResponse;
import com.trading.saga.messaging.EventLogEntry;

import java.util.List;

/**
 * 【職責】前台一次拉取帳戶／訂單／Kafka 軌跡。
 */
public record DemoStateResponse(
        AccountResponse account,
        List<TradeResponse> orders,
        List<EventLogEntry> events
) {
}
