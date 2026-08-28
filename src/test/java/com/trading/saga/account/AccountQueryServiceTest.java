package com.trading.saga.account;

import com.trading.saga.account.domain.Account;
import com.trading.saga.account.infrastructure.AccountRepository;
import com.trading.saga.common.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 【職責】帳戶查詢單元層，與 ACCOUNT-001 成對。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountQueryService unit")
class AccountQueryServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @InjectMocks
    private AccountQueryService queryService;

    @Test
    @DisplayName("ACCOUNT-001: get ACC-001 returns seed balances")
    void get_seedAccount() {
        Account account = Account.builder()
                .accountId("ACC-001")
                .available(new BigDecimal("100000"))
                .frozen(BigDecimal.ZERO)
                .build();
        given(accountRepository.findById("ACC-001")).willReturn(Optional.of(account));

        var response = queryService.get("ACC-001");

        assertThat(response.accountId()).isEqualTo("ACC-001");
        assertThat(response.available()).isEqualByComparingTo("100000");
        assertThat(response.total()).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("ACCOUNT-001 error: missing account → ResourceNotFoundException")
    void get_missing_throws() {
        given(accountRepository.findById("NOPE")).willReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.get("NOPE"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("NOPE");
    }
}
