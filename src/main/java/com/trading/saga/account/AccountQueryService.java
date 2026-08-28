package com.trading.saga.account;

import com.trading.saga.account.domain.Account;
import com.trading.saga.account.dto.AccountResponse;
import com.trading.saga.account.infrastructure.AccountRepository;
import com.trading.saga.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 【職責】帳戶查詢與練習重置；並實作 {@link AccountLookup} 給訂單側啟動前確認存在。
 * 【邊界】唯讀查詢用 account TM；reset 只改帳戶庫。
 */
@Service
public class AccountQueryService implements AccountLookup {

    public static final String SEED_ACCOUNT_ID = "ACC-001";
    public static final BigDecimal SEED_AVAILABLE = new BigDecimal("100000");

    private final AccountRepository accountRepository;

    /**
     * @param accountRepository 帳戶庫
     */
    public AccountQueryService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(value = "accountTransactionManager", readOnly = true)
    public void requireExists(String accountId) {
        get(accountId);
    }

    /**
     * @param accountId 帳戶代號
     * @return DTO
     */
    @Transactional(value = "accountTransactionManager", readOnly = true)
    public AccountResponse get(String accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        return AccountResponse.from(account);
    }

    /**
     * 還原種子餘額，方便前台重跑劇情。
     *
     * @param accountId 帳戶代號
     * @return 重置後 DTO
     */
    @Transactional("accountTransactionManager")
    public AccountResponse reset(String accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        account.resetTo(SEED_AVAILABLE, BigDecimal.ZERO);
        accountRepository.save(account);
        return AccountResponse.from(account);
    }
}
