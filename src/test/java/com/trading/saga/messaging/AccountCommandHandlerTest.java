package com.trading.saga.messaging;

import com.trading.saga.expansion.TccResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 【職責】帳戶 command handler 單元：三種命令各自回覆正確的結果事件。
 * <br>覆蓋 {@link AccountCommandHandler} 的全部分支（帳戶側 TCC 參與者）：
 * <ul>
 *   <li>{@code RESERVE_FUNDS}：SAGA-001（Try 成功 → {@code FUNDS_RESERVED}）、SAGA-002（Try 失敗 → {@code FUNDS_FAILED}）。</li>
 *   <li>{@code CONFIRM_FUNDS}：SAGA-001（Confirm 成功 → {@code FUNDS_CONFIRMED}）、
 *       TCC-002（forceFail → {@code FUNDS_CANCELLED}）、Confirm 失敗且非 forceFail → {@code FUNDS_FAILED}。</li>
 *   <li>{@code CANCEL_FUNDS}：一律 Cancel 後回 {@code FUNDS_CANCELLED}。</li>
 * </ul>
 * <p>【技巧】
 * <ul>
 *   <li>{@link TccResource}／{@link KafkaMessageSender} 都是介面，直接 {@code @Mock}；
 *       Handler 不用 {@code @InjectMocks}，改在每個 Test 內手動 new，第 3 個參數 event topic 一目了然。</li>
 *   <li>{@code given(tccResource.tryReserve(...)).willReturn(true/false)} 決定 TCC Try 的結果，
 *       不必真的準備帳戶庫與餘額。</li>
 *   <li>{@link ArgumentCaptor} 抓出 {@code send(...)} 第 3 個參數（Handler 內部新組的 {@link SagaMessage}），
 *       再檢查它的 {@code type()}。</li>
 * </ul>
 * <p>【概念】帳戶側收到命令後「成功或失敗都回一則 event」給訂單側編排者，不丟例外給 Kafka；
 * <br>編排者只聽 event 決定下一步（Confirm 或補償）。本測試只看「收到什麼命令 → 發出什麼 event」，
 * <br>不啟動 Spring、不連 Kafka，所以 {@code @Value("${trading.kafka.event-topic}")} 不生效，topic 要手動傳入。
 */
// 啟用 Mockito：自動建立下方 @Mock 欄位，並在每個 Test 結束檢查是否有多餘的 stub（嚴格模式）
@ExtendWith(MockitoExtension.class)
// 測試報告／IDE 上顯示的名稱
@DisplayName("AccountCommandHandler unit")
class AccountCommandHandlerTest {

    // 帳戶 TCC 資源：正式環境是 AccountTccService（真的凍結／扣款／釋放帳戶庫餘額）；這裡是假的，回傳值由 given(...) 決定
    @Mock
    private TccResource tccResource;
    // Kafka 發送埠：正式環境是 KafkaTemplateMessageSender（送 broker 並記前台軌跡）；這裡是假的，只用來 verify 送了什麼
    @Mock
    private KafkaMessageSender kafkaMessageSender;

    /** SAGA-001：Given Try 成功（tryReserve 回 true），When 收到 RESERVE_FUNDS，Then 對 event topic 發出 FUNDS_RESERVED。 */
    @Test
    @DisplayName("SAGA-001: RESERVE_FUNDS success → FUNDS_RESERVED")
    void reserve_ok() {
        // ===== Given：TCC Try 會成功，並組好 Handler 與命令 =====
        // 只要 sagaId="s1"、accountId="ACC-001"、金額任意，tryReserve 就回 true（代表 available 足夠、已轉入 frozen）
        // Mockito 規則：同一次呼叫若有一個參數用比對器，全部參數都要用比對器，所以字串要包 eq(...)；金額不在乎就用 any()
        // 嚴格模式下若 Handler 傳入的 sagaId／accountId 與此不符，Mockito 會拋 PotentialStubbingProblem，等於順便驗證參數
        given(tccResource.tryReserve(eq("s1"), eq("ACC-001"), any())).willReturn(true);
        // 手動組裝被測物件：前兩個是 mock，第 3 個是 event topic
        // 正式環境由 @Value("${trading.kafka.event-topic}") 讀 application.yml（trading.saga.events）；單元測試沒 Spring，故手動傳同值
        AccountCommandHandler handler = new AccountCommandHandler(
                tccResource, kafkaMessageSender, "trading.saga.events");
        // 組一則 RESERVE_FUNDS 命令：sagaId=s1、orderId=o1、帳戶 ACC-001、金額 10000（同 SAGA-001 的 1 × 10000）、BTCUSDT、forceFail=false
        // SagaMessage.of 會自動產生 messageId（隨機 UUID）與 occurredAt（現在時間）
        SagaMessage cmd = SagaMessage.of(
                "s1", "o1", "ACC-001", SagaMessageTypes.RESERVE_FUNDS,
                new BigDecimal("10000"), "BTCUSDT", false);

        // ===== When：模擬 Kafka listener 把命令交給 Handler =====
        // 正式環境由 SagaKafkaListeners.onCommand 呼叫；這裡直接呼叫，走 RESERVE_FUNDS 分支 → tryReserve → publish
        handler.onMessage(cmd);

        // ===== Then：檢查有對 event topic 發出 FUNDS_RESERVED =====
        // 建立參數捕捉器，用來抓出 send() 第 3 個參數（Handler 內部新組的結果事件）
        ArgumentCaptor<SagaMessage> captor = ArgumentCaptor.forClass(SagaMessage.class);
        // 驗證 send 剛好被呼叫 1 次：topic 必須是 event topic、key 必須是 sagaId "s1"（同一 Saga 進同一分區），訊息交給 captor
        verify(kafkaMessageSender).send(eq("trading.saga.events"), eq("s1"), captor.capture());
        // tryReserve 回 true → Handler 必須發 FUNDS_RESERVED，訂單側收到後才會下 CONFIRM_FUNDS
        assertThat(captor.getValue().type()).isEqualTo(SagaMessageTypes.FUNDS_RESERVED);
    }

