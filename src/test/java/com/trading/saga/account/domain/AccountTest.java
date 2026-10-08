package com.trading.saga.account.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【職責】帳戶領域：TCC Try／Confirm／Cancel 的資金不變式（不啟動 Spring）。
 * <br>覆蓋 {@link Account} 領域層；對應 Case SAGA-001（Try→Confirm）、SAGA-002（餘額不足）、TCC-002（Try→Cancel）。
 * <p>【技巧】純物件斷言；與整合層 SAGA-001／SAGA-002／TCC-002 同一資金語意。
 * <br>用 {@code @Nested} 依 Case 分組；BigDecimal 一律以 {@code isEqualByComparingTo} 比數值；
 * <br>例外以 {@code assertThatThrownBy} 斷言型別，並在丟出後再檢查餘額未被動到。
 * <p>【概念】Try 把 available 轉入 frozen；Confirm 吃掉 frozen；Cancel 把 frozen 還回 available。
 * <br>名義總額 {@code total() = available + frozen}：Try／Cancel 只是「錢換口袋」所以 total 不變，
 * <br>只有 Confirm 才真正扣款讓 total 下降。這就是 TCC 的資金不變式，測試逐步驗證它。
 */
// 測試報告／IDE 上顯示的名稱
@DisplayName("Account domain (TCC money invariants)")
class AccountTest {

    /**
     * 【職責】建立與正式種子相同的帳戶：ACC-001、available 100000、frozen 0。
     * <p>【技巧】每個 Test 呼叫一次取得全新實例，避免測試之間共用可變狀態。
     * <p>【概念】數值與 {@code AccountQueryService.SEED_AVAILABLE}、
     * <br>{@code docs/test-data/account/ACCOUNT-001-SEED.json} 一致，讓單元層與整合層預期值同源。
     *
     * @return 尚未持久化的種子帳戶
     */
    private Account seed() {
        // 用 Lombok @Builder 產生的建構器組出 Account（純 Java 物件，不經 JPA）
        return Account.builder()
                // 帳戶代號：與正式種子帳戶 ACC-001 相同
                .accountId("ACC-001")
                // 可用餘額 100000；用字串建 BigDecimal 避免 double 精度誤差
                .available(new BigDecimal("100000"))
                // 凍結金額從 0 開始：尚未有任何 Try 預留
                .frozen(BigDecimal.ZERO)
                // 產生實體
                .build();
    }

    // 巢狀測試類別：把 SAGA-001 成功路徑（Try 後 Confirm）的 Case 收在同一組
    @Nested
    // 報告中這一組的標題
    @DisplayName("SAGA-001 / TCC Try-Confirm")
    class TryConfirm {

        /**
         * SAGA-001：Given 種子 100000，When Try 10000 再 Confirm 10000，
         * <br>Then available 90000、frozen 0、total 90000（真正扣款）。
         */
        @Test
        @DisplayName("SAGA-001: tryReserve 10000 then confirm → available 90000, frozen 0")
        void tryThenConfirm_deductsAvailable() {
            // ===== Given：種子帳戶 available 100000／frozen 0 =====
            // 取得全新種子帳戶
            Account account = seed();

            // ===== When①：TCC Try 預留 10000 =====
            // 10000 來自 SAGA-001-SUCCESS.json 的 1 × 10000；Try 會把 available 移到 frozen
            account.tryReserve(new BigDecimal("10000"));

            // ===== Then①：錢從 available 換到 frozen，總額不變 =====
            // 可用 100000 − 10000 ＝ 90000；isEqualByComparingTo 只比數值，忽略 scale（90000 vs 90000.0000）
            assertThat(account.getAvailable()).isEqualByComparingTo("90000");
            // 凍結 0 + 10000 ＝ 10000：資金已被預留但尚未扣走
            assertThat(account.getFrozen()).isEqualByComparingTo("10000");
            // total ＝ available + frozen ＝ 100000：Try 只是換口袋，總額不變
            assertThat(account.total()).isEqualByComparingTo("100000");

            // ===== When②：TCC Confirm 消耗凍結的 10000 =====
            // 金額必須與 Try 相同；Confirm 只扣 frozen，不退回 available
            account.confirm(new BigDecimal("10000"));

            // ===== Then②：凍結被吃掉，總額下降 =====
            // available 不受 Confirm 影響，維持 Try 後的 90000
            assertThat(account.getAvailable()).isEqualByComparingTo("90000");
            // frozen 10000 − 10000 ＝ 0：預留已轉為真正扣款
            assertThat(account.getFrozen()).isEqualByComparingTo("0");
            // total ＝ 90000 + 0：只有 Confirm 會讓總額下降，與整合層 SAGA-001 的 available 90000 一致
            assertThat(account.total()).isEqualByComparingTo("90000");
        }
    }

