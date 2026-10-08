package com.trading.saga.order.domain;

/**
 * 【職責】編排式 Saga 生命週期；終態不可再轉。
 * <p>【技巧】{@link #canTransitionTo(SagaStatus)} 集中合法邊，避免各處 if 散落。
 * <p>【概念】Saga 記「流程走到哪」；帳戶 TCC 記「錢凍在哪」。兩者不同庫。
 *
 * <p>【使用】狀態只能經 {@link SagaInstance#transitionTo(SagaStatus)} 改變；
 * <br>非法的一跳會丟 {@link IllegalStateException}，不要直接 set 欄位。
 *
 * <p>【狀態圖】
 * <pre>
 * 正向（SAGA-001）：
 *   STARTED → ACCOUNT_TRYING → ACCOUNT_CONFIRMING → COMPLETED
 *
 * 負向（SAGA-002 餘額不足／TCC-002 forceFail）：
 *   STARTED／ACCOUNT_TRYING／ACCOUNT_CONFIRMING → COMPENSATING → COMPENSATED
 *
 * 預留（目前無程式走到）：
 *   COMPENSATING → FAILED
 * </pre>
 *
 * <p>【誰在什麼時機轉換】
 * <pre>
 * 狀態                誰設定                                     觸發時機
 * STARTED             SagaInstance.start()                       建立 Saga 實體（初始值）
 * ACCOUNT_TRYING      SagaOrchestrator.start()                   同 TX 寫 Outbox RESERVE_FUNDS 之後
 * ACCOUNT_CONFIRMING  OrderSagaEventHandler.onReserved()         收到 FUNDS_RESERVED，寫 Outbox CONFIRM_FUNDS
 * COMPLETED           OrderSagaEventHandler.onConfirmed()        收到 FUNDS_CONFIRMED，訂單 FILLED
 * COMPENSATING        OrderMarkFailedAction.compensate()         收到 FUNDS_FAILED／FUNDS_CANCELLED（中間態，同一 TX 內）
 * COMPENSATED         OrderMarkFailedAction.compensate()         緊接 COMPENSATING，訂單 FAILED
 * FAILED              （無）                                     預留：補償本身也失敗時使用
 * </pre>
 *
 * <p>【常見誤會】Saga {@code COMPENSATED} 搭配訂單 {@code FAILED} 是「失敗路徑已正確收尾」，
 * <br>不是系統出錯；帳戶那邊由 TCC Cancel 還原 frozen → available。
 *
 * <p>【與 TCC 預留票的差異】Saga 記流程（7 個值），預留票 {@code TccState} 只記錢（3 個值）；
 * <br>預留票先變、Saga 收到事件後才跟上。三組狀態逐步對照見 {@code docs/狀態對照-Saga-TCC-訂單.md}。
 */
public enum SagaStatus {

    /** 初始值：{@link SagaInstance#start} 建立時設定，通常同一 TX 內立刻轉 {@link #ACCOUNT_TRYING}。 */
    STARTED,

    /**
     * 已在發件匣登記 RESERVE_FUNDS，等待帳戶 TCC Try 結果。
     * <br>由 {@code SagaOrchestrator.start} 設定；HTTP 202 回傳時多半停在這裡。
     */
    ACCOUNT_TRYING,

    /**
     * 帳戶已凍結資金（FUNDS_RESERVED），已登記 CONFIRM_FUNDS，等待 Confirm 結果。
     * <br>由 {@code OrderSagaEventHandler.onReserved} 設定；重送的 FUNDS_RESERVED 看到此狀態會直接略過（冪等）。
     */
    ACCOUNT_CONFIRMING,

    /**
     * 終態（成功）：帳戶已扣款、訂單 FILLED。
     * <br>由 {@code OrderSagaEventHandler.onConfirmed} 設定；前台顯示綠色並停止輪詢。
     */
    COMPLETED,

    /**
     * 中間態：開始補償。{@code OrderMarkFailedAction.compensate} 在同一 TX 內
     * <br>先轉到這裡、再立刻轉 {@link #COMPENSATED}，所以外部查詢幾乎看不到它。
     */
    COMPENSATING,

    /**
     * 終態（失敗已收尾）：訂單標 FAILED，帳戶側由 TCC Cancel 還原。
     * <br>由 {@code OrderMarkFailedAction.compensate} 設定；前台顯示黃色並停止輪詢。
     */
    COMPENSATED,

    /**
     * 終態（預留）：補償本身失敗、需人工介入時使用。
     * <br>目前沒有程式會設定它；前台與 {@link #isTerminal()} 已先把它當終態處理。
     */
    FAILED;

    /**
     * 【職責】判斷從目前狀態跳到 {@code next} 是否合法（狀態機的唯一規則表）。
     * <p>【技巧】switch 表達式列出每個狀態的合法出口；新增狀態時編譯器會逼你補 case。
     * <p>【概念】任何非終態都能轉 {@link #COMPENSATING}，因為失敗事件可能在 Try 或 Confirm 任一階段到達；
     * <br>終態一律回 false，保證 Kafka 重送不會把已結束的 Saga 再改掉。
     * <p>【使用】業務程式不直接呼叫，改用 {@link SagaInstance#transitionTo}（它會呼叫本方法並在非法時丟例外）。
     * <pre>
     * SagaStatus.ACCOUNT_TRYING.canTransitionTo(SagaStatus.ACCOUNT_CONFIRMING); // true
     * SagaStatus.COMPLETED.canTransitionTo(SagaStatus.COMPENSATING);            // false：終態不可再轉
     * </pre>
     *
     * @param next 目標狀態
     * @return 是否允許這一跳
     */
    public boolean canTransitionTo(SagaStatus next) {
        return switch (this) {
            case STARTED -> next == ACCOUNT_TRYING || next == COMPENSATING;
            case ACCOUNT_TRYING -> next == ACCOUNT_CONFIRMING || next == COMPENSATING;
            case ACCOUNT_CONFIRMING -> next == COMPLETED || next == COMPENSATING;
            case COMPENSATING -> next == COMPENSATED || next == FAILED;
            case COMPLETED, COMPENSATED, FAILED -> false;
        };
    }

    /**
     * 【職責】是否為終態（流程已結束，不會再變）。
     * <p>【使用】兩種用途：
     * <ul>
     *   <li>後端冪等：{@code OrderSagaEventHandler}／{@code OrderMarkFailedAction} 看到終態直接 return，
     *       避免 Kafka 重送事件重複處理。</li>
     *   <li>前台輪詢：{@code app.js} 的 {@code pollSaga} 看到 COMPLETED／COMPENSATED／FAILED 就停止輪詢並顯示結果。</li>
     * </ul>
     *
     * @return true＝COMPLETED／COMPENSATED／FAILED
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == COMPENSATED || this == FAILED;
    }
}
