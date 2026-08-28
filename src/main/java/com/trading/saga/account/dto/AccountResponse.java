package com.trading.saga.account.dto;

import com.trading.saga.account.domain.Account;

import java.math.BigDecimal;

/**
 * 【職責】帳戶餘額對外 DTO。
 */
public record AccountResponse(
        String accountId,
        BigDecimal available,
        BigDecimal frozen,
        BigDecimal total
) {
    /**
     * 從實體投影。
     */
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getAccountId(),
                account.getAvailable(),
                account.getFrozen(),
                account.total()
        );
    }
}
