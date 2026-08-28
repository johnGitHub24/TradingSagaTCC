package com.trading.saga.account.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【職責】帳戶領域：TCC Try／Confirm／Cancel 的資金不變式（不啟動 Spring）。
 * 【技巧】純物件斷言；與整合層 SAGA-001／SAGA-002／TCC-002 同一資金語意。
 * 【概念】Try 把 available 轉入 frozen；Confirm 吃掉 frozen；Cancel 把 frozen 還回 available。
 */
@DisplayName("Account domain (TCC money invariants)")
class AccountTest {

    private Account seed() {
        return Account.builder()
                .accountId("ACC-001")
                .available(new BigDecimal("100000"))
                .frozen(BigDecimal.ZERO)
                .build();
    }

    @Nested
    @DisplayName("SAGA-001 / TCC Try-Confirm")
    class TryConfirm {

        @Test
        @DisplayName("SAGA-001: tryReserve 10000 then confirm → available 90000, frozen 0")
        void tryThenConfirm_deductsAvailable() {
            Account account = seed();

            account.tryReserve(new BigDecimal("10000"));
            assertThat(account.getAvailable()).isEqualByComparingTo("90000");
            assertThat(account.getFrozen()).isEqualByComparingTo("10000");
            assertThat(account.total()).isEqualByComparingTo("100000");

            account.confirm(new BigDecimal("10000"));
            assertThat(account.getAvailable()).isEqualByComparingTo("90000");
            assertThat(account.getFrozen()).isEqualByComparingTo("0");
            assertThat(account.total()).isEqualByComparingTo("90000");
        }
    }

    @Nested
    @DisplayName("SAGA-002 insufficient")
    class Insufficient {

        @Test
        @DisplayName("SAGA-002: tryReserve above available → InsufficientFundsException, balances unchanged")
        void tryReserve_tooLarge_throwsAndLeavesBalances() {
            Account account = seed();

            assertThatThrownBy(() -> account.tryReserve(new BigDecimal("999999")))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(account.getAvailable()).isEqualByComparingTo("100000");
            assertThat(account.getFrozen()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("TCC-002 Try-Cancel")
    class TryCancel {

        @Test
        @DisplayName("TCC-002: tryReserve then cancel → available restored to 100000")
        void tryThenCancel_restoresAvailable() {
            Account account = seed();

            account.tryReserve(new BigDecimal("10000"));
            account.cancel(new BigDecimal("10000"));

            assertThat(account.getAvailable()).isEqualByComparingTo("100000");
            assertThat(account.getFrozen()).isEqualByComparingTo("0");
            assertThat(account.total()).isEqualByComparingTo("100000");
        }
    }
}