    /** SAGA-002：Given Try 失敗（tryReserve 回 false，例如餘額不足），When 收到 RESERVE_FUNDS，Then 發出 FUNDS_FAILED。 */
    @Test
    @DisplayName("SAGA-002: RESERVE_FUNDS fail → FUNDS_FAILED")
    void reserve_fail() {
        // ===== Given：TCC Try 會失敗，並組好 Handler 與命令 =====
        // sagaId="s2"、ACC-001 的 tryReserve 回 false：真實的 AccountTccService 在餘額不足時就是回 false（不寫預留票、不丟例外）
        given(tccResource.tryReserve(eq("s2"), eq("ACC-001"), any())).willReturn(false);
        // 與上一個 Test 相同方式手動組裝；event topic 同 application.yml 的 trading.kafka.event-topic
        AccountCommandHandler handler = new AccountCommandHandler(
                tccResource, kafkaMessageSender, "trading.saga.events");
        // 金額 999999 對齊 SAGA-002 fixture（1 × 999999 > 種子 available 100000）；
        // 但結果其實由上面的 stub 決定（any() 不看金額），這裡的數字只是讓情境貼近真實
        SagaMessage cmd = SagaMessage.of(
                "s2", "o2", "ACC-001", SagaMessageTypes.RESERVE_FUNDS,
                new BigDecimal("999999"), "BTCUSDT", false);

        // ===== When：模擬 Kafka listener 把命令交給 Handler =====
        // 同樣走 RESERVE_FUNDS 分支，但 tryReserve 回 false
        handler.onMessage(cmd);

        // ===== Then：檢查有對 event topic 發出 FUNDS_FAILED =====
        // 建立參數捕捉器，抓出送出的結果事件
        ArgumentCaptor<SagaMessage> captor = ArgumentCaptor.forClass(SagaMessage.class);
        // 驗證 send 被呼叫 1 次，topic＝event topic、key＝"s2"；失敗也要回報，不能安靜吞掉
        verify(kafkaMessageSender).send(eq("trading.saga.events"), eq("s2"), captor.capture());
        // tryReserve 回 false → 發 FUNDS_FAILED，訂單側收到後會呼叫 CompensationAction 把 Saga 收成 COMPENSATED
        assertThat(captor.getValue().type()).isEqualTo(SagaMessageTypes.FUNDS_FAILED);
    }

    /** SAGA-001：Given Confirm 成功（confirm 回 true），When 收到 CONFIRM_FUNDS（forceFail=false），Then 發出 FUNDS_CONFIRMED。 */
    @Test
    @DisplayName("SAGA-001: CONFIRM_FUNDS success → FUNDS_CONFIRMED")
    void confirm_ok() {
        // ===== Given：Confirm 會成功 =====
        // forceFail=false 時真實的 AccountTccService.confirm 會消耗凍結並回 true
        given(tccResource.confirm("s3", false)).willReturn(true);
        // 手動組裝 Handler，event topic 同 application.yml 的 trading.kafka.event-topic
        AccountCommandHandler handler = new AccountCommandHandler(
                tccResource, kafkaMessageSender, "trading.saga.events");
        // CONFIRM_FUNDS 命令由訂單側在收到 FUNDS_RESERVED 後組出；金額與 Try 相同
        SagaMessage cmd = SagaMessage.of(
                "s3", "o3", "ACC-001", SagaMessageTypes.CONFIRM_FUNDS,
                new BigDecimal("10000"), "BTCUSDT", false);

        // ===== When：走 CONFIRM_FUNDS 分支 =====
        handler.onMessage(cmd);

        // ===== Then：回覆 FUNDS_CONFIRMED =====
        // 共用 helper 抓出送往 event topic、key＝s3 的結果事件 type
        // 訂單側收到 FUNDS_CONFIRMED 後把訂單標 FILLED、Saga 標 COMPLETED
        assertThat(publishedType("s3")).isEqualTo(SagaMessageTypes.FUNDS_CONFIRMED);
    }

