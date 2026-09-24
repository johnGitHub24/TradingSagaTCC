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
 *
 * <p>【怎麼運作】
 * <ol>
 *   <li>註冊：{@code @Service} → Spring 建立本 Bean。</li>
 *   <li>注入：建構子要 {@link AccountLookup}（實作＝{@link com.trading.saga.account.AccountQueryService}）、
 *       三個訂單庫 Repository、{@link OutboxPort}（實作＝{@link com.trading.saga.messaging.OutboxPublisherService}）、
 *       以及 {@code @Value} 的 command topic。沒有無參建構子。</li>
 *   <li>進線：HTTP {@code POST /trades} → {@link #start}。</li>
 *   <li>同 TX：寫訂單＋Saga＋{@link OutboxPort#append}（發件匣 unpublished）。</li>
 *   <li>出線：真正 Kafka 由 {@link com.trading.saga.messaging.OutboxRelayJob} 稍後寄出。</li>
 * </ol>
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
     * 【職責】組裝編排所需埠（建構子注入，見類別上方【怎麼運作】）。
     * 【技巧】{@link AccountLookup}／{@link OutboxPort} 寫介面；Spring 找唯一實作。
     * 【概念】和 {@link com.trading.saga.messaging.OrderSagaEventHandler} 同一套 DI。
     *
     * @param accountLookup            啟動前確認帳戶存在（跨庫只讀）
     * @param orderRepository          訂單
     * @param sagaInstanceRepository   Saga 實例
     * @param sagaStepRepository       步驟軌跡
     * @param outboxPort               發件匣寫入
     * @param commandTopic             Kafka command topic（{@code trading.kafka.command-topic}）
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
     * 【概念·局部全成或全敗】掛 {@code orderTransactionManager}：訂單＋Saga＋Outbox
     * 必須全部成功才 commit；任一失敗全部 rollback（像「全域」語感，但只限訂單庫＝Local TX）。
     * 此時帳戶尚未扣款——那是下一階 Kafka／TCC 用 {@code accountTransactionManager} 另開局部 TX。
     * 不是 XA（跨兩庫同一筆全域事務／2PC）；XA 名詞見 {@link com.trading.saga.config.OrderDataSourceConfig}。
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
