package com.trading.saga.account.infrastructure;

import com.trading.saga.account.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 【職責】帳戶庫 accounts 存取。
 * 【邊界】禁止放 TCC 規則。
 */
public interface AccountRepository extends JpaRepository<Account, String> {
}
