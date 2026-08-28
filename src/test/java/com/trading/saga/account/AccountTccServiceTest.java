package com.trading.saga.account;

import com.trading.saga.account.domain.Account;
import com.trading.saga.account.domain.TccReservation;
import com.trading.saga.account.domain.TccState;
import com.trading.saga.account.infrastructure.AccountRepository;
import com.trading.saga.account.infrastructure.TccReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 【職責】{@link AccountTccService} 單元層：與 SAGA-001／SAGA-002／TCC-002 同一 TCC 契約。
 * 【技巧】Mock 兩個帳戶庫 Repository，不啟動 Kafka／訂單庫。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountTccService unit (TCC)")
class AccountTccServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TccReservationRepository reservationRepository;
    @InjectMocks
    private AccountTccService tccService;

    private Account account;

    @BeforeEach
    void setUp() {
        account = Account.builder()
                .accountId("ACC-001")
                .available(new BigDecimal("100000"))
                .frozen(BigDecimal.ZERO)
                .build();
    }

    @Test
    @DisplayName("SAGA-001: tryReserve 10000 succeeds and persists TRYING reservation")
    void tryReserve_success() {
        given(reservationRepository.findById("saga-1")).willReturn(Optional.empty());
        given(accountRepository.findById("ACC-001")).willReturn(Optional.of(account));

        boolean ok = tccService.tryReserve("saga-1", "ACC-001", new BigDecimal("10000"));

        assertThat(ok).isTrue();
        assertThat(account.getFrozen()).isEqualByComparingTo("10000");
        ArgumentCaptor<TccReservation> captor = ArgumentCaptor.forClass(TccReservation.class);
        verify(reservationRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(TccState.TRYING);
    }

    @Test
    @DisplayName("SAGA-002: tryReserve 999999 returns false and does not save reservation")
    void tryReserve_insufficient_returnsFalse() {
        given(reservationRepository.findById("saga-2")).willReturn(Optional.empty());
        given(accountRepository.findById("ACC-001")).willReturn(Optional.of(account));

        boolean ok = tccService.tryReserve("saga-2", "ACC-001", new BigDecimal("999999"));

        assertThat(ok).isFalse();
        assertThat(account.getAvailable()).isEqualByComparingTo("100000");
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("TCC-002: confirm(forceFail=true) cancels and restores available")
    void confirm_forceFail_cancels() {
        account.tryReserve(new BigDecimal("10000"));
        TccReservation reservation = TccReservation.trying("saga-3", "ACC-001", new BigDecimal("10000"));
        given(reservationRepository.findById("saga-3")).willReturn(Optional.of(reservation));
        given(accountRepository.findById("ACC-001")).willReturn(Optional.of(account));

        boolean ok = tccService.confirm("saga-3", true);

        assertThat(ok).isFalse();
        assertThat(account.getAvailable()).isEqualByComparingTo("100000");
        assertThat(account.getFrozen()).isEqualByComparingTo("0");
        assertThat(reservation.getState()).isEqualTo(TccState.CANCELLED);
    }

    @Test
    @DisplayName("SAGA-001: confirm(forceFail=false) deducts frozen")
    void confirm_success() {
        account.tryReserve(new BigDecimal("10000"));
        TccReservation reservation = TccReservation.trying("saga-1", "ACC-001", new BigDecimal("10000"));
        given(reservationRepository.findById("saga-1")).willReturn(Optional.of(reservation));
        given(accountRepository.findById("ACC-001")).willReturn(Optional.of(account));

        boolean ok = tccService.confirm("saga-1", false);

        assertThat(ok).isTrue();
        assertThat(account.getAvailable()).isEqualByComparingTo("90000");
        assertThat(account.getFrozen()).isEqualByComparingTo("0");
        assertThat(reservation.getState()).isEqualTo(TccState.CONFIRMED);
    }
}
