package com.trading.saga.messaging;

import com.trading.saga.expansion.CompensationAction;
import com.trading.saga.expansion.DomainEventConsumer;
import com.trading.saga.order.domain.OrderStatus;
import com.trading.saga.order.domain.SagaInstance;
import com.trading.saga.order.domain.SagaStatus;
import com.trading.saga.order.domain.SagaStep;
import com.trading.saga.order.domain.TradeOrder;
import com.trading.saga.order.infrastructure.SagaInstanceRepository;
import com.trading.saga.order.infrastructure.SagaStepRepository;
import com.trading.saga.order.infrastructure.TradeOrderRepository;
import com.trading.saga.common.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 【職責】訂單側消費帳戶事件：下 Confirm 命令、完成訂單、或觸發補償。
 * 【技巧】終態直接略過，避免 Kafka 重送把已完成 Saga 再轉一次。
 * 【概念】編排者只根據事件推進自己的庫（orderdb），不直接改帳戶庫。
 * 【使用】由 {@link SagaKafkaListeners#onEvent} 轉送進來。
 *
 * <p>【怎麼運作】從頭到腳：
 * <ol>
 *   <li>註冊：類別標 {@code @Service} → Spring 啟動時建立一個 Bean。</li>
 *   <li>注入：唯一建構子參數全部由容器塞入（建構子依賴注入）——
 *       Repository、{@link OutboxPort}、{@link CompensationAction}，
 *       以及 {@code @Value} 讀到的 command topic 字串。
 *       沒有無參建構子，也沒有 {@code new OrderSagaEventHandler(...)}。</li>
 *   <li>進線：Kafka 帳戶事件 → {@code SagaKafkaListeners.onEvent}
 *       → 本類 {@link #onMessage}（{@link DomainEventConsumer} 契約）。</li>
 *   <li>分派：依 {@code message.type()}——
 *       {@code FUNDS_RESERVED} → 寫 Outbox Confirm 命令；
 *       {@code FUNDS_CONFIRMED} → 訂單 FILLED、Saga COMPLETED；
 *       {@code FUNDS_FAILED}/{@code CANCELLED} → {@link CompensationAction#compensate}。</li>
 *   <li>出線：Confirm 不直接打 Kafka，而是 {@link OutboxPort#append} 進發件匣，
 *       再由 {@link OutboxRelayJob} 定時寄出（提交後發訊）。</li>
 * </ol>
 *
 * <p>【對照】和 {@link OutboxRelayJob} 一樣都是建構子注入；
 * Job 只要一個 {@link com.trading.saga.expansion.OutboxRelay}，
 * 本類要一組「編排推進」依賴，多一個 {@code @Value} 設定字串。
 */
@Service
public class OrderSagaEventHandler implements DomainEventConsumer {

    public static final String STEP_CONFIRM_COMMANDED = "CONFIRM_COMMANDED";
    public static final String STEP_COMPLETED = "SAGA_COMPLETED";

    private final SagaInstanceRepository sagaInstanceRepository;
    private final TradeOrderRepository orderRepository;
    private final SagaStepRepository sagaStepRepository;
    private final OutboxPort outboxPort;
    private final CompensationAction compensationAction;
    private final String commandTopic;

    /**
     * 【職責】組裝編排推進所需依賴（建構子注入，見類別上方【怎麼運作】第 2 步）。
     * 【技巧】前五個是 Spring Bean；最後一個用 {@code @Value} 從設定檔注入字串（不是 Bean）。
     * 【概念】Spring 看到唯一建構子 → 自動找齊參數再呼叫；你不用自己 new。
     *
     * @param sagaInstanceRepository Saga 實例（orderdb）
     * @param orderRepository        訂單（orderdb）
     * @param sagaStepRepository     步驟軌跡（orderdb）
     * @param outboxPort             發件匣寫入（實作通常為 {@link OutboxPublisherService}）
     * @param compensationAction     失敗／取消時補償
     * @param commandTopic           寫回 Confirm 命令用的 Kafka topic（{@code trading.kafka.command-topic}）
     */
    public OrderSagaEventHandler(SagaInstanceRepository sagaInstanceRepository,
                                 TradeOrderRepository orderRepository,
                                 SagaStepRepository sagaStepRepository,
                                 OutboxPort outboxPort,
                                 CompensationAction compensationAction,
                                 @Value("${trading.kafka.command-topic}") String commandTopic) {
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.orderRepository = orderRepository;
        this.sagaStepRepository = sagaStepRepository;
        this.outboxPort = outboxPort;
        this.compensationAction = compensationAction;
        this.commandTopic = commandTopic;
    }

    /**
     * 【職責】依 event type 分派：RESERVED→Confirm、CONFIRMED→完成、FAILED／CANCELLED→補償。
     * 【使用】對應正向 SAGA-001 與負向 SAGA-002／TCC-002。
     * <pre>
     * FUNDS_RESERVED   → onReserved → Outbox CONFIRM_FUNDS
     * FUNDS_CONFIRMED  → onConfirmed → FILLED + COMPLETED
     * FUNDS_FAILED|CANCELLED → compensate → FAILED + COMPENSATED
     * </pre>
     *
     * @param message 帳戶側事件信封
     */
    @Override
    @Transactional("orderTransactionManager")
    public void onMessage(SagaMessage message) {
        String type = message.type();
        if (SagaMessageTypes.FUNDS_RESERVED.equals(type)) {
            onReserved(message);
        } else if (SagaMessageTypes.FUNDS_CONFIRMED.equals(type)) {
            onConfirmed(message);
        } else if (SagaMessageTypes.FUNDS_FAILED.equals(type)
                || SagaMessageTypes.FUNDS_CANCELLED.equals(type)) {
            compensationAction.compensate(message.sagaId());
        }
    }

    /**
     * 【職責】Try 成功後推進至 ACCOUNT_CONFIRMING，並 Outbox 登記 CONFIRM_FUNDS。
     * 【技巧】已終態或已在 CONFIRMING → return（防重送）。
     * 【使用】僅由 {@link #onMessage} 在 FUNDS_RESERVED 時呼叫。
     */
    private void onReserved(SagaMessage message) {
        SagaInstance saga = requireSaga(message.sagaId());
        if (saga.getStatus().isTerminal() || saga.getStatus() == SagaStatus.ACCOUNT_CONFIRMING) {
            return;
        }
        if (saga.getStatus() == SagaStatus.STARTED) {
            saga.transitionTo(SagaStatus.ACCOUNT_TRYING);
        }
        saga.transitionTo(SagaStatus.ACCOUNT_CONFIRMING);
        TradeOrder order = requireOrder(saga.getOrderId());
        SagaMessage confirm = SagaMessage.of(
                saga.getSagaId(), order.getOrderId(), order.getAccountId(),
                SagaMessageTypes.CONFIRM_FUNDS, order.getAmount(), order.getSymbol(), order.isForceFail());
        outboxPort.append(commandTopic, saga.getSagaId(), confirm);
        sagaStepRepository.save(SagaStep.of(saga.getSagaId(), STEP_CONFIRM_COMMANDED, SagaMessageTypes.CONFIRM_FUNDS));
    }

    /**
     * 【職責】Confirm 成功：訂單 FILLED、Saga COMPLETED。
     * 【使用】僅由 {@link #onMessage} 在 FUNDS_CONFIRMED 時呼叫；前台應看到 COMPLETED＋FILLED。
     */
    private void onConfirmed(SagaMessage message) {
        SagaInstance saga = requireSaga(message.sagaId());
        if (saga.getStatus().isTerminal()) {
            return;
        }
        TradeOrder order = requireOrder(saga.getOrderId());
        order.markFilled();
        saga.transitionTo(SagaStatus.COMPLETED);
        sagaStepRepository.save(SagaStep.of(saga.getSagaId(), STEP_COMPLETED, OrderStatus.FILLED.name()));
    }

    /** 【使用】查不到 Saga 即視為契約錯誤（不應發生於正常流程）。 */
    private SagaInstance requireSaga(String sagaId) {
        return sagaInstanceRepository.findById(sagaId)
                .orElseThrow(() -> new ResourceNotFoundException("Saga not found: " + sagaId));
    }

    /** 【使用】查不到訂單即視為契約錯誤。 */
    private TradeOrder requireOrder(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
    }
}
