package com.trading.saga.messaging;

/**
 * 【職責】Kafka／Outbox 共用的訊息型別常數：{@link SagaMessage#type()} 只會是下列 7 個字串之一。
 * <br>送出端與接收端都比對同一組常數，避免手打字串拼錯（例如 {@code "FUND_RESERVED"} 少一個 S 就永遠收不到）。
 *
 * <p>【概念·兩種訊息】</p>
 * <ul>
 *   <li><b>命令（Command）</b>＝「請你做」：動詞開頭 {@code XXX_FUNDS}；訂單側發、帳戶側收；
 *       走 {@code trading.saga.commands}（command topic），<b>一律先寫 Outbox</b> 再由
 *       {@code OutboxRelayJob} 投遞。</li>
 *   <li><b>事件（Event）</b>＝「我做完了，結果是…」：{@code FUNDS_XXX} 過去式；帳戶側發、訂單側收；
 *       走 {@code trading.saga.events}（event topic），由 {@link AccountCommandHandler} 直接送出。</li>
 * </ul>
 * <p>口訣：<b>命令問「做不做」、事件答「做完了沒」</b>；訊息 key 一律是 sagaId（同一筆 Saga 保證順序）。</p>
 *
 * <p>【使用·一問一答對照】</p>
 * <pre>
 * 命令（訂單側 → 帳戶側）         帳戶側動作（TCC）          回覆事件（帳戶側 → 訂單側）
 * ─────────────────────────   ─────────────────────   ─────────────────────────────
 * RESERVE_FUNDS               tryReserve（凍結）        FUNDS_RESERVED ｜ FUNDS_FAILED（餘額不足）
 * CONFIRM_FUNDS               confirm（扣款）           FUNDS_CONFIRMED ｜ FUNDS_CANCELLED（forceFail）
 * CANCEL_FUNDS                cancel（解凍）            FUNDS_CANCELLED
 * </pre>
 *
 * <p>【使用·事件如何推動狀態】（{@link OrderSagaEventHandler#onMessage}）</p>
 * <pre>
 * 事件               TCC 預留票（帳戶庫，已先改）   Saga（訂單庫）                   訂單
 * ────────────────   ──────────────────────────   ─────────────────────────────   ───────
 * FUNDS_RESERVED     TRYING                       STARTED→ACCOUNT_TRYING
 *                                                 →ACCOUNT_CONFIRMING（再發 CONFIRM_FUNDS）   PENDING
 * FUNDS_CONFIRMED    CONFIRMED                    COMPLETED                        FILLED
 * FUNDS_FAILED       （沒有票：Try 就失敗）         COMPENSATING→COMPENSATED        FAILED
 * FUNDS_CANCELLED    CANCELLED                    COMPENSATING→COMPENSATED        FAILED
 * </pre>
 *
 * <p>【使用·正向與負向路徑】</p>
 * <pre>
 * SAGA-001 成功：   RESERVE_FUNDS → FUNDS_RESERVED → CONFIRM_FUNDS → FUNDS_CONFIRMED
 * SAGA-002 餘額不足：RESERVE_FUNDS → FUNDS_FAILED
 * TCC-002 故意補償：RESERVE_FUNDS → FUNDS_RESERVED → CONFIRM_FUNDS(forceFail) → FUNDS_CANCELLED
 * </pre>
 *
 * <p>【常見誤會】</p>
 * <ul>
 *   <li><b>{@link #CANCEL_FUNDS} 目前沒有人送</b>：帳戶側已實作處理，但訂單側的補償
 *       （{@code OrderMarkFailedAction}）只改本庫狀態、不發命令；TCC-002 的解凍是在
 *       {@code confirm(forceFail=true)} 內部直接 {@code cancel}。保留它是給「訂單側主動取消」的擴增點。</li>
 *   <li><b>{@link #FUNDS_FAILED} 與 {@link #FUNDS_CANCELLED} 在訂單側效果相同</b>（都走補償→COMPENSATED＋FAILED），
 *       差別只在帳戶側：FAILED＝連預留票都沒有；CANCELLED＝票存在且已解凍。
 *       要知道「錢到底有沒有被凍結過」請查 {@code tcc_reservations}，不要看事件名猜。</li>
 *   <li>事件型別<b>不是</b> Saga 狀態：{@code FUNDS_CONFIRMED} 是「帳戶說扣好了」，
 *       訂單側收到並寫入後，Saga 才變 {@code COMPLETED}；中間可能短暫對不上（最終一致）。</li>
 *   <li>同一事件可能被送兩次（Kafka at-least-once）：接收端靠 Saga 終態／預留票狀態判斷後直接 return，保證冪等。</li>
 * </ul>
 *
 * <p>【技巧】用 {@code public static final String} 而非 enum：Kafka JSON 直接放字串，
 * <br>未知型別（例如未來新版多送一種）接收端 if-else 不命中就忽略，不會因反序列化 enum 失敗而整批卡住。</p>
 *
 * @see SagaMessage
 * @see AccountCommandHandler
 * @see OrderSagaEventHandler
 * @see com.trading.saga.saga.SagaOrchestrator
 */
