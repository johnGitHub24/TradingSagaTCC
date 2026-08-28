package com.trading.saga.account;

import com.trading.saga.account.dto.AccountResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 【職責】帳戶查詢與練習重置。
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountQueryService accountQueryService;

    /**
     * @param accountQueryService 查詢服務
     */
    public AccountController(AccountQueryService accountQueryService) {
        this.accountQueryService = accountQueryService;
    }

    /**
     * 查餘額。
     */
    @GetMapping("/{accountId}")
    public AccountResponse get(@PathVariable String accountId) {
        return accountQueryService.get(accountId);
    }

    /**
     * 還原種子餘額。
     */
    @PostMapping("/{accountId}/reset")
    public AccountResponse reset(@PathVariable String accountId) {
        return accountQueryService.reset(accountId);
    }
}
