package com.trading.saga.account.infrastructure;

import com.trading.saga.account.domain.TccReservation;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 【職責】帳戶庫 tcc_reservations（TCC 預留票）存取。只做查詢／寫入，不含 TCC 規則。
 * 【技巧】繼承 {@link JpaRepository}（主鍵型別 String＝sagaId），沒有自訂方法：
 * 冪等判斷只需要 {@code findById(sagaId)}——因為主鍵就是冪等 Key。
 * 【概念】本套件 {@code account.infrastructure} 由 {@code AccountDataSourceConfig} 的 {@code @EnableJpaRepositories}
 * 綁到 accountEntityManagerFactory／accountTransactionManager；訂單側程式不可注入本介面（兩庫只靠 Kafka 溝通）。
 * 【使用】僅 {@code AccountTccService}：Try 先 {@code findById} 檢查是否已有票、成功再 {@code save(TccReservation.trying(...))}；
 * Confirm／Cancel {@code findById} 後改狀態，由 dirty checking 寫回。
 */
public interface TccReservationRepository extends JpaRepository<TccReservation, String> {
}
