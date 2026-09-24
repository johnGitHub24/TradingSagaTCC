package com.trading.saga.account;

import com.trading.saga.account.dto.AccountResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 【職責】帳戶查詢與練習重置。
 * 【使用】前台顯示餘額／「還原種子」按鈕；Case ACCOUNT-001。
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountQueryService accountQueryService;

    /**
     * 【職責】注入查詢服務。
     */
    public AccountController(AccountQueryService accountQueryService) {
        this.accountQueryService = accountQueryService;
    }

    /**
     * 【職責】查餘額。
     * 【使用】{@code GET /api/v1/accounts/ACC-001} → available／frozen／total。
     *
     * @param accountId 帳戶 id（種子為 ACC-001）
     */
    @GetMapping("/{accountId}")
    public AccountResponse get(@PathVariable String accountId) {
        return accountQueryService.get(accountId);
    }

    /**
     * 【職責】還原種子餘額（available=100000, frozen=0）。
     * 【使用】每跑一輪 Demo／Smoke 前建議先 reset。
     * <pre>
     * POST /api/v1/accounts/ACC-001/reset
     * </pre>
     *
     * @param accountId 帳戶 id
     */
    @PostMapping("/{accountId}/reset")
    public AccountResponse reset(@PathVariable String accountId) {
        return accountQueryService.reset(accountId);
    }
}
