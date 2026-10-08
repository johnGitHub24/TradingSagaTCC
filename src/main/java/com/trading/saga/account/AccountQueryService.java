package com.trading.saga.account;

import com.trading.saga.account.domain.Account;
import com.trading.saga.account.dto.AccountResponse;
import com.trading.saga.account.dto.TccReservationResponse;
import com.trading.saga.account.infrastructure.AccountRepository;
import com.trading.saga.account.infrastructure.TccReservationRepository;
import com.trading.saga.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 【職責】帳戶查詢與練習重置；並實作 {@link AccountLookup} 給訂單側啟動前確認存在。
 * 【使用】HTTP 經 {@link AccountController}；編排經 {@link AccountLookup#requireExists}。
 * 【邊界】唯讀查詢用 account TM；reset 只改帳戶庫。
 *
 * <p>【怎麼運作／注入】{@code @Service} + {@code implements AccountLookup} →
 * 同時是查詢服務與 Lookup 埠；Orchestrator 寫介面即可拿到本類。
 */
@Service
public class AccountQueryService implements AccountLookup {

    public static final String SEED_ACCOUNT_ID = "ACC-001";
    public static final BigDecimal SEED_AVAILABLE = new BigDecimal("100000");

    private final AccountRepository accountRepository;
    private final TccReservationRepository reservationRepository;

    /**
     * 【職責】注入帳戶與預留票 Repository（建構子注入；兩者同屬帳戶庫）。
     */
    public AccountQueryService(AccountRepository accountRepository,
                               TccReservationRepository reservationRepository) {
        this.accountRepository = accountRepository;
        this.reservationRepository = reservationRepository;
    }

    /**
     * 【職責】帳戶必須存在，否則拋 404。
     * 【使用】{@code SagaOrchestrator.start} 開頭呼叫；只讀帳戶庫（允許跨庫讀）。
     * <pre>
     * accountLookup.requireExists("ACC-001");
     * </pre>
     *
     * @param accountId 帳戶代號
     */
    @Override
    @Transactional(value = "accountTransactionManager", readOnly = true)
    public void requireExists(String accountId) {
        get(accountId);
    }

    /**
     * 【職責】查詢帳戶餘額 DTO。
     * 【使用】Case ACCOUNT-001；前台顯示 available／frozen。
     *
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
     * 【職責】查詢某 Saga 的 TCC 預留票狀態（Case TCC-001；前台 Dashboard 的 TCC 狀態機）。
     * 【概念】查無票回 {@link TccReservationResponse#none}（200），不拋 404：
     * 「無票」代表 Try 尚未執行或 Try 失敗，本身就是要畫出來的狀態。
     * <pre>
     * accountQueryService.getReservation(sagaId); // exists=true, state=CONFIRMED
     * </pre>
     *
     * @param sagaId 流程 id（預留票主鍵）
     * @return 預留票 DTO；無票時 exists=false
     */
    @Transactional(value = "accountTransactionManager", readOnly = true)
    public TccReservationResponse getReservation(String sagaId) {
        return reservationRepository.findById(sagaId)
                .map(TccReservationResponse::from)
                .orElseGet(() -> TccReservationResponse.none(sagaId));
    }

    /**
     * 【職責】還原種子餘額，方便前台重跑劇情。
     * 【使用】每輪 Demo／Smoke 前：
     * <pre>
     * accountQueryService.reset("ACC-001"); // available=100000, frozen=0
     * </pre>
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
