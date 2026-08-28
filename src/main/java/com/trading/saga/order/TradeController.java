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
 */
@RestController
@RequestMapping("/api/v1")
public class TradeController {

    private final SagaOrchestrator sagaOrchestrator;
    private final TradeQueryService tradeQueryService;

    /**
     * 建構控制器。
     */
    public TradeController(SagaOrchestrator sagaOrchestrator, TradeQueryService tradeQueryService) {
        this.sagaOrchestrator = sagaOrchestrator;
        this.tradeQueryService = tradeQueryService;
    }

    /**
     * 啟動 Saga，202。
     */
    @PostMapping("/trades")
    public ResponseEntity<TradeResponse> place(@Valid @RequestBody TradeRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(sagaOrchestrator.start(request));
    }

    /**
     * 訂單列表。
     */
    @GetMapping("/trades")
    public List<TradeResponse> list() {
        return tradeQueryService.listOrders();
    }

    /**
     * 單筆訂單。
     */
    @GetMapping("/trades/{orderId}")
    public TradeResponse get(@PathVariable String orderId) {
        return tradeQueryService.getOrder(orderId);
    }

    /**
     * Saga 時間軸。
     */
    @GetMapping("/sagas/{sagaId}")
    public SagaResponse getSaga(@PathVariable String sagaId) {
        return tradeQueryService.getSaga(sagaId);
    }
}
