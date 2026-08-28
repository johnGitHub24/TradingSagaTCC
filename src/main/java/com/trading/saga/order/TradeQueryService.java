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
 * 【邊界】不啟動 Saga、不寫 Outbox。
 */
@Service
public class TradeQueryService {

    private final TradeOrderRepository orderRepository;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepRepository sagaStepRepository;

    /**
     * 建構查詢服務。
     */
    public TradeQueryService(TradeOrderRepository orderRepository,
                             SagaInstanceRepository sagaInstanceRepository,
                             SagaStepRepository sagaStepRepository) {
        this.orderRepository = orderRepository;
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaStepRepository = sagaStepRepository;
    }

    /**
     * @return 新到舊訂單
     */
    @Transactional(value = "orderTransactionManager", readOnly = true)
    public List<TradeResponse> listOrders() {
        return orderRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(TradeResponse::from)
                .toList();
    }

    /**
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
     * @param sagaId 流程 id
     * @return 含步驟
     */
    @Transactional(value = "orderTransactionManager", readOnly = true)
    public SagaResponse getSaga(String sagaId) {
        var saga = sagaInstanceRepository.findById(sagaId)
                .orElseThrow(() -> new ResourceNotFoundException("Saga not found: " + sagaId));
        var steps = sagaStepRepository.findBySagaIdOrderByAtAscIdAsc(sagaId);
        return SagaResponse.from(saga, steps);
    }
}
