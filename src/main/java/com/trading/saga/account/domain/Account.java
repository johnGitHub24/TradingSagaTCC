package com.trading.saga.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 【職責】帳戶庫的資金聚合：available／frozen，只在本庫交易內變更。
 * 【技巧】TCC 三方法都用 {@link BigDecimal#compareTo} 比大小，不用 equals。
 * 【概念】Try＝預留（轉凍結）、Confirm＝真正扣款（凍結消失）、Cancel＝補償（凍結回到可用）。
 * 【邊界】不寫訂單表、不發 Kafka；跨庫一致性由 Saga／TCC 協調。
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal available;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal frozen;

    /**
     * TCC Try：從 available 轉入 frozen。
     *
     * @param amount 必須為正數
     * @throws InsufficientFundsException 可用餘額不足（餘額不變）
     */
    public void tryReserve(BigDecimal amount) {
        requirePositive(amount);
        if (available.compareTo(amount) < 0) {
            throw new InsufficientFundsException(accountId, amount, available);
        }
        available = available.subtract(amount);
        frozen = frozen.add(amount);
    }

    /**
     * TCC Confirm：消耗已凍結金額（不再退回 available）。
     *
     * @param amount 必須與 Try 時相同
     */
    public void confirm(BigDecimal amount) {
        requirePositive(amount);
        if (frozen.compareTo(amount) < 0) {
            throw new IllegalStateException("frozen " + frozen + " < confirm " + amount);
        }
        frozen = frozen.subtract(amount);
    }

    /**
     * TCC Cancel：把凍結還回 available（補償）。
     *
     * @param amount 必須與 Try 時相同
     */
    public void cancel(BigDecimal amount) {
        requirePositive(amount);
        if (frozen.compareTo(amount) < 0) {
            throw new IllegalStateException("frozen " + frozen + " < cancel " + amount);
        }
        frozen = frozen.subtract(amount);
        available = available.add(amount);
    }

    /**
     * 練習重置：回到種子餘額。
     *
     * @param availableAmount 可用
     * @param frozenAmount    凍結
     */
    public void resetTo(BigDecimal availableAmount, BigDecimal frozenAmount) {
        this.available = availableAmount;
        this.frozen = frozenAmount;
    }

    /**
     * @return available + frozen（Confirm 後 total 會下降）
     */
    public BigDecimal total() {
        return available.add(frozen);
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
