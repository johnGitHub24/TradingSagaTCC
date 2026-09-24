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
 * 【使用】僅由 {@code TradeController.place} 呼叫；勿在 Job／Listener 重複 start 同一業務意圖。
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
     * 【職責】組裝編排所需埠。
     * 【使用】Spring 注入；{@code commandTopic} 來自 {@code trading.kafka.command-topic}。
     *
     * @param commandTopic Kafka command topic 名稱
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
     * 【職責】啟動一筆交易 Saga：訂單 PENDING、Saga ACCOUNT_TRYING、Outbox 登記 RESERVE_FUNDS。
     * 【技巧】同 TX 寫 order／saga／outbox；真正 Kafka 發送交給 {@code OutboxRelayJob}。
     * 【概念】回傳的 status 幾乎一定是 PENDING；完成與否要輪詢 Saga／訂單。
     * 【使用】對應 Case SAGA-001／002／TCC-002／OUTBOX-001 的入口。
     * <pre>
     * TradeResponse r = orchestrator.start(new TradeRequest(
     *     "ACC-001", "BTCUSDT", "BUY", BigDecimal.ONE, new BigDecimal("10000"), false));
     * // r.status() == PENDING；之後等 Outbox → Kafka → TCC
     * </pre>
     *
     * @param request 下單（含可選 forceFail）
     * @return 當下訂單快照（含 sagaId／orderId，多為 PENDING）
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
