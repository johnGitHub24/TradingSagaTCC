package com.trading.saga.config;

import com.trading.saga.account.AccountQueryService;
import com.trading.saga.account.domain.Account;
import com.trading.saga.account.infrastructure.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 【職責】種子帳戶 ACC-001／100000，供前台三條劇情。
 * 【技巧】{@link CommandLineRunner} 在 Context 啟動完成、{@code ApplicationReadyEvent} 之前執行；
 *         帳號與金額取自 {@link AccountQueryService#SEED_ACCOUNT_ID}／{@link AccountQueryService#SEED_AVAILABLE}，
 *         與 {@code POST /api/v1/accounts/{id}/reset}、{@code GET /api/v1/demo/state} 共用同一組常數。
 * 【概念】H2 是記憶體庫（{@code jdbc:h2:mem:accountdb}），每次重啟都是空庫，
 *         所以每次 bootRun 都要重新種一次；若沒種，Demo 狀態 API 會回 404。
 * 【邊界】只寫帳戶庫（{@code accountTransactionManager}），不碰訂單庫；無條件生效（無 profile 開關）。
 */
@Configuration
public class AccountDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(AccountDataSeeder.class);

    /**
     * 【職責】啟動時觸發種子寫入。
     * 【技巧】Lambda 只轉呼叫 {@link AccountSeedService#ensureSeed()}，交易邊界留在另一個 Bean。
     *
     * @param seedService 帶帳戶庫交易的種子服務
     * @return 啟動時執行一次的 runner
     */
    @Bean
    public CommandLineRunner seedAccount(AccountSeedService seedService) {
        return args -> seedService.ensureSeed();
    }

    /**
     * 【職責】實際寫入種子帳戶，並掛 {@code accountTransactionManager}。
     * 【技巧】獨立 bean 以便掛 account 交易：{@code @Transactional} 靠 Spring 代理生效，
     *         若把交易方法放在外層類別、再由 runner Lambda 以 {@code this} 內部呼叫（self-invocation），
     *         呼叫不經代理，交易註記等於沒寫。
     * 【概念】雙庫時必須指名交易管理器；不指名會落到 {@code @Primary} 的訂單庫交易，寫帳戶表就用錯庫的交易。
     */
    @Configuration
    public static class AccountSeedService {

        private final AccountRepository accountRepository;

        /**
         * @param accountRepository 帳戶庫
         */
        public AccountSeedService(AccountRepository accountRepository) {
            this.accountRepository = accountRepository;
        }

        /**
         * 【職責】若無 ACC-001 則建立（available=100000、frozen=0）；已存在則只記 log。
         * 【概念】冪等：重複執行不會重複插入，也不會把已被劇情改動的餘額蓋回 100000
         *         （要還原請用 reset API）。
         */
        @Transactional("accountTransactionManager")
        public void ensureSeed() {
            // 以主鍵判斷而非「表是否為空」：只保證種子帳戶存在，不影響其他帳戶
            if (accountRepository.existsById(AccountQueryService.SEED_ACCOUNT_ID)) {
                log.info("AccountDataSeeder: {} already present", AccountQueryService.SEED_ACCOUNT_ID);
                return;
            }
            accountRepository.save(Account.builder()
                    .accountId(AccountQueryService.SEED_ACCOUNT_ID)
                    .available(AccountQueryService.SEED_AVAILABLE)
                    .frozen(BigDecimal.ZERO)
                    .build());
            log.info("AccountDataSeeder: inserted {}", AccountQueryService.SEED_ACCOUNT_ID);
        }
    }
}
