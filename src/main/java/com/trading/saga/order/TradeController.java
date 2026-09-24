package com.trading.saga.order;

import com.trading.saga.order.dto.SagaResponse;
import com.trading.saga.order.dto.TradeRequest;
import com.trading.saga.order.dto.TradeResponse;
import com.trading.saga.saga.SagaOrchestrator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 【職責】HTTP 轉下單／查單／查 Saga；禁止碰 Repository。
 * 【技巧】寫入走 {@link SagaOrchestrator}；讀取走 {@link TradeQueryService}。
 * 【使用】前台 {@code app.js}／Smoke／Swagger 都打 {@code /api/v1/...}。
 *
 * <p>【怎麼運作】{@code @RestController} → Spring MVC 登錄路由；建構子注入兩個 Service Bean，
 * 沒有 {@code new SagaOrchestrator(...)}。請求進來 → 方法 → 轉交 Service。
 */
@RestController
@RequestMapping("/api/v1")
public class TradeController {

    private final SagaOrchestrator sagaOrchestrator;
    private final TradeQueryService tradeQueryService;

    /**
     * 【職責】注入編排與查詢（建構子注入）。
     * 【概念】Controller 薄：只轉呼叫，不自己組依賴。
     */
    public TradeController(SagaOrchestrator sagaOrchestrator, TradeQueryService tradeQueryService) {
        this.sagaOrchestrator = sagaOrchestrator;
        this.tradeQueryService = tradeQueryService;
    }

    /**
     * 【職責】啟動 Saga，回 202＋訂單快照（多為 PENDING）。
     * 【概念】202＝已接受編排，≠扣款完成。
     * 【使用】
     * <pre>
     * POST /api/v1/trades
     * {"accountId":"ACC-001","symbol":"BTCUSDT","side":"BUY","quantity":1,"price":10000,"forceFail":false}
     * → 202；body.sagaId 用於 GET /sagas/{id} 輪詢
     * </pre>
     *
     * @param request 經 {@code @Valid} 驗證
     * @return 202 Accepted
     */
    @PostMapping("/trades")
    public ResponseEntity<TradeResponse> place(@Valid @RequestBody TradeRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(sagaOrchestrator.start(request));
    }

    /**
     * 【職責】訂單列表（新到舊）。
     * 【使用】{@code GET /api/v1/trades}；Demo 面板刷新用。
     */
    @GetMapping("/trades")
    public List<TradeResponse> list() {
        return tradeQueryService.listOrders();
    }

    /**
     * 【職責】單筆訂單；不存在 → 404。
     * 【使用】Case TRADE-001：{@code GET /api/v1/trades/missing-order}。
     *
     * @param orderId 訂單 id
     */
    @GetMapping("/trades/{orderId}")
    public TradeResponse get(@PathVariable String orderId) {
        return tradeQueryService.getOrder(orderId);
    }

    /**
     * 【職責】Saga 狀態＋步驟時間軸。
     * 【使用】前台 {@code pollSaga(sagaId)}；終態 COMPLETED／COMPENSATED／FAILED。
     *
     * @param sagaId 流程 id（下單回傳）
     */
    @GetMapping("/sagas/{sagaId}")
    public SagaResponse getSaga(@PathVariable String sagaId) {
        return tradeQueryService.getSaga(sagaId);
    }
}
