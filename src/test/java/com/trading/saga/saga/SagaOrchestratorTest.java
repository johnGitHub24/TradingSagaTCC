package com.trading.saga.saga;

import com.trading.saga.account.AccountLookup;
import com.trading.saga.messaging.OutboxPort;
import com.trading.saga.messaging.SagaMessage;
import com.trading.saga.messaging.SagaMessageTypes;
import com.trading.saga.order.domain.OrderStatus;
import com.trading.saga.order.domain.SagaInstance;
import com.trading.saga.order.domain.SagaStatus;
import com.trading.saga.order.domain.TradeOrder;
import com.trading.saga.order.dto.TradeRequest;
import com.trading.saga.order.dto.TradeResponse;
import com.trading.saga.order.infrastructure.SagaInstanceRepository;
import com.trading.saga.order.infrastructure.SagaStepRepository;
import com.trading.saga.order.infrastructure.TradeOrderRepository;
import com.trading.saga.support.SagaTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 【職責】{@link SagaOrchestrator} 單元層：SAGA-001／OUTBOX-001 同一契約（寫 Outbox RESERVE_FUNDS）。
 * <p>【技巧】Request 來自 {@code docs/test-data/trade/SAGA-001-SUCCESS.json}。
 */
// 啟用 Mockito：自動建立下方 @Mock 欄位，並在每個 Test 結束檢查是否有多餘的 stub（嚴格模式）
@ExtendWith(MockitoExtension.class)
// 測試報告／IDE 上顯示的名稱
@DisplayName("SagaOrchestrator unit")
class SagaOrchestratorTest {

    // 帳戶查詢：真實環境會查帳戶庫；這裡是假的，預設什麼都不做（不拋例外＝帳戶存在）
    @Mock
    private AccountLookup accountLookup;
    // 訂單 Repository（訂單庫）：假的，不會真的寫 DB
    @Mock
    private TradeOrderRepository orderRepository;
    // Saga 主檔 Repository：記錄整筆 Saga 目前走到哪個狀態
    @Mock
    private SagaInstanceRepository sagaInstanceRepository;
    // Saga 步驟 Repository：記錄每一步的流水帳（ORDER_CREATED、RESERVE_COMMANDED…）
    @Mock
    private SagaStepRepository sagaStepRepository;
    // Outbox 寫入埠：真實環境寫 outbox 表，之後由 OutboxRelayJob 送 Kafka；這裡只用來驗證有被呼叫
    @Mock
    private OutboxPort outboxPort;

    // 被測物件：不用 @InjectMocks，改在 setUp() 手動 new，建構參數一目了然
    private SagaOrchestrator orchestrator;

