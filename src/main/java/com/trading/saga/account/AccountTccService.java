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
 * 【技巧】以 sagaId 當預留票主鍵做冪等；forceFail 在 Confirm 改走 Cancel（教學補償）。
 * 【概念】這是「單資源兩階段」；跨庫成敗由 Saga 聽 Kafka 事件決定。
 * 【概念·預留票】{@code tcc_reservations} 一列＝一張預留票（PK＝sagaId）：
 * 證明「這筆 Saga 在帳戶側預留了多少、走到 TRYING／CONFIRMED／CANCELLED」。
 * 不是訂單；重送同 sagaId 看到票已存在就不再二次扣 available。
 * 【使用】實作 {@link TccResource}；由 {@code AccountCommandHandler} 呼叫，勿從 HTTP Controller 直呼。
 * 【邊界】只用 accountTransactionManager；不寫訂單表。
 *
 * <p>【怎麼運作／注入】{@code @Service} + {@code implements TccResource} →
 * 登錄成 TCC Bean；{@link com.trading.saga.messaging.AccountCommandHandler} 寫介面即可拿到本類。
 */
@Service
public class AccountTccService implements TccResource {

    private final AccountRepository accountRepository;
    private final TccReservationRepository reservationRepository;

    /**
     * 【職責】注入帳戶庫 Repository（建構子注入）。
     * 【使用】Spring 建構；測試可用 Mockito 注入假 Repository。
     *
     * @param accountRepository     帳戶表
     * @param reservationRepository TCC 預留票表（{@code tcc_reservations}；PK＝sagaId＝冪等 Key）
     */
    public AccountTccService(AccountRepository accountRepository,
                             TccReservationRepository reservationRepository) {
        this.accountRepository = accountRepository;
        this.reservationRepository = reservationRepository;
    }

    /**
     * 【職責】Try：從 available 轉入 frozen，並寫入預留票。
     * 【技巧】{@code findById(sagaId)} 已存在則不重複扣款（冪等 Key＝sagaId）。
     * 【概念】回 false 表示「這步失敗、應補償」，不是丟例外給 Kafka。
     * 【使用】對應 Case SAGA-001（成功）／SAGA-002（不足回 false）。
     * <pre>
     * // SAGA-001
     * assertTrue(tcc.tryReserve(sagaId, "ACC-001", new BigDecimal("10000")));
     * // SAGA-002
     * assertFalse(tcc.tryReserve(sagaId, "ACC-001", new BigDecimal("999999")));
     * </pre>
     *
     * @param sagaId    冪等 Key（tcc_reservations 主鍵）
     * @param accountId 帳戶
     * @param amount    金額
     * @return true 已凍結；false 餘額不足（未寫預留列）
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
     * 【職責】Confirm：消耗凍結（真正扣款）；或 forceFail 改 Cancel。
     * 【技巧】已 CONFIRMED 再呼叫回 true；forceFail 先 {@link #cancel} 再回 false。
     * 【概念】Confirm 成功後 total＝available+frozen 會下降。
     * 【使用】對應 Case SAGA-001（forceFail=false）／TCC-002（forceFail=true）。
     * <pre>
     * tcc.tryReserve(sagaId, "ACC-001", amount);
     * tcc.confirm(sagaId, false); // → CONFIRMED，扣款
     * tcc.confirm(sagaId, true);  // → Cancel，餘額還原，回 false
     * </pre>
     *
     * @param sagaId    與 Try 相同
     * @param forceFail 教學開關：true＝故意補償
     * @return true 扣款完成；false 已取消／失敗
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
     * 【職責】Cancel：把凍結還回 available。
     * 【技巧】無列／已 CANCELLED → 空操作；僅 TRYING→CANCELLED 時才真正還錢。
     * 【概念】帳戶補償靠此方法；訂單 FAILED 不在這裡寫。
     * 【使用】由 {@link #confirm}（forceFail）或 {@code CANCEL_FUNDS} 觸發；可安全重入。
     * <pre>
     * tcc.cancel(sagaId);
     * tcc.cancel(sagaId); // 第二次無害
     * </pre>
     *
     * @param sagaId 與 Try 相同
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