    // 巢狀測試類別：SAGA-002 餘額不足（Try 階段即被拒）
    @Nested
    // 報告中這一組的標題
    @DisplayName("SAGA-002 insufficient")
    class Insufficient {

        /**
         * SAGA-002：Given 種子 100000，When Try 999999，
         * <br>Then 丟 {@link InsufficientFundsException} 且 available／frozen 完全不變。
         */
        @Test
        @DisplayName("SAGA-002: tryReserve above available → InsufficientFundsException, balances unchanged")
        void tryReserve_tooLarge_throwsAndLeavesBalances() {
            // ===== Given：種子帳戶 available 100000／frozen 0 =====
            // 取得全新種子帳戶
            Account account = seed();

            // ===== When／Then①：預留超過可用餘額必須丟領域例外 =====
            // 999999 來自 SAGA-002-INSUFFICIENT.json 的 1 × 999999，大於 available 100000；
            // assertThatThrownBy 接一個 lambda，執行時抓住丟出的例外再交給後續斷言（未丟例外則測試失敗）
            assertThatThrownBy(() -> account.tryReserve(new BigDecimal("999999")))
                    // 型別必須是 InsufficientFundsException（不是 IllegalArgumentException 或 IllegalStateException）
                    .isInstanceOf(InsufficientFundsException.class);

            // ===== Then②：例外在改欄位之前丟出，餘額不變 =====
            // tryReserve 先比 available < amount 才丟例外，所以 available 仍是 100000
            assertThat(account.getAvailable()).isEqualByComparingTo("100000");
            // frozen 也沒被加上任何金額，仍為 0
            assertThat(account.getFrozen()).isEqualByComparingTo("0");
        }
    }

    // 巢狀測試類別：TCC-002 補償路徑（Try 後 Cancel）
    @Nested
    // 報告中這一組的標題
    @DisplayName("TCC-002 Try-Cancel")
    class TryCancel {

        /**
         * TCC-002：Given 種子 100000，When Try 10000 再 Cancel 10000，
         * <br>Then available 還原 100000、frozen 0、total 100000。
         */
        @Test
        @DisplayName("TCC-002: tryReserve then cancel → available restored to 100000")
        void tryThenCancel_restoresAvailable() {
            // ===== Given：種子帳戶 available 100000／frozen 0 =====
            // 取得全新種子帳戶
            Account account = seed();

            // ===== When：先 Try 預留、再 Cancel 補償 =====
            // Try：available 100000 → 90000，frozen 0 → 10000
            account.tryReserve(new BigDecimal("10000"));
            // Cancel：金額與 Try 相同，frozen 10000 → 0，available 90000 → 100000（對應 forceFail 補償）
            account.cancel(new BigDecimal("10000"));

            // ===== Then：完全回到 Try 之前的樣子 =====
            // 可用餘額還原為種子值 100000
            assertThat(account.getAvailable()).isEqualByComparingTo("100000");
            // 凍結歸零：沒有殘留的預留金額
            assertThat(account.getFrozen()).isEqualByComparingTo("0");
            // total 全程維持 100000：Try／Cancel 都不改變總額，錢沒有被扣走
            assertThat(account.total()).isEqualByComparingTo("100000");
        }
    }
}
