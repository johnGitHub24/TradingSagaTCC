package com.trading.saga.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【職責】保護 Saga 狀態機合法轉移（編排終態與補償路徑）。
 * 覆蓋 {@link SagaStatus#canTransitionTo}／{@link SagaStatus#isTerminal} 與
 * {@link SagaInstance#transitionTo}（領域層）；對應 Case SAGA-001（成功）、SAGA-002／TCC-002（補償）。
 * 【技巧】純 enum／純物件斷言，不啟動 Spring；合法邊用 {@code isTrue()}，非法邊用 {@code isFalse()}，
 * 並以 {@code assertThatThrownBy} 確認實體層真的會擋下非法轉移。
 * 【概念】成功：STARTED→ACCOUNT_TRYING→ACCOUNT_CONFIRMING→COMPLETED；
 * 失敗：任中段（STARTED／ACCOUNT_TRYING／ACCOUNT_CONFIRMING）→COMPENSATING→COMPENSATED（補償本身失敗則→FAILED）。
 * 終態（COMPLETED／COMPENSATED／FAILED）不可再轉，Kafka 重送事件時才不會把已結束的 Saga 改掉。
 */
// 測試報告／IDE 上顯示的名稱
@DisplayName("SagaStatus transitions")
class SagaStatusTest {

    /**
     * SAGA-001：成功路徑上的每一跳都合法，且 COMPLETED 是終態。
     */
    @Test
    @DisplayName("SAGA-001 happy path transitions are allowed")
    void happyPath_allowed() {
        // ===== Then①：正向第一跳 =====
        // 建立 Saga 後登記 RESERVE_FUNDS，進入等待帳戶 Try
        assertThat(SagaStatus.STARTED.canTransitionTo(SagaStatus.ACCOUNT_TRYING)).isTrue();

        // ===== Then②：正向後續兩跳 =====
        // 收到 FUNDS_RESERVED：從等待 Try 轉為等待 Confirm
        assertThat(SagaStatus.ACCOUNT_TRYING.canTransitionTo(SagaStatus.ACCOUNT_CONFIRMING)).isTrue();
        // 收到 FUNDS_CONFIRMED：流程成功結束
        assertThat(SagaStatus.ACCOUNT_CONFIRMING.canTransitionTo(SagaStatus.COMPLETED)).isTrue();

        // ===== Then③：終點是終態 =====
        // COMPLETED 屬於終態，前台據此停止輪詢、後端據此略過重送事件
        assertThat(SagaStatus.COMPLETED.isTerminal()).isTrue();
    }

    /**
     * SAGA-002／TCC-002：任一中段狀態（含 Try 之前的 STARTED）失敗都能進入補償，
     * 補償收尾為 COMPENSATED 或 FAILED，兩者皆為終態。
     */
    @Test
    @DisplayName("SAGA-002 / TCC-002 compensation transitions are allowed")
    void compensationPath_allowed() {
        // ===== Then①：中段狀態都能轉補償 =====
        // Try 之前就失敗（尚未送出 RESERVE_FUNDS）→ 也允許直接補償
        assertThat(SagaStatus.STARTED.canTransitionTo(SagaStatus.COMPENSATING)).isTrue();
        // 等待 Try 時收到 FUNDS_FAILED（SAGA-002 餘額不足）→ 開始補償
        assertThat(SagaStatus.ACCOUNT_TRYING.canTransitionTo(SagaStatus.COMPENSATING)).isTrue();
        // 等待 Confirm 時收到 FUNDS_CANCELLED（TCC-002 forceFail）→ 開始補償
        assertThat(SagaStatus.ACCOUNT_CONFIRMING.canTransitionTo(SagaStatus.COMPENSATING)).isTrue();

        // ===== Then②：補償收尾 =====
        // COMPENSATING 是中間態，同一 TX 內緊接轉 COMPENSATED
        assertThat(SagaStatus.COMPENSATING.canTransitionTo(SagaStatus.COMPENSATED)).isTrue();
        // COMPENSATED 是終態：失敗路徑已正確收尾（不是系統出錯）
        assertThat(SagaStatus.COMPENSATED.isTerminal()).isTrue();

        // ===== Then③：補償本身失敗的出口 =====
        // 預留邊：補償也失敗時轉 FAILED（目前沒有程式設定，但規則表已允許）
        assertThat(SagaStatus.COMPENSATING.canTransitionTo(SagaStatus.FAILED)).isTrue();
        // FAILED 同樣是終態，前台看到即停止輪詢
        assertThat(SagaStatus.FAILED.isTerminal()).isTrue();
        // 終態不可再轉：FAILED 不能回到補償重來
        assertThat(SagaStatus.FAILED.canTransitionTo(SagaStatus.COMPENSATING)).isFalse();
    }

    /**
     * Given 已走完成功路徑的 Saga（COMPLETED），When 再要求轉 COMPENSATING，
     * Then enum 規則回 false，且 {@link SagaInstance#transitionTo} 丟 {@link IllegalStateException}。
     */
    @Test
    @DisplayName("COMPLETED cannot transition to COMPENSATING")
    void completed_cannotCompensate() {
        // ===== Then①：規則表層級 =====
        // 終態一律回 false：已成功的 Saga 不能被事後補償
        assertThat(SagaStatus.COMPLETED.canTransitionTo(SagaStatus.COMPENSATING)).isFalse();

        // ===== Given：建立一筆 Saga 並以合法步驟走到 COMPLETED =====
        // start() 建立的實體初始狀態為 STARTED；id 只是測試用字串，不需與 DB 對應
        SagaInstance saga = SagaInstance.start("saga-1", "order-1");
        // STARTED → ACCOUNT_TRYING（合法）
        saga.transitionTo(SagaStatus.ACCOUNT_TRYING);
        // ACCOUNT_TRYING → ACCOUNT_CONFIRMING（合法）
        saga.transitionTo(SagaStatus.ACCOUNT_CONFIRMING);
        // ACCOUNT_CONFIRMING → COMPLETED（合法，進入終態）
        saga.transitionTo(SagaStatus.COMPLETED);

        // ===== When／Then②：實體層級必須擋下非法轉移 =====
        // transitionTo 內部呼叫 canTransitionTo，為 false 時丟例外；lambda 讓 AssertJ 能捕捉該例外
        assertThatThrownBy(() -> saga.transitionTo(SagaStatus.COMPENSATING))
                // 型別必須是 IllegalStateException（訊息為 "illegal saga transition COMPLETED -> COMPENSATING"）
                .isInstanceOf(IllegalStateException.class);
    }
}
