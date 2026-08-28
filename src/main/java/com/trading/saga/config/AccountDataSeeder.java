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
 * 【邊界】只寫帳戶庫。
 */
@Configuration
public class AccountDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(AccountDataSeeder.class);

    /**
     * 空庫時插入種子帳戶。
     */
    @Bean
    public CommandLineRunner seedAccount(AccountSeedService seedService) {
        return args -> seedService.ensureSeed();
    }

    /**
     * 獨立 bean 以便掛 account 交易。
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
         * 若無 ACC-001 則建立。
         */
        @Transactional("accountTransactionManager")
        public void ensureSeed() {
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
