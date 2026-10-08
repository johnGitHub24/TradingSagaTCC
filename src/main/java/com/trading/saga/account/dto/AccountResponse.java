package com.trading.saga.account.dto;

import com.trading.saga.account.domain.Account;

import java.math.BigDecimal;

/**
 * 【職責】帳戶餘額對外 DTO：把 accountdb {@link Account} 投影成 JSON，不讓 JPA 實體直接外露。
 * <p>【技巧】record＋靜態工廠 {@link #from}；total 由實體 {@code total()} 即時計算後放進 DTO，資料庫本身不存 total 欄位。
 * <p>【概念】看 TCC 效果就看這三個數：Try 後 available↓、frozen↑、total 不變；Confirm 後 frozen↓、total↓（真的扣款）；
 * <br>Cancel 後 frozen↓、available↑、total 不變（錢退回）。
 * <p>【使用】{@code AccountQueryService.get／reset}（{@code GET /api/v1/accounts/{id}}、{@code POST .../reset}）、
 * <br>{@link com.trading.saga.order.dto.DemoStateResponse#account()}；Case ACCOUNT-001。
 *
 * @param accountId 帳戶代號
 * @param available 可用餘額（可再被 Try 凍結的部分）
 * @param frozen    凍結中金額（已 Try、尚未 Confirm 或 Cancel 的預留總和）
 * @param total     名義總額＝available＋frozen
 */
public record AccountResponse(
        String accountId,
        BigDecimal available,
        BigDecimal frozen,
        BigDecimal total
) {
    /**
     * 【職責】從帳戶實體投影成 DTO（含計算後的 total）。
     * <p>【邊界】純讀取，不改實體；呼叫端應在 account TX 內呼叫以取得一致的 available／frozen。
     *
     * @param account 帳戶實體
     * @return 對外 DTO
     */
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getAccountId(),
                account.getAvailable(),
                account.getFrozen(),
                account.total()
        );
    }
}