    /** TCC-002：Given forceFail=true（confirm 內部改走 Cancel 並回 false），When 收到 CONFIRM_FUNDS，Then 發出 FUNDS_CANCELLED。 */
    @Test
    @DisplayName("TCC-002: CONFIRM_FUNDS forceFail → FUNDS_CANCELLED")
    void confirm_forceFail_cancelled() {
        // ===== Given：forceFail 讓 Confirm 回 false =====
        // 真實的 AccountTccService.confirm(sagaId, true) 會先 cancel 把凍結還給 available，再回 false
        given(tccResource.confirm("s4", true)).willReturn(false);
        AccountCommandHandler handler = new AccountCommandHandler(
                tccResource, kafkaMessageSender, "trading.saga.events");
        // 命令的 forceFail=true：Handler 據此判斷「false 是因為教學取消」而不是真的失敗
        SagaMessage cmd = SagaMessage.of(
                "s4", "o4", "ACC-001", SagaMessageTypes.CONFIRM_FUNDS,
                new BigDecimal("10000"), "BTCUSDT", true);

        // ===== When：走 CONFIRM_FUNDS 分支 =====
        handler.onMessage(cmd);

        // ===== Then：回覆 FUNDS_CANCELLED（不是 FUNDS_FAILED）=====
        // 錢已還原，訂單側收到後走補償，Saga 收成 COMPENSATED
        assertThat(publishedType("s4")).isEqualTo(SagaMessageTypes.FUNDS_CANCELLED);
    }

    /** Given confirm 回 false 且 forceFail=false（TccResource 契約允許的失敗），When 收到 CONFIRM_FUNDS，Then 發出 FUNDS_FAILED。 */
    @Test
    @DisplayName("CONFIRM_FUNDS failure without forceFail → FUNDS_FAILED")
    void confirm_fail_failed() {
        // ===== Given：Confirm 失敗、且不是教學取消 =====
        // 本版 AccountTccService 不會走到這裡（查無預留票會丟例外），但 TccResource 介面允許其他實作回 false
        given(tccResource.confirm("s5", false)).willReturn(false);
        AccountCommandHandler handler = new AccountCommandHandler(
                tccResource, kafkaMessageSender, "trading.saga.events");
        SagaMessage cmd = SagaMessage.of(
                "s5", "o5", "ACC-001", SagaMessageTypes.CONFIRM_FUNDS,
                new BigDecimal("10000"), "BTCUSDT", false);

        // ===== When：走 CONFIRM_FUNDS 分支 =====
        handler.onMessage(cmd);

        // ===== Then：回覆 FUNDS_FAILED =====
        // ok=false 且 forceFail=false → 三元運算子選 FUNDS_FAILED，訂單側一樣走補償
        assertThat(publishedType("s5")).isEqualTo(SagaMessageTypes.FUNDS_FAILED);
    }

    /** Given 收到 CANCEL_FUNDS，When Handler 處理，Then 呼叫 cancel 並發出 FUNDS_CANCELLED。 */
    @Test
    @DisplayName("CANCEL_FUNDS → cancel + FUNDS_CANCELLED")
    void cancel_cancelled() {
        // ===== Given：cancel 是 void，mock 預設什麼都不做，不必 stub =====
        AccountCommandHandler handler = new AccountCommandHandler(
                tccResource, kafkaMessageSender, "trading.saga.events");
        SagaMessage cmd = SagaMessage.of(
                "s6", "o6", "ACC-001", SagaMessageTypes.CANCEL_FUNDS,
                new BigDecimal("10000"), "BTCUSDT", false);

        // ===== When：走 CANCEL_FUNDS 分支 =====
        handler.onMessage(cmd);

        // ===== Then①：確實呼叫了 TCC Cancel =====
        // 以 sagaId 找預留票釋放凍結；可安全重入（查無票或已取消皆不報錯）
        verify(tccResource).cancel("s6");
        // ===== Then②：回覆 FUNDS_CANCELLED =====
        // Cancel 沒有失敗分支，一律回報已取消
        assertThat(publishedType("s6")).isEqualTo(SagaMessageTypes.FUNDS_CANCELLED);
    }

    /**
     * 【職責】驗證 Handler 對 event topic 送出剛好一則、key＝sagaId 的事件，並回傳該事件的 type。
     * <p>【技巧】{@link ArgumentCaptor} 抓 {@code send(...)} 第 3 個參數；{@code verify} 預設即「剛好 1 次」。
     *
     * @param sagaId 預期的 Kafka key
     * @return 送出事件的 type
     */
    private String publishedType(String sagaId) {
        ArgumentCaptor<SagaMessage> captor = ArgumentCaptor.forClass(SagaMessage.class);
        verify(kafkaMessageSender).send(eq("trading.saga.events"), eq(sagaId), captor.capture());
        return captor.getValue().type();
    }
}
