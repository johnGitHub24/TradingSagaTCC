package com.trading.saga.order;

import com.trading.saga.account.AccountQueryService;
import com.trading.saga.messaging.EventLogService;
import com.trading.saga.order.dto.DemoStateResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 【職責】Demo 聚合讀取，減少前台來回。
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
     */
    public DemoController(AccountQueryService accountQueryService,
                          TradeQueryService tradeQueryService,
                          EventLogService eventLogService) {
        this.accountQueryService = accountQueryService;
        this.tradeQueryService = tradeQueryService;
        this.eventLogService = eventLogService;
    }

    /**
     * 種子帳戶＋訂單＋事件。
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
