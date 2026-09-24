package com.trading.saga.account;

import com.trading.saga.account.domain.Account;
import com.trading.saga.account.domain.TccReservation;
import com.trading.saga.account.domain.TccState;
import com.trading.saga.account.infrastructure.AccountRepository;
import com.trading.saga.account.infrastructure.TccReservationRepository;
import com.trading.saga.order.dto.TradeRequest;
import com.trading.saga.support.SagaTestFixtures;
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
 * 【技巧】金額來自 {@code docs/test-data/trade/*.json}；Mock 帳戶庫 Repository。
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

    private static BigDecimal amountOf(String caseId) {
        TradeRequest req = SagaTestFixtures.loadDto("trade", caseId, TradeRequest.class);
        return req.quantity().multiply(req.price());
    }

    @Test
    @DisplayName("SAGA-001: tryReserve fixture amount succeeds and persists TRYING")
    void tryReserve_success() {
        BigDecimal amount = amountOf("SAGA-001-SUCCESS");
        given(reservationRepository.findById("saga-1")).willReturn(Optional.empty());
        given(accountRepository.findById("ACC-001")).willReturn(Optional.of(account));

        boolean ok = tccService.tryReserve("saga-1", "ACC-001", amount);

        assertThat(ok).isTrue();
        assertThat(account.getFrozen()).isEqualByComparingTo(amount);
        ArgumentCaptor<TccReservation> captor = ArgumentCaptor.forClass(TccReservation.class);
        verify(reservationRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(TccState.TRYING);
    }

    @Test
    @DisplayName("SAGA-002: tryReserve insufficient fixture returns false")
    void tryReserve_insufficient_returnsFalse() {
        BigDecimal amount = amountOf("SAGA-002-INSUFFICIENT");
        given(reservationRepository.findById("saga-2")).willReturn(Optional.empty());
        given(accountRepository.findById("ACC-001")).willReturn(Optional.of(account));

        boolean ok = tccService.tryReserve("saga-2", "ACC-001", amount);

        assertThat(ok).isFalse();
        assertThat(account.getAvailable()).isEqualByComparingTo("100000");
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("TCC-002: confirm(forceFail=true) cancels and restores available")
    void confirm_forceFail_cancels() {
        BigDecimal amount = amountOf("TCC-002-FORCE-FAIL");
        account.tryReserve(amount);
        TccReservation reservation = TccReservation.trying("saga-3", "ACC-001", amount);
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
        BigDecimal amount = amountOf("SAGA-001-SUCCESS");
        account.tryReserve(amount);
        TccReservation reservation = TccReservation.trying("saga-1", "ACC-001", amount);
        given(reservationRepository.findById("saga-1")).willReturn(Optional.of(reservation));
        given(accountRepository.findById("ACC-001")).willReturn(Optional.of(account));

        boolean ok = tccService.confirm("saga-1", false);

        assertThat(ok).isTrue();
        assertThat(account.getAvailable()).isEqualByComparingTo("90000");
        assertThat(account.getFrozen()).isEqualByComparingTo("0");
        assertThat(reservation.getState()).isEqualTo(TccState.CONFIRMED);
    }
}
