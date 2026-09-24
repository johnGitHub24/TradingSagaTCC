package com.trading.saga.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 【職責】帳戶庫的 TCC 預留紀錄（俗稱「預留票」），以 sagaId 當自然鍵以利冪等 Try／Cancel。
 * 【技巧】同一 saga 重送 Reserve 時直接回既有列，不重複扣 available。
 * 【概念】預留票＝帳戶側的 TCC 憑證（非訂單）：
 * Try 發票（available→frozen＋寫本列）；Confirm 兌票扣款（吃掉 frozen）；Cancel 退票還原。
 * 訂單庫看不到此表，只靠 Kafka 事件得知結果。
 * 【使用】經 {@code AccountTccService}／Repository 操作；PK＝sagaId（冪等 Key）。
 * 【邊界】這是帳戶自己的「預留票」；不要與 trade_orders 混淆。
 */
@Entity
@Table(name = "tcc_reservations")
@Getter
@NoArgsConstructor
public class TccReservation {

    @Id
    @Column(name = "saga_id", nullable = false, length = 64)
    private String sagaId;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TccState state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private TccReservation(String sagaId, String accountId, BigDecimal amount) {
        this.sagaId = sagaId;
        this.accountId = accountId;
        this.amount = amount;
        this.state = TccState.TRYING;
        this.createdAt = Instant.now();
    }

    /**
     * 【職責】建立 TRYING 預留（尚未 persist）。
     * 【使用】Try 成功後 {@code reservationRepository.save(TccReservation.trying(...))}。
     */
    public static TccReservation trying(String sagaId, String accountId, BigDecimal amount) {
        return new TccReservation(sagaId, accountId, amount);
    }

    /**
     * 【職責】標記已 Confirm（TRYING → CONFIRMED）。
     * 【使用】僅 Confirm 成功路徑；非 TRYING 會丟 IllegalStateException。
     */
    public void markConfirmed() {
        if (state != TccState.TRYING) {
            throw new IllegalStateException("cannot confirm reservation in " + state);
        }
        this.state = TccState.CONFIRMED;
    }

    /**
     * 【職責】標記已 Cancel；已 CANCELLED 則冪等略過。
     * 【使用】Cancel／forceFail 路徑；回 true 表示本次真正從 TRYING 轉出（才需還錢）。
     *
     * @return true 表示本次真正從 TRYING 轉出
     */
    public boolean markCancelled() {
        if (state == TccState.CANCELLED) {
            return false;
        }
        if (state != TccState.TRYING) {
            throw new IllegalStateException("cannot cancel reservation in " + state);
        }
        this.state = TccState.CANCELLED;
        return true;
    }
}
