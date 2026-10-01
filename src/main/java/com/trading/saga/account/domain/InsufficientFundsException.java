package com.trading.saga.account.domain;

import java.math.BigDecimal;

/**
 * 【職責】帳戶資金不足時的領域例外，供 Try 階段拒絕預留。
 * 【技巧】帶 accountId／requested／available，方便 API 與測試斷言同一語意。
 * 繼承 {@link RuntimeException}（unchecked）：若一路穿出 {@code @Transactional} 方法邊界，預設會觸發 rollback；
 * 也不必在每層方法簽名宣告 throws。（本版在 TX 方法內就被捕捉，不會造成 rollback。）
 * 【概念】這不是 HTTP 錯誤，而是「業務上的正常失敗」。目前唯一拋出點是 {@link Account#tryReserve}，
 * 唯一捕捉點是 {@code AccountTccService.tryReserve}：捕捉後回 false（不寫預留票），
 * 再由 {@code AccountCommandHandler} 轉成 {@code FUNDS_FAILED} 事件 → 訂單側補償（SAGA-002）。
 * 所以下單 HTTP 仍是 202，使用者是從 Saga COMPENSATED＋訂單 FAILED 得知餘額不足。
 * 【邊界】不修改帳戶餘額；{@code Account.tryReserve} 在扣 available 之前就檢查並拋出，所以丟出時餘額保證未變。
 * {@code GlobalExceptionHandler} 目前沒有專屬對應；若日後在同步 API 直接拋出而未捕捉，會落到兜底 500。
 */
public class InsufficientFundsException extends RuntimeException {

    private final String accountId;
    private final BigDecimal requested;
    private final BigDecimal available;

    /**
     * 【職責】建立例外並組出可讀訊息（含三個數值，方便 log 直接判讀）。
     *
     * @param accountId 帳戶代號
     * @param requested 欲預留金額
     * @param available 當下可用餘額
     */
    public InsufficientFundsException(String accountId, BigDecimal requested, BigDecimal available) {
        super("Insufficient funds for " + accountId + ": requested=" + requested + " available=" + available);
        this.accountId = accountId;
        this.requested = requested;
        this.available = available;
    }

    /** @return 資金不足的帳戶代號 */
    public String getAccountId() {
        return accountId;
    }

    /** @return 這次想凍結的金額 */
    public BigDecimal getRequested() {
        return requested;
    }

    /** @return 拋出當下的可用餘額（小於 requested） */
    public BigDecimal getAvailable() {
        return available;
    }
}
