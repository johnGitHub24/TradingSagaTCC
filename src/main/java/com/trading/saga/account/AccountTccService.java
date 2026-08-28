package com.trading.saga.account;

import com.trading.saga.account.domain.Account;
import com.trading.saga.account.domain.InsufficientFundsException;
import com.trading.saga.account.domain.TccReservation;
import com.trading.saga.account.domain.TccState;
import com.trading.saga.account.infrastructure.AccountRepository;
import com.trading.saga.account.infrastructure.TccReservationRepository;
import com.trading.saga.common.ResourceNotFoundException;
import com.trading.saga.expansion.TccResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 【職責】帳戶庫內的 TCC：Try 凍結、Confirm 扣款、Cancel 釋放。
 * 【技巧】以 sagaId 當預留主鍵做冪等；forceFail 在 Confirm 改走 Cancel（教學補償）。
 * 【概念】這是「單資源兩階段」；跨庫成敗由 Saga 聽 Kafka 事件決定。
 * 【邊界】只用 accountTransactionManager；不寫訂單表。
 */
@Service
public class AccountTccService implements TccResource {

    private final AccountRepository accountRepository;
    private final TccReservationRepository reservationRepository;

    /**
     * @param accountRepository     帳戶
     * @param reservationRepository 預留票
     */
    public AccountTccService(AccountRepository accountRepository,
                             TccReservationRepository reservationRepository) {
        this.accountRepository = accountRepository;
        this.reservationRepository = reservationRepository;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional("accountTransactionManager")
    public boolean tryReserve(String sagaId, String accountId, BigDecimal amount) {
        Optional<TccReservation> existing = reservationRepository.findById(sagaId);
        if (existing.isPresent()) {
            TccState state = existing.get().getState();
            return state == TccState.TRYING || state == TccState.CONFIRMED;
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        try {
            account.tryReserve(amount);
        } catch (InsufficientFundsException ex) {
            return false;
        }
        reservationRepository.save(TccReservation.trying(sagaId, accountId, amount));
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional("accountTransactionManager")
    public boolean confirm(String sagaId, boolean forceFail) {
        if (forceFail) {
            cancel(sagaId);
            return false;
        }
        TccReservation reservation = reservationRepository.findById(sagaId)
                .orElseThrow(() -> new ResourceNotFoundException("TCC reservation not found: " + sagaId));
        if (reservation.getState() == TccState.CONFIRMED) {
            return true;
        }
        Account account = accountRepository.findById(reservation.getAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + reservation.getAccountId()));
        account.confirm(reservation.getAmount());
        reservation.markConfirmed();
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional("accountTransactionManager")
    public void cancel(String sagaId) {
        Optional<TccReservation> optional = reservationRepository.findById(sagaId);
        if (optional.isEmpty()) {
            return;
        }
        TccReservation reservation = optional.get();
        if (reservation.getState() == TccState.CANCELLED) {
            return;
        }
        Account account = accountRepository.findById(reservation.getAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + reservation.getAccountId()));
        if (reservation.markCancelled()) {
            account.cancel(reservation.getAmount());
        }
    }
}
