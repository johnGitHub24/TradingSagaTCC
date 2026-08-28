package com.trading.saga.account.domain;

import java.math.BigDecimal;

/**
 * 【職責】帳戶資金不足時的領域例外，供 Try 階段拒絕預留。
 * 【技巧】帶 accountId／requested／available，方便 API 與測試斷言同一語意。
 * 【概念】這不是 HTTP；Controller／Handler 才決定 202（Saga 補償）或 422。
 * 【邊界】不修改帳戶餘額；丟出前呼叫端應保持不變式。
 */
public class InsufficientFundsException extends RuntimeException {

    private final String accountId;
    private final BigDecimal requested;
    private final BigDecimal available;

    /**
     * @param accountId 帳戶代號
     * @param requested 欲預留金額
     * @param available 當下可用餘額
     */
    public InsufficientFundsException(String accountId, BigDecimal requested, BigDecimal available) {
        super("Insufficient funds for " + accountId + ": requested=" + requested + " available=" + available);
        this.accountId = accountId;
        this.requested = requested;
        this.available = available;
    }

    public String getAccountId() {
        return accountId;
    }

    public BigDecimal getRequested() {
        return requested;
    }

    public BigDecimal getAvailable() {
        return available;
    }
}
