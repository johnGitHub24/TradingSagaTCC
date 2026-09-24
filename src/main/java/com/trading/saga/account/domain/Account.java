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
 * 【使用】經由 {@code AccountTccService} 呼叫；勿在 Controller 直接改欄位。
 * 【邊界】不寫訂單表、不發 Kafka。
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
     * 【職責】TCC Try：available → frozen。
     * 【使用】僅在尚未有預留票時由 Service 呼叫一次。
     * <pre>
     * account.tryReserve(new BigDecimal("10000"));
     * // available -= 10000；frozen += 10000
     * </pre>
     *
     * @param amount 必須為正數
     * @throws InsufficientFundsException 可用不足（餘額不變）
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
     * 【職責】TCC Confirm：消耗 frozen（真正扣款，不退回 available）。
     * 【使用】Try 成功且要成交時；amount 須與 Try 相同。
     * <pre>
     * account.confirm(amount); // frozen -= amount；total 下降
     * </pre>
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
     * 【職責】TCC Cancel：frozen → available（補償還原）。
     * 【使用】forceFail 或業務取消預留時。
     * <pre>
     * account.cancel(amount); // frozen -= amount；available += amount
     * </pre>
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
     * 【職責】Demo／測試重置種子餘額。
     * 【使用】{@code POST /api/v1/accounts/ACC-001/reset} 最終會呼叫此方法。
     *
     * @param availableAmount 可用
     * @param frozenAmount    凍結（通常 0）
     */
    public void resetTo(BigDecimal availableAmount, BigDecimal frozenAmount) {
        this.available = availableAmount;
        this.frozen = frozenAmount;
    }

    /**
     * 【職責】計算名義總額。
     * 【概念】Confirm 後 total 會下降；Try／Cancel 期間 total 不變。
     *
     * @return available + frozen
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
