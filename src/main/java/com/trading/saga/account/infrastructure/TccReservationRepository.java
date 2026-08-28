package com.trading.saga.account.infrastructure;

import com.trading.saga.account.domain.TccReservation;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 【職責】帳戶庫 tcc_reservations 存取。
 */
public interface TccReservationRepository extends JpaRepository<TccReservation, String> {
}