    /**
     * 【職責】每個 Test 前以 mock 依賴手動組裝 {@link SagaOrchestrator}，並讓兩個 Repository 的
     * <br>{@code save} 行為貼近真實 JPA（存什麼就回傳什麼）。
     * <p>【技巧】{@code given(...).willAnswer(inv -> inv.getArgument(0))}：
     * <ul>
     *   <li>{@code given(...)}：BDDMockito 寫法，等同 {@code when(...)}，語意為「在這個前提下」。</li>
     *   <li>{@code any(X.class)}：參數比對器，不論傳入哪一個 X 實例都符合此 stub。</li>
     *   <li>{@code willAnswer(...)}：非固定回傳值，而是每次呼叫時才執行 lambda 計算結果。</li>
     *   <li>{@code inv}：{@code InvocationOnMock}，代表本次呼叫；{@code getArgument(0)} 取第 1 個參數。</li>
     * </ul>
     * <p>【概念】{@code @Mock} 未設定的方法對物件型別一律回傳 {@code null}；若被測程式改寫成
     * <br>{@code order = orderRepository.save(order)} 便會 NPE。實體是在 {@code start()} 內部才 new 出來，
     * <br>測試拿不到參考，無法用 {@code willReturn(固定物件)}，故以「回傳傳入參數」保證同一實例，
     * <br>後續 {@code ArgumentCaptor} 檢查到的狀態即為被測程式實際寫入的值。
     * <br>在 {@code MockitoExtension} 嚴格模式下，只要 {@code save} 有被呼叫即視為已使用，
     * <br>即使回傳值未被接收也不會拋 {@code UnnecessaryStubbingException}。
     */
    @BeforeEach
    void setUp() {
        // 把 5 個 mock 依賴＋命令 topic 名稱塞進建構子
        // 第 6 個參數 commandTopic 的來源：
        //   正式執行：建構子上 @Value("${trading.kafka.command-topic}") → application.yml
        //            trading.kafka.command-topic: trading.saga.commands
        //   本單元測試：只有 Mockito、沒啟動 Spring，@Value 不生效、也不讀 application.yml，
        //            所以手動傳入字串；刻意與設定檔同值以貼近真實，下方 verify 的 eq(...) 必須與此一致
        orchestrator = new SagaOrchestrator(
                accountLookup, orderRepository, sagaInstanceRepository, sagaStepRepository,
                outboxPort, "trading.saga.commands");
        // 任何 TradeOrder 傳入 save → 原樣回傳該 TradeOrder（模擬 JPA save 回傳已存實體）
        given(orderRepository.save(any(TradeOrder.class))).willAnswer(inv -> inv.getArgument(0));
        // 同上，對象換成 SagaInstance
        given(sagaInstanceRepository.save(any(SagaInstance.class))).willAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("SAGA-001 / OUTBOX-001: start appends RESERVE_FUNDS outbox and returns PENDING")
    void start_appendsReserveCommand() {
        // ===== Given：準備輸入 =====
        // 讀 docs/test-data/trade/SAGA-001-SUCCESS.json 轉成 TradeRequest
        // 內容：ACC-001 買 BTCUSDT，數量 1 × 價格 10000，forceFail=false（正常流程）
        TradeRequest request = SagaTestFixtures.loadDto("trade", "SAGA-001-SUCCESS", TradeRequest.class);

        // ===== When：執行被測方法 =====
        // 啟動 Saga：建立 PENDING 訂單、Saga 轉 ACCOUNT_TRYING、Outbox 登記 RESERVE_FUNDS 命令
        TradeResponse response = orchestrator.start(request);

        // ===== Then①：檢查回傳值 =====
        // 剛啟動時帳戶還沒扣款（要等 Kafka → TCC），所以訂單狀態必定是 PENDING
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        // 金額＝數量 × 價格＝1 × 10000；BigDecimal 用 isEqualByComparingTo 比數值，忽略小數位數（10000 vs 10000.00）
        assertThat(response.amount()).isEqualByComparingTo("10000");

        // ===== Then②：檢查 Outbox 有寫入「預留資金」命令 =====
        // 建立參數捕捉器，用來抓出 append() 第 3 個參數（SagaMessage）實際內容
        ArgumentCaptor<SagaMessage> captor = ArgumentCaptor.forClass(SagaMessage.class);
        // 驗證 append 被呼叫剛好 1 次：topic 必須是命令 topic、key（sagaId 隨機 UUID）不在乎、訊息交給 captor 捕捉
        // "trading.saga.commands" 即 setUp() 建構子第 6 個參數；start() 內以 commandTopic 原樣傳給 append()
        // 若 setUp() 改傳別的字串，這裡也要同步改，否則驗證失敗
        verify(outboxPort).append(eq("trading.saga.commands"), any(), captor.capture());
        // 捕捉到的訊息類型必須是 RESERVE_FUNDS（請帳戶服務 Try 凍結資金）
        assertThat(captor.getValue().type()).isEqualTo(SagaMessageTypes.RESERVE_FUNDS);

        // ===== Then③：檢查有先確認帳戶存在 =====
        // 驗證 requireExists 以 JSON 裡的 accountId "ACC-001" 被呼叫過 1 次
        verify(accountLookup).requireExists("ACC-001");

        // ===== Then④：檢查 Saga 主檔存檔時的狀態 =====
        // 建立捕捉器，抓出 save() 收到的 SagaInstance
        ArgumentCaptor<SagaInstance> sagaCaptor = ArgumentCaptor.forClass(SagaInstance.class);
        // 驗證 sagaInstanceRepository.save 被呼叫 1 次，並捕捉傳入的 SagaInstance
        verify(sagaInstanceRepository).save(sagaCaptor.capture());
        // 存檔前 Saga 已從 STARTED 轉成 ACCOUNT_TRYING（正在等帳戶 TCC Try 的結果）
        assertThat(sagaCaptor.getValue().getStatus()).isEqualTo(SagaStatus.ACCOUNT_TRYING);
    }
}
