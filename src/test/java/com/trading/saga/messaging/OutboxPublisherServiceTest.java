package com.trading.saga.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.saga.order.domain.OutboxEvent;
import com.trading.saga.order.infrastructure.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 【職責】Outbox 單元層：OUTBOX-001 append 為 unpublished，publishPending 才送 Kafka。
 * <br>覆蓋 {@link OutboxPublisherService} 的兩個公開方法：
 * <ul>
 *   <li>{@code append}：只在訂單庫 {@code outbox_events} 存一列 {@code publishedAt == null} 的信，不碰 Kafka。</li>
 *   <li>{@code publishPending}：撈最多 50 筆未發送列 → 反序列化 → 送 Kafka → {@code markPublished()}。</li>
 * </ul>
 * 與整合層 {@code TradeSagaIntegrationTest#outbox_reachesKafkaTrail} 成對（同一 Case ID OUTBOX-001）。
 * <p>【技巧】
 * <ul>
 *   <li>{@code @Spy ObjectMapper}：用真的 Jackson 做序列化／反序列化（payload 是真 JSON），
 *       又能被 {@code @InjectMocks} 當依賴塞進建構子。</li>
 *   <li>{@code findAndRegisterModules()}：自動註冊 classpath 上的 JavaTimeModule，
 *       否則 {@link SagaMessage#occurredAt()}（{@code Instant}）無法序列化。</li>
 *   <li>{@code @InjectMocks}：Mockito 找最多參數的建構子，依型別把上面的 mock／spy 傳進去，
 *       等同 {@code new OutboxPublisherService(outboxEventRepository, objectMapper, kafkaMessageSender)}。</li>
 * </ul>
 * <p>【概念】Outbox（發件匣）解決「DB 寫成功但 Kafka 送失敗」的雙寫不一致：業務交易只把信寫進本庫表，
 * <br>交易提交後再由排程 {@link OutboxRelayJob} 呼叫 {@code publishPending} 寄出。
 * <br>本測試沒有 Spring，所以 {@code @Transactional} 不生效，也沒有排程；直接呼叫方法觀察結果即可。
 */
// 啟用 Mockito：處理 @Mock／@Spy／@InjectMocks，並在每個 Test 結束檢查是否有多餘的 stub（嚴格模式）
@ExtendWith(MockitoExtension.class)
// 測試報告／IDE 上顯示的名稱
@DisplayName("OutboxPublisherService unit")
class OutboxPublisherServiceTest {

    // 發件匣 Repository（訂單庫 outbox_events）：假的，不會真的寫 DB；用來捕捉 save 的內容、或 stub 待發送清單
    @Mock
    private OutboxEventRepository outboxEventRepository;
    // 真的 ObjectMapper 外面包一層 Spy：方法照常執行（真的轉 JSON），必要時也可 verify；註冊 JavaTimeModule 以處理 Instant
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    // Kafka 發送埠：假的，send 不做事；只用來 verify 送了哪個 topic／key／訊息
    @Mock
    private KafkaMessageSender kafkaMessageSender;
    // 被測物件：Mockito 以建構子注入上面三個依賴
    @InjectMocks
    private OutboxPublisherService publisherService;

    /** OUTBOX-001：Given 一則 RESERVE_FUNDS 命令，When append，Then 存成一列 unpublished、topic 正確、payload 為該命令 JSON。 */
    @Test
    @DisplayName("OUTBOX-001: append stores unpublished payload")
    void append_unpublished() {
        // ===== Given：準備要放進發件匣的命令 =====
        // 與 SagaOrchestrator.start 寫入的命令同形：sagaId=saga-1、orderId=order-1、ACC-001、RESERVE_FUNDS、金額 10000、BTCUSDT、forceFail=false
        SagaMessage message = SagaMessage.of(
                "saga-1", "order-1", "ACC-001", SagaMessageTypes.RESERVE_FUNDS,
                new BigDecimal("10000"), "BTCUSDT", false);

        // ===== When：把命令放進發件匣 =====
        // topic＝命令 topic（同 application.yml trading.kafka.command-topic）、key＝sagaId；內部會 writeValueAsString 再 save
        publisherService.append("trading.saga.commands", "saga-1", message);

        // ===== Then：檢查存進 outbox_events 的那一列 =====
        // 建立捕捉器，抓出 outboxEventRepository.save() 收到的 OutboxEvent
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        // 驗證 save 剛好被呼叫 1 次，並捕捉傳入的實體
        verify(outboxEventRepository).save(captor.capture());
        // publishedAt 為 null＝還在匣裡、尚未寄出；append 絕不能直接送 Kafka
        assertThat(captor.getValue().isUnpublished()).isTrue();
        // 這封信之後要寄往的 topic 必須原樣保存
        assertThat(captor.getValue().getTopic()).isEqualTo("trading.saga.commands");
        // payload 是 SagaMessage 的 JSON 字串，裡面的 "type" 欄位值應含 RESERVE_FUNDS
        assertThat(captor.getValue().getPayload()).contains("RESERVE_FUNDS");
    }

    /** OUTBOX-001：Given 匣中有一列未發送的 RESERVE_FUNDS，When publishPending，Then 以原 topic／key 送 Kafka 且該列被標為已發送。 */
    @Test
    @DisplayName("OUTBOX-001: publishPending sends Kafka then marks published")
    void publishPending_sends() throws Exception {
        // ===== Given：發件匣裡已有一封未寄出的信 =====
        // 準備與上一個 Test 相同的 RESERVE_FUNDS 命令
        SagaMessage message = SagaMessage.of(
                "saga-1", "order-1", "ACC-001", SagaMessageTypes.RESERVE_FUNDS,
                new BigDecimal("10000"), "BTCUSDT", false);
        // 用真的 ObjectMapper 把命令轉成 JSON，包成一列 unpublished 的 OutboxEvent（模擬 append 留下的資料）
        // writeValueAsString 會宣告 JsonProcessingException，所以本方法標 throws Exception
        OutboxEvent event = OutboxEvent.unpublished(
                "trading.saga.commands", "saga-1", objectMapper.writeValueAsString(message));
        // 讓 Repository 的「撈前 50 筆 publishedAt 為 null、依 id 由小到大」回傳只含這一列的清單
        given(outboxEventRepository.findTop50ByPublishedAtIsNullOrderByIdAsc()).willReturn(List.of(event));

        // ===== When：模擬排程 OutboxRelayJob.tick 觸發一次轉送 =====
        // 內部流程：撈清單 → readValue 還原 SagaMessage → kafkaMessageSender.send → event.markPublished()
        publisherService.publishPending();

        // ===== Then①：檢查真的送到 Kafka，且內容與原命令一致 =====
        // 建立捕捉器，抓出 send() 第 3 個參數（從 JSON 還原的 SagaMessage）
        ArgumentCaptor<SagaMessage> sent = ArgumentCaptor.forClass(SagaMessage.class);
        // 驗證 send 被呼叫 1 次：topic 與 key 必須取自那一列的 topic／messageKey
        verify(kafkaMessageSender).send(eq("trading.saga.commands"), eq("saga-1"), sent.capture());
        // JSON 來回轉換後，訊息類型仍是 RESERVE_FUNDS
        assertThat(sent.getValue().type()).isEqualTo(SagaMessageTypes.RESERVE_FUNDS);
        // sagaId 也要完整保留，帳戶側才能以它當 TCC 預留票的冪等 Key
        assertThat(sent.getValue().sagaId()).isEqualTo("saga-1");

        // ===== Then②：檢查該列已標記為已發送 =====
        // markPublished() 直接改這個 Java 物件的 publishedAt（正式環境靠 JPA dirty checking 在交易提交時寫回 DB）；
        // 因為 stub 回傳的是同一個 event 實例，這裡可直接讀狀態。註：本 Test 驗證「送出且已標記」，不驗證兩者先後順序
        assertThat(event.isUnpublished()).isFalse();
    }
}
