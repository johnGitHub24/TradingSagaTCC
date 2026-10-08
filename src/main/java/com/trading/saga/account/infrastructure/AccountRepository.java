package com.trading.saga.account.infrastructure;

import com.trading.saga.account.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 【職責】帳戶庫 accounts 存取。只做查詢／寫入。
 * <p>【技巧】繼承 {@link JpaRepository}（主鍵型別 String＝accountId），沒有自訂方法；
 * <br>餘額變更寫在 {@link Account} 實體方法（tryReserve／confirm／cancel），Service 取出實體呼叫後由 dirty checking 寫回。
 * <p>【概念】綁定 accountTransactionManager（見 {@code AccountDataSourceConfig}）；訂單側只能經
 * <br>{@code AccountLookup}（唯讀）間接確認帳戶存在，不直接注入本介面。
 * <p>【使用】{@code AccountTccService}（TCC 三步）、{@code AccountQueryService}（查詢／reset）、
 * <br>{@code AccountDataSeeder}（啟動時建立種子帳戶 ACC-001）。
 * <p>【邊界】禁止放 TCC 規則。
 */
public interface AccountRepository extends JpaRepository<Account, String> {
}
