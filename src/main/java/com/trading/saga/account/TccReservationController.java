package com.trading.saga.account;

import com.trading.saga.account.dto.TccReservationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 【職責】TCC 預留票唯讀查詢；前台 Dashboard 用來畫「TCC 資金狀態機」（Case TCC-001）。
 * 【概念】預留票住在帳戶庫，訂單側看不到；Saga 只能從 Kafka 事件間接得知結果。
 * 前台同時拉 Saga（訂單庫）與預留票（帳戶庫），就能親眼看到「預留票先變、Saga 後跟」的最終一致。
 * 【邊界】獨立路徑 {@code /api/v1/tcc/reservations}，不掛在 {@code /accounts/{accountId}} 底下：
 * 預留票以 sagaId 為鍵，不屬於某個帳戶路徑。
 *
 * <p>【怎麼運作】{@code @RestController} + 建構子注入 {@link AccountQueryService}（與 {@link AccountController} 相同 DI）。
 */
@RestController
@RequestMapping("/api/v1/tcc/reservations")
public class TccReservationController {

    private final AccountQueryService accountQueryService;

    /**
     * 【職責】注入帳戶庫查詢服務（建構子注入）。
     *
     * @param accountQueryService 帳戶庫唯讀查詢
     */
    public TccReservationController(AccountQueryService accountQueryService) {
        this.accountQueryService = accountQueryService;
    }

    /**
     * 【職責】查某 Saga 的預留票。
     * 【使用】{@code GET /api/v1/tcc/reservations/{sagaId}} → 一律 200；無票時 {@code exists=false}。
     *
     * @param sagaId 流程 id
     * @return 預留票 DTO
     */
    @GetMapping("/{sagaId}")
    public TccReservationResponse get(@PathVariable String sagaId) {
        return accountQueryService.getReservation(sagaId);
    }
}
