package com.trading.saga.account;

import com.trading.saga.account.domain.Account;
import com.trading.saga.account.domain.TccReservation;
import com.trading.saga.account.domain.TccState;
import com.trading.saga.account.infrastructure.AccountRepository;
import com.trading.saga.account.infrastructure.TccReservationRepository;
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
 * 【職責】帳戶查詢單元層，與 ACCOUNT-001、TCC-001（預留票查詢）成對。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountQueryService unit")
class AccountQueryServiceTest {

    /**
     * 【概念】假的 repository：Mockito 依介面產生替身，<b>沒有 H2、沒有資料表、不會發 SQL</b>。
     * <br>每個方法預設回 null／空值，要回什麼由各測試用 {@code given(...).willReturn(...)} 事先寫劇本。
     */
    @Mock
    private AccountRepository accountRepository;

    /** 【概念】假的預留票 repository；TCC-001 用它模擬「有票」與「無票」。 */
    @Mock
    private TccReservationRepository reservationRepository;

    /**
     * 【概念】真的 {@link AccountQueryService}：Mockito 呼叫它的建構子，把上面兩個假 repository 塞進去
     * <br>（等同手寫 {@code new AccountQueryService(accountRepository, reservationRepository)}）；這裡沒有 Spring 容器參與。
     */
    @InjectMocks
    private AccountQueryService queryService;

    /**
     * 【職責】ACCOUNT-001 正向：查到帳戶時，Service 能正確轉成 {@code AccountResponse}（含 total 計算）。
     * <p>【概念】資料<b>不是</b>從資料表來：是測試自己 new 一個 Account，交給假 repository 當回傳值。
     * <br>正式環境的 ACC-001 由 {@code AccountDataSeeder} 在 App 啟動時 {@code save} 進 accountdb；
     * <br>走真資料庫的版本在 {@code TradeSagaIntegrationTest}（同一 Case ID）。
     */
    @Test
    @DisplayName("ACCOUNT-001: get ACC-001 returns seed balances")
    void get_seedAccount() {
        // ① 準備（Given）：只在記憶體 new 一個 Java 物件，沒有 save，也沒有寫入任何 table。
        //    數值刻意和種子帳戶一致（ACC-001／100000／凍結 0），讓單元與整合測試對照同一份期望。
        Account account = Account.builder()
                .accountId("ACC-001")
                .available(new BigDecimal("100000"))
                .frozen(BigDecimal.ZERO)
                .build();
        // ② 寫劇本：等一下若有人呼叫 findById("ACC-001")，假 repository 就直接回上面的物件。
        //    這一行本身什麼都不查；它只是「預約」回傳值。參數不同（例如 "ACC-002"）就不會命中，會回 Optional.empty()。
        given(accountRepository.findById("ACC-001")).willReturn(Optional.of(account));

        // ③ 執行（When）：跑真正的 Service 程式；它內部呼叫 accountRepository.findById("ACC-001")，
        //    拿到的是 ② 塞進去的物件，再轉成 AccountResponse。整個過程沒有碰資料庫。
        var response = queryService.get("ACC-001");

        // ④ 驗證（Then）：只驗 Service 的轉換邏輯是否正確。
        assertThat(response.accountId()).isEqualTo("ACC-001");
        // BigDecimal 用 isEqualByComparingTo 比數值：100000 與 100000.00 視為相等（isEqualTo 會連 scale 一起比而失敗）。
        assertThat(response.available()).isEqualByComparingTo("100000");
        // total 不是存在資料表的欄位：由 Account.total() 即時算 available + frozen = 100000 + 0，
        // 再經 AccountResponse.from 帶進回應。
        assertThat(response.total()).isEqualByComparingTo("100000");
    }

    /**
     * 【職責】ACCOUNT-001 負向：查不到帳戶時要丟 {@link ResourceNotFoundException}
     * <br>（由 {@code GlobalExceptionHandler} 轉成 HTTP 404）。
     */
    @Test
    @DisplayName("ACCOUNT-001 error: missing account → ResourceNotFoundException")
    void get_missing_throws() {
        // ① 寫劇本：模擬「資料表裡沒有 NOPE」——不用真的準備空資料庫，直接讓 findById 回 Optional.empty()。
        given(accountRepository.findById("NOPE")).willReturn(Optional.empty());

        // ② 執行＋驗證：Service 拿到空的 Optional，應走 orElseThrow 丟例外，訊息要帶出查詢的帳號方便除錯。
        assertThatThrownBy(() -> queryService.get("NOPE"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("NOPE");
    }

    /**
     * 【職責】TCC-001 正向：有預留票時投影出 state／amount（Dashboard TCC 狀態機的資料來源）。
     */
    @Test
    @DisplayName("TCC-001: reservation exists → exists=true with state CONFIRMED")
    void getReservation_exists() {
        TccReservation reservation = TccReservation.trying("saga-1", "ACC-001", new BigDecimal("10000"));
        reservation.markConfirmed();
        given(reservationRepository.findById("saga-1")).willReturn(Optional.of(reservation));

        var response = queryService.getReservation("saga-1");

        assertThat(response.exists()).isTrue();
        assertThat(response.state()).isEqualTo(TccState.CONFIRMED);
        assertThat(response.accountId()).isEqualTo("ACC-001");
        assertThat(response.amount()).isEqualByComparingTo("10000");
    }

    /**
     * 【職責】TCC-001 無票：回 exists=false 而非拋例外（Try 尚未執行或 Try 失敗都是合法狀態）。
     */
    @Test
    @DisplayName("TCC-001: no reservation → exists=false, no exception")
    void getReservation_none() {
        given(reservationRepository.findById("saga-x")).willReturn(Optional.empty());

        var response = queryService.getReservation("saga-x");

        assertThat(response.sagaId()).isEqualTo("saga-x");
        assertThat(response.exists()).isFalse();
        assertThat(response.state()).isNull();
    }
}
