package com.trading.saga.messaging;

/**
 * 【職責】Kafka／Outbox 共用的訊息型別常數。
 */
public final class SagaMessageTypes {

    public static final String RESERVE_FUNDS = "RESERVE_FUNDS";
    public static final String CONFIRM_FUNDS = "CONFIRM_FUNDS";
    public static final String CANCEL_FUNDS = "CANCEL_FUNDS";
    public static final String FUNDS_RESERVED = "FUNDS_RESERVED";
    public static final String FUNDS_CONFIRMED = "FUNDS_CONFIRMED";
    public static final String FUNDS_FAILED = "FUNDS_FAILED";
    public static final String FUNDS_CANCELLED = "FUNDS_CANCELLED";

    private SagaMessageTypes() {
    }
}
