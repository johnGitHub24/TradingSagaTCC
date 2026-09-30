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
 *
 * <p>【概念·金額從哪來】{@link #amountOf} 讀 fixture 的 {@code quantity × price}：</p>
 * <pre>
 * SAGA-001-SUCCESS／TCC-002-FORCE-FAIL：1 × 10000  = 10000（一萬，不是帳戶的十萬）
 * SAGA-002-INSUFFICIENT             ：1 × 999999 = 999999（超過 available 100000 → Try 失敗）
 * </pre>
 *
 * <p>【概念·帳戶三個數字怎麼變】以 SAGA-001（amount = 10000）為例；total = available + frozen：</p>
 * <pre>
 * 時間點                          available   frozen   total    誰驗這一列
 * ─────────────────────────────   ─────────   ──────   ──────   ─────────────────────────
 * setUp() 建好帳戶                   100000        0   100000   （每個測試的起點）
 * Try：tryReserve(10000) 之後         90000    10000   100000   tryReserve_success
 * Confirm：confirm(false) 之後        90000        0    90000   confirm_success
 * Cancel：confirm(true) 之後         100000        0   100000   confirm_forceFail_cancels
 * Try 失敗（999999 餘額不足）        100000        0   100000   tryReserve_insufficient_returnsFalse
 * </pre>
 * <ul>
 *   <li><b>Try 只凍結、不扣款</b>：錢從 available 移到 frozen，total 不變——帳戶裡的錢一毛都沒少。</li>
 *   <li><b>Confirm 才真正扣款</b>：frozen 歸零且不回 available，total 才從 100000 降到 90000。</li>
 *   <li><b>Cancel 是把凍結退回</b>：frozen 回到 available，數字回到起點。</li>
 * </ul>
 * <p>【常見誤會】fixture 的 description 寫「available 90000」，指的是<b>整條 Saga 跑完</b>
 * （Try＋Confirm）的結果。單看 Try 之後 available 也是 90000，但錢只是被凍結；
 * 要分辨「凍結中」還是「已扣款」，看 frozen 與 total，不要只看 available。</p>
 * <p>【注意】{@code setUp()} 每個 {@code @Test} 前都重建帳戶，所以各測試互不影響、起點都是 100000／0。</p>
 *
 * <p>【範圍·本類只測一格】直接呼叫 {@code tryReserve}／{@code confirm}，拿到 boolean 就結束；
 * 沒有 Spring、沒有 Kafka、repository 是假的。同一個 Case 分三層測，各管一段：</p>
 * <pre>
 * 層級                                  執行範圍
 * ───────────────────────────────────   ────────────────────────────────────────────────
 * AccountTccServiceTest（本類）          AccountTccService：改餘額、寫預留票、回 boolean
 * AccountCommandHandlerTest             AccountCommandHandler：boolean → 寄哪種事件（TCC／Kafka 為假）
 * TradeSagaIntegrationTest              整條鏈：POST → Outbox → Kafka → Handler → TCC → 事件 → 補償
 * </pre>
 * <p>整合測試只寫「發 POST」與「await 等終態」兩步，中間由 OutboxRelayJob 輪詢、Kafka listener 在背景自己跑，
 * 所以要用 Awaitility 等結果，不能呼叫完立刻檢查。</p>
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

    /**
     * 【職責】SAGA-001 Try 成功：凍結 10000，並寫一張 TRYING 預留票。
     * <pre>
     *              available   frozen   total
     * 執行前          100000        0   100000
     * 執行後           90000    10000   100000   ← total 不變：只凍結、還沒扣款
     * </pre>
     */
    @Test
    @DisplayName("SAGA-001: tryReserve fixture amount succeeds and persists TRYING")
    void tryReserve_success() {
        // amount = 1 × 10000 = 10000（讀 docs/test-data/trade/SAGA-001-SUCCESS.json）
        BigDecimal amount = amountOf("SAGA-001-SUCCESS");
        // 沒有舊預留票 → 走「第一次 Try」路線（有票的話會直接回傳，屬冪等重送）
        given(reservationRepository.findById("saga-1")).willReturn(Optional.empty());
        given(accountRepository.findById("ACC-001")).willReturn(Optional.of(account));

        boolean ok = tccService.tryReserve("saga-1", "ACC-001", amount);

        assertThat(ok).isTrue();
        // frozen：0 → 10000。available 同時變 90000（本測試未 assert），total 仍是 100000。
        assertThat(account.getFrozen()).isEqualByComparingTo(amount);
        // 【為什麼要攔截】預留票是 Service 內部 TccReservation.trying(...) new 出來、直接丟進 save() 的；
        //   tryReserve 只回 boolean，repository 又是假的（不會真的存進 table 讓你再查），
        //   所以測試手上拿不到這張票，只能在它被傳進 save() 的那一刻抓下來檢查。
        //
        // 【時間順序】
        //   ① 上面 tryReserve(...) 執行時：Service 呼叫 save(票)
        //      → @Mock 不存資料，只默默記下「save 被呼叫、參數是 票」
        //   ② 下面 verify(...)：事後翻這份呼叫紀錄（不是再呼叫一次 save）
        //   ③ captor.getValue()：取出 ① 那張票，檢查內容

        // ① 準備捕捉器：專門接 TccReservation 型別參數的空容器，此時裡面還沒有東西。
        ArgumentCaptor<TccReservation> captor = ArgumentCaptor.forClass(TccReservation.class);
        // ② 一行做兩件事：
        //    - verify(...).save(...)：確認 save「剛好被呼叫 1 次」（verify 預設 times(1)；0 次或 2 次都失敗）
        //    - captor.capture()：放在參數位置＝「傳什麼都接受，但幫我存起來」
        verify(reservationRepository).save(captor.capture());
        // ③ getValue() 拿到的就是 Service 內部 new 出來的同一個物件；
        //    Try 階段寫入的預留票必須是 TRYING（之後 Confirm → CONFIRMED、Cancel → CANCELLED）。
        //    同一張票也可再驗 sagaId／accountId／amount，確保 Service 沒傳錯參數。
        assertThat(captor.getValue().getState()).isEqualTo(TccState.TRYING);
    }

    /**
     * 【職責】SAGA-002 Try 失敗：要凍結 999999，但 available 只有 100000 → 回 false，且不寫預留票。
     * <pre>
     *              available   frozen   total
     * 執行前          100000        0   100000
     * 執行後          100000        0   100000   ← 完全沒動：餘額不足時不做部分凍結
     * </pre>
     */
    @Test
    @DisplayName("SAGA-002: tryReserve insufficient fixture returns false")
    void tryReserve_insufficient_returnsFalse() {
        // amount = 1 × 999999 = 999999 > available 100000
        BigDecimal amount = amountOf("SAGA-002-INSUFFICIENT");
        given(reservationRepository.findById("saga-2")).willReturn(Optional.empty());
        given(accountRepository.findById("ACC-001")).willReturn(Optional.of(account));

        boolean ok = tccService.tryReserve("saga-2", "ACC-001", amount);

        // 本測試只驗 tryReserve 回 false；「false → 寄 FUNDS_FAILED」不在這裡執行
        // （見 AccountCommandHandlerTest.reserve_fail；端到端見 TradeSagaIntegrationTest.insufficient_compensated）。
        assertThat(ok).isFalse();
        assertThat(account.getAvailable()).isEqualByComparingTo("100000");
        // 沒有預留票：這就是 FUNDS_FAILED 與 FUNDS_CANCELLED 的差別（前者連票都沒有）
        // never()＝一次都不能呼叫；any()＝參數是什麼都行（反正不該被呼叫，不必看內容，所以不用 ArgumentCaptor）。
        //   對照：只驗有呼叫 → verify(mock).save(any())；要檢查傳了什麼 → ArgumentCaptor（見 tryReserve_success）。
        verify(reservationRepository, never()).save(any());
    }

    /**
     * 【職責】TCC-002 故意補償：Confirm 時帶 forceFail=true，帳戶側改做 Cancel，把凍結退回。
     * <pre>
     *                           available   frozen   total
     * setUp()                      100000        0   100000
     * 手動 Try 之後（準備）         90000    10000   100000
     * confirm(true) 之後           100000        0   100000   ← 回到起點：錢退回、沒有扣款
     * </pre>
     */
    @Test
    @DisplayName("TCC-002: confirm(forceFail=true) cancels and restores available")
    void confirm_forceFail_cancels() {
        // amount = 1 × 10000 = 10000
        BigDecimal amount = amountOf("TCC-002-FORCE-FAIL");
        // 準備：直接呼叫領域方法凍結 10000，模擬「Try 已經成功」→ 90000／10000
        account.tryReserve(amount);
        // 對應的 TRYING 預留票（Try 成功時本來就會寫這張）
        TccReservation reservation = TccReservation.trying("saga-3", "ACC-001", amount);
        given(reservationRepository.findById("saga-3")).willReturn(Optional.of(reservation));
        given(accountRepository.findById("ACC-001")).willReturn(Optional.of(account));

        boolean ok = tccService.confirm("saga-3", true);

        // 本測試只驗 confirm 回 false；「false＋forceFail → 寄 FUNDS_CANCELLED」不在這裡執行
        // （正式環境由 AccountCommandHandler 轉換；端到端見 TradeSagaIntegrationTest.forceFail_compensated）。
        assertThat(ok).isFalse();
        // 90000／10000 → 100000／0：凍結款全數退回 available
        assertThat(account.getAvailable()).isEqualByComparingTo("100000");
        assertThat(account.getFrozen()).isEqualByComparingTo("0");
        assertThat(reservation.getState()).isEqualTo(TccState.CANCELLED);
    }

    /**
     * 【職責】SAGA-001 Confirm 成功：消耗凍結款，真正扣款，預留票 → CONFIRMED。
     * <pre>
     *                           available   frozen   total
     * setUp()                      100000        0   100000
     * 手動 Try 之後（準備）            90000    10000   100000
     * confirm(false) 之後           90000        0    90000   ← total 才真的少 10000
     * </pre>
     * 【注意】available 在 Try 之後就已是 90000，Confirm 前後<b>都是 90000</b>；
     * 真正的變化在 frozen（10000 → 0）與 total（100000 → 90000）。
     */
    @Test
    @DisplayName("SAGA-001: confirm(forceFail=false) deducts frozen")
    void confirm_success() {
        // amount = 1 × 10000 = 10000
        BigDecimal amount = amountOf("SAGA-001-SUCCESS");
        // 準備：模擬 Try 已成功 → 90000／10000，並備妥 TRYING 預留票
        account.tryReserve(amount);
        TccReservation reservation = TccReservation.trying("saga-1", "ACC-001", amount);
        given(reservationRepository.findById("saga-1")).willReturn(Optional.of(reservation));
        given(accountRepository.findById("ACC-001")).willReturn(Optional.of(account));

        boolean ok = tccService.confirm("saga-1", false);

        // 本測試只驗 confirm 回 true；「true → 寄 FUNDS_CONFIRMED」不在這裡執行
        // （端到端見 TradeSagaIntegrationTest.happyPath_filled）。
        assertThat(ok).isTrue();
        // available 維持 90000（Try 時就扣出去了），frozen 10000 → 0：凍結款被消耗、不回 available
        assertThat(account.getAvailable()).isEqualByComparingTo("90000");
        assertThat(account.getFrozen()).isEqualByComparingTo("0");
        assertThat(reservation.getState()).isEqualTo(TccState.CONFIRMED);
    }
}
