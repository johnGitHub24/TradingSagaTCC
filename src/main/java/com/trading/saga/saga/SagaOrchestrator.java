package com.trading.saga.saga;

import com.trading.saga.account.AccountLookup;
import com.trading.saga.messaging.OutboxPort;
import com.trading.saga.messaging.SagaMessage;
import com.trading.saga.messaging.SagaMessageTypes;
import com.trading.saga.order.domain.SagaInstance;
import com.trading.saga.order.domain.SagaStatus;
import com.trading.saga.order.domain.SagaStep;
import com.trading.saga.order.domain.TradeOrder;
import com.trading.saga.order.dto.TradeRequest;
import com.trading.saga.order.dto.TradeResponse;
import com.trading.saga.order.infrastructure.SagaInstanceRepository;
import com.trading.saga.order.infrastructure.SagaStepRepository;
import com.trading.saga.order.infrastructure.TradeOrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 【職責】編排起點：在訂單庫一筆交易寫訂單＋Saga＋Outbox command。
 * 【技巧】先 {@link AccountLookup#requireExists}（只讀帳戶庫），再寫訂單庫；不開 XA。
 * 【概念】HTTP 只保證「Saga 已登記」；扣款成敗由後續 Kafka／TCC 決定。
 * 【邊界】不呼叫帳戶寫入、不直接 KafkaTemplate。
 */
@Service
public class SagaOrchestrator {

    public static final String STEP_ORDER_CREATED = "ORDER_CREATED";
    public static final String STEP_RESERVE_COMMANDED = "RESERVE_COMMANDED";

    private final AccountLookup accountLookup;
    private final TradeOrderRepository orderRepository;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepRepository sagaStepRepository;
    private final OutboxPort outboxPort;
    private final String commandTopic;

    /**
     * @param commandTopic {@code trading.kafka.command-topic}
     */
    public SagaOrchestrator(AccountLookup accountLookup,
                            TradeOrderRepository orderRepository,
                            SagaInstanceRepository sagaInstanceRepository,
                            SagaStepRepository sagaStepRepository,
                            OutboxPort outboxPort,
                            @Value("${trading.kafka.command-topic}") String commandTopic) {
        this.accountLookup = accountLookup;
        this.orderRepository = orderRepository;
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaStepRepository = sagaStepRepository;
        this.outboxPort = outboxPort;
        this.commandTopic = commandTopic;
    }

    /**
     * 啟動 Saga：訂單 PENDING，Outbox {@code RESERVE_FUNDS}。
     *
     * @param request 下單
     * @return 當下訂單快照（多為 PENDING）
     */
    @Transactional("orderTransactionManager")
    public TradeResponse start(TradeRequest request) {
        accountLookup.requireExists(request.accountId());
        String orderId = UUID.randomUUID().toString();
        String sagaId = UUID.randomUUID().toString();
        TradeOrder order = TradeOrder.pending(
                orderId, sagaId, request.accountId(), request.symbol(),
                request.side(), request.quantity(), request.price(), request.forceFailOrFalse());
        SagaInstance saga = SagaInstance.start(sagaId, orderId);
        saga.transitionTo(SagaStatus.ACCOUNT_TRYING);
        orderRepository.save(order);
        sagaInstanceRepository.save(saga);
        sagaStepRepository.save(SagaStep.of(sagaId, STEP_ORDER_CREATED, "order PENDING amount=" + order.getAmount()));
        SagaMessage command = SagaMessage.of(
                sagaId, orderId, request.accountId(), SagaMessageTypes.RESERVE_FUNDS,
                order.getAmount(), request.symbol(), request.forceFailOrFalse());
        outboxPort.append(commandTopic, sagaId, command);
        sagaStepRepository.save(SagaStep.of(sagaId, STEP_RESERVE_COMMANDED, SagaMessageTypes.RESERVE_FUNDS));
        return TradeResponse.from(order);
    }
}
