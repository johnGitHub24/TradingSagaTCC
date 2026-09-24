package com.trading.saga.order;

import com.trading.saga.common.ResourceNotFoundException;
import com.trading.saga.order.dto.SagaResponse;
import com.trading.saga.order.dto.TradeResponse;
import com.trading.saga.order.infrastructure.SagaInstanceRepository;
import com.trading.saga.order.infrastructure.SagaStepRepository;
import com.trading.saga.order.infrastructure.TradeOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 【職責】訂單／Saga 唯讀查詢（訂單庫）。
 * 【使用】由 {@link TradeController}／{@link DemoController} 呼叫；單元測試可 Mock Repository。
 * 【邊界】不啟動 Saga、不寫 Outbox。
 */
@Service
public class TradeQueryService {

    private final TradeOrderRepository orderRepository;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepRepository sagaStepRepository;

    /**
     * 【職責】注入訂單庫唯讀埠。
     */
    public TradeQueryService(TradeOrderRepository orderRepository,
                             SagaInstanceRepository sagaInstanceRepository,
                             SagaStepRepository sagaStepRepository) {
        this.orderRepository = orderRepository;
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaStepRepository = sagaStepRepository;
    }

    /**
     * 【職責】列出訂單（新到舊）。
     * 【使用】Demo 面板「訂單」表；{@code GET /api/v1/trades}。
     *
     * @return 訂單 DTO 列表
     */
    @Transactional(value = "orderTransactionManager", readOnly = true)
    public List<TradeResponse> listOrders() {
        return orderRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(TradeResponse::from)
                .toList();
    }

    /**
     * 【職責】依 id 取單筆；不存在拋 404。
     * 【使用】Case TRADE-001。
     * <pre>
     * tradeQueryService.getOrder("missing-order"); // → ResourceNotFoundException
     * </pre>
     *
     * @param orderId 訂單 id
     * @return DTO
     */
    @Transactional(value = "orderTransactionManager", readOnly = true)
    public TradeResponse getOrder(String orderId) {
        return orderRepository.findById(orderId)
                .map(TradeResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
    }

    /**
     * 【職責】取 Saga 狀態＋步驟時間軸。
     * 【使用】前台輪詢終態；{@code GET /api/v1/sagas/{sagaId}}。
     *
     * @param sagaId 流程 id
     * @return 含 steps
     */
    @Transactional(value = "orderTransactionManager", readOnly = true)
    public SagaResponse getSaga(String sagaId) {
        var saga = sagaInstanceRepository.findById(sagaId)
                .orElseThrow(() -> new ResourceNotFoundException("Saga not found: " + sagaId));
        var steps = sagaStepRepository.findBySagaIdOrderByAtAscIdAsc(sagaId);
        return SagaResponse.from(saga, steps);
    }
}
