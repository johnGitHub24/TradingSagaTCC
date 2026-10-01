package com.trading.saga.order;

import com.trading.saga.account.AccountQueryService;
import com.trading.saga.messaging.EventLogService;
import com.trading.saga.order.dto.DemoStateResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 【職責】Demo 聚合讀取，減少前台來回。
 * 【技巧】一個 GET 把帳戶、訂單、Kafka 軌跡三份查詢包成 {@link DemoStateResponse}；
 *         前台 {@code static/app.js} 輪詢 {@code /api/v1/demo/state} 刷新畫面。
 * 【概念】薄 Controller：只做 HTTP 轉換（組回應），不含商業規則、不直接碰 Repository；
 *         三份資料各自由 Service 以自己的交易讀取。
 * 【邊界】唯讀；帳戶固定為種子 {@code ACC-001}，不支援指定其他帳戶。
 *         三次讀取分屬帳戶庫、訂單庫與記憶體，不是同一時間點的一致快照（Saga 進行中可能短暫對不上）。
 *
 * <p>【怎麼運作】{@code @RestController} + 建構子注入三個 Service（帳戶／訂單／事件軌跡）。
 */
@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {

    private final AccountQueryService accountQueryService;
    private final TradeQueryService tradeQueryService;
    private final EventLogService eventLogService;

    /**
     * 【職責】注入 Demo 所需查詢 Bean（建構子注入）。
     *
     * @param accountQueryService 帳戶庫唯讀查詢
     * @param tradeQueryService   訂單庫唯讀查詢
     * @param eventLogService     記憶體 Kafka 軌跡
     */
    public DemoController(AccountQueryService accountQueryService,
                          TradeQueryService tradeQueryService,
                          EventLogService eventLogService) {
        this.accountQueryService = accountQueryService;
        this.tradeQueryService = tradeQueryService;
        this.eventLogService = eventLogService;
    }

    /**
     * 【職責】種子帳戶＋訂單＋事件。
     * 【使用】{@code GET /api/v1/demo/state}；成功 200。
     *         種子帳戶不存在時 {@code AccountQueryService.get} 拋 {@code ResourceNotFoundException} → 404。
     *
     * @return {@code account}（ACC-001 餘額）、{@code orders}（新到舊）、{@code events}（最多 100 筆，新到舊）
     */
    @GetMapping("/state")
    public DemoStateResponse state() {
        return new DemoStateResponse(
                accountQueryService.get(AccountQueryService.SEED_ACCOUNT_ID),
                tradeQueryService.listOrders(),
                eventLogService.list()
        );
    }
}