public final class SagaMessageTypes {

    /**
     * 【命令】請帳戶側做 TCC <b>Try</b>：從 available 凍結 amount 到 frozen，並寫一張 TRYING 預留票。
     * <p>【誰送】{@code SagaOrchestrator.start}（下單時，與訂單／Saga 同一 Local TX 寫入 Outbox）。
     * <p>【誰收】{@link AccountCommandHandler} → {@code tryReserve}。
     * <p>【回覆】成功 {@link #FUNDS_RESERVED}；餘額不足 {@link #FUNDS_FAILED}（不寫預留票）。
     */
    public static final String RESERVE_FUNDS = "RESERVE_FUNDS";

    /**
     * 【命令】請帳戶側做 TCC <b>Confirm</b>：消耗凍結款（frozen 減少，真正扣款），預留票 → CONFIRMED。
     * <p>【誰送】{@code OrderSagaEventHandler.onReserved}（收到 {@link #FUNDS_RESERVED} 後寫 Outbox）。
     * <p>【誰收】{@link AccountCommandHandler} → {@code confirm(sagaId, forceFail)}。
     * <p>【回覆】成功 {@link #FUNDS_CONFIRMED}；{@code forceFail=true} 時帳戶側改做 Cancel 並回 {@link #FUNDS_CANCELLED}。
     * <p>【注意】程式雖保留「失敗回 {@link #FUNDS_FAILED}」分支，但目前 {@code confirm} 只在 forceFail 時回 false，
     * <br>該分支屬防禦性寫法；查無預留票會丟例外，不會回事件。
     */
    public static final String CONFIRM_FUNDS = "CONFIRM_FUNDS";

    /**
     * 【命令】請帳戶側做 TCC <b>Cancel</b>：把凍結款退回 available，預留票 → CANCELLED（可安全重入）。
     * <p>【誰送】<b>目前無</b>（擴增點：訂單側主動取消／逾時取消時使用）。
     * <p>【誰收】{@link AccountCommandHandler} → {@code cancel}。
     * <p>【回覆】一律 {@link #FUNDS_CANCELLED}。
     */
    public static final String CANCEL_FUNDS = "CANCEL_FUNDS";

    /**
     * 【事件】Try 成功：錢已凍結、預留票 TRYING。
     * <p>【誰送】{@link AccountCommandHandler}（回覆 {@link #RESERVE_FUNDS}）。
     * <p>【誰收】{@code OrderSagaEventHandler.onReserved}：Saga → ACCOUNT_CONFIRMING，並經 Outbox 發 {@link #CONFIRM_FUNDS}。
     * <p>【冪等】Saga 已終態或已是 ACCOUNT_CONFIRMING → 直接 return，不重發 Confirm。
     */
    public static final String FUNDS_RESERVED = "FUNDS_RESERVED";

    /**
     * 【事件】Confirm 成功：真正扣款、預留票 CONFIRMED。
     * <p>【誰送】{@link AccountCommandHandler}（回覆 {@link #CONFIRM_FUNDS}）。
     * <p>【誰收】{@code OrderSagaEventHandler.onConfirmed}：訂單 → FILLED、Saga → COMPLETED（正向終點）。
     */
    public static final String FUNDS_CONFIRMED = "FUNDS_CONFIRMED";

    /**
     * 【事件】資金步驟失敗，<b>沒有預留票</b>（典型：Try 時餘額不足，錢從未被凍結）。
     * <p>【誰送】{@link AccountCommandHandler}（回覆 {@link #RESERVE_FUNDS} 失敗）。
     * <p>【誰收】{@code OrderSagaEventHandler} → {@code CompensationAction.compensate}：
     * <br>Saga → COMPENSATING → COMPENSATED、訂單 → FAILED。
     */
    public static final String FUNDS_FAILED = "FUNDS_FAILED";

    /**
     * 【事件】凍結款已退回：預留票 CANCELLED（典型：TCC-002 forceFail，或收到 {@link #CANCEL_FUNDS}）。
     * <p>【誰送】{@link AccountCommandHandler}（回覆 {@link #CONFIRM_FUNDS}＋forceFail，或 {@link #CANCEL_FUNDS}）。
     * <p>【誰收】{@code OrderSagaEventHandler} → {@code CompensationAction.compensate}（與 {@link #FUNDS_FAILED} 同路）。
     * <p>【注意】帳戶側的 CANCELLED（錢退了）≠ 訂單側的 COMPENSATED（流程收尾）；兩者由本事件串起。
     */
    public static final String FUNDS_CANCELLED = "FUNDS_CANCELLED";

    /** 【技巧】純常數類別：私有建構子防止被 new。 */
    private SagaMessageTypes() {
    }
}
