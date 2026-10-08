package com.trading.saga.integration;

import com.trading.saga.support.SagaTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 【職責】HTTP＋雙 H2＋內嵌 Kafka 整合層；與單元層同一 Case ID。
 * <br>從 Controller 一路打到雙庫與 Kafka，驗證整條 Saga 的最終結果：
 * <ul>
 *   <li>ACCOUNT-001：{@code GET /api/v1/accounts/ACC-001} → 200，種子餘額。</li>
 *   <li>TRADE-001：{@code GET /api/v1/trades/missing-order} → 404。</li>
 *   <li>TRADE-002：壞掉的 JSON body → 400；{@code DELETE /api/v1/trades} → 405＋Allow。</li>
 *   <li>SAGA-001：下單 → Saga {@code COMPLETED}、available 90000。</li>
 *   <li>SAGA-002：餘額不足 → Saga {@code COMPENSATED}、餘額不變。</li>
 *   <li>TCC-002：forceFail → Try 後 Cancel → Saga {@code COMPENSATED}、餘額還原。</li>
 *   <li>OUTBOX-001：下單後 Kafka 軌跡出現 {@code RESERVE_FUNDS}。</li>
 *   <li>TCC-001：{@code GET /api/v1/tcc/reservations/{sagaId}} → 三情境終態預留票 CONFIRMED／無票／CANCELLED；未知 sagaId → 200 exists=false。</li>
 *   <li>DASH-001：同埠靜態前台 index.html／dashboard.js／dashboard.spec.js → 200 且含 Dashboard 標記。</li>
 * </ul>
 * <p>【技巧】Request body 來自 {@code docs/test-data/}（EOS Fixture）；Awaitility 等終態。
 * <ul>
 *   <li>{@link SagaTestFixtures}：單元與整合層共用同一份 JSON，契約改動只改一處。</li>
 *   <li>{@link MockMvc}：在同一個 JVM 內直接呼叫 DispatcherServlet，不經真實網路，但 Controller、
 *       {@code @Valid}、{@code GlobalExceptionHandler}、JSON 轉換都是真的。</li>
 *   <li>{@code jsonPath(...)}：以 JsonPath 語法讀回應 JSON；{@code $[*].type} 取陣列每個元素的 type。</li>
 *   <li>Awaitility {@code await().atMost(...).untilAsserted(...)}：反覆執行 lambda 內的斷言，
 *       直到通過或逾時；用來等非同步的 Kafka 流程，取代不可靠的 {@code Thread.sleep}。</li>
 * </ul>
 * <p>【概念】{@code @SpringBootTest} 會真的啟動整個 Spring 應用：讀 {@code application.yml}、建立雙 H2
 * <br>（orderdb／accountdb）、Kafka listener、Outbox 排程。{@code POST /trades} 只回 202（已受理），
 * <br>之後的流程在背景執行緒進行：Outbox 排程把 {@code RESERVE_FUNDS} 送進 Kafka → 帳戶側 TCC Try →
 * <br>發 event → 訂單側推進（{@code CONFIRM_FUNDS} 或補償）……所以 HTTP 回來當下 Saga 多半仍是
 * <br>{@code ACCOUNT_TRYING}，必須用 Awaitility 輪詢 {@code GET /sagas/{id}} 等到終態才能斷言。
 * <br>同一個測試類別的方法共用快取的 Spring Context（DB 與記憶體軌跡不會自動清空），因此：
 * <ul>
 *   <li>每個 Test 前都以 reset API 把帳戶還原成種子餘額。</li>
 *   <li>Kafka 軌跡斷言一律以 JsonPath 篩選本次的 sagaId（{@link #typesOf}），不會被前一個 Test 留下的訊息滿足。</li>
 *   <li>每個下單的 Test 都等 Saga 走到終態才結束，避免背景流程（如晚到的 CONFIRM_FUNDS）在下一個 Test reset 後才扣款。</li>
 * </ul>
 */
// 標記為整合測試：build.gradle 的 test 任務排除此 tag、integrationTest 任務只跑此 tag；gradlew check 兩者都跑
@Tag("integration")
// 啟動完整 Spring Boot 應用，內嵌 Web 伺服器開在隨機埠（避免與本機 bootRun 的 8093 衝突）
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 自動建立 MockMvc Bean，供下方 @Autowired 注入
@AutoConfigureMockMvc
// 由 spring-kafka-test 啟動一個測試用 Kafka broker，並預建命令／事件兩個 topic（各 1 個 partition）
@EmbeddedKafka(partitions = 1, topics = { "trading.saga.commands", "trading.saga.events" })
// 覆寫 application.yml 的設定（優先權高於設定檔），只對本測試生效
@TestPropertySource(properties = {
        // 關閉啟動資訊列印（StartupInfoLogger），讓測試 log 乾淨
        "startup.info.enabled=false",
        // Outbox 排程間隔由預設 200ms 縮成 50ms，讓命令更快送進 Kafka、縮短等待
        "trading.outbox.poll-ms=50",
        // 關閉應用自帶的內嵌 broker（EmbeddedKafkaConfig 以此屬性為條件），避免同時起兩個 broker
        "trading.kafka.embedded=false",
        // 讓 Producer／Consumer 連到 @EmbeddedKafka 的 broker；其位址由 @EmbeddedKafka 寫進 spring.embedded.kafka.brokers
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
// 測試報告／IDE 上顯示的名稱
@DisplayName("Trade Saga integration (paired cases)")
class TradeSagaIntegrationTest {

    // 模擬 HTTP 呼叫的入口：perform(請求) → andExpect(期望)；由 @AutoConfigureMockMvc 提供
    @Autowired
    private MockMvc mockMvc;

    /**
     * 【職責】每個 Test 前把種子帳戶 ACC-001 還原成 available=100000、frozen=0。
     * <p>【技巧】直接打正式的 reset API（{@code POST /api/v1/accounts/ACC-001/reset}），並順便斷言回應，
     * <br>確保還原真的成功後才進入各 Test。
     * <p>【概念】Spring Context 在方法之間共用，前一個 Test 扣過的款會留在 accountdb；
     * <br>不還原的話，SAGA-001 的「available 90000」等期望值會被前一個 Test 影響。
     */
    @BeforeEach
    void resetSeed() throws Exception {
        // 送出還原種子餘額的 POST（AccountQueryService.reset → Account.resetTo(100000, 0)）
        mockMvc.perform(post("/api/v1/accounts/ACC-001/reset"))
                // 期望 HTTP 200
                .andExpect(status().isOk())
                // 回應 JSON 的 available 必須是種子值 100000（AccountQueryService.SEED_AVAILABLE）
                .andExpect(jsonPath("$.available").value(100000))
                // frozen 必須歸零，代表沒有殘留的凍結資金
                .andExpect(jsonPath("$.frozen").value(0));
    }

    /** ACCOUNT-001：Given 種子帳戶，When GET /api/v1/accounts/ACC-001，Then 200 且 accountId／available 與 seed JSON 一致。 */
    @Test
    @DisplayName("ACCOUNT-001: GET /api/v1/accounts/ACC-001 → 200 seed balance")
    void getAccount_seed_200() throws Exception {
        // ===== Given：讀種子期望值 =====
        // 讀 docs/test-data/account/ACCOUNT-001-SEED.json 成 JsonNode（內容：accountId=ACC-001、available=100000、frozen=0）
        var seed = SagaTestFixtures.loadTree("account", "ACCOUNT-001-SEED");
        // ===== When：查詢帳戶 =====
        // 打 GET 查餘額（AccountController.get → AccountQueryService.get）
        mockMvc.perform(get("/api/v1/accounts/ACC-001"))
                // ===== Then：狀態碼與內容 =====
                // 帳戶存在 → HTTP 200
                .andExpect(status().isOk())
                // 回應的 accountId 要等於 seed JSON 的 accountId 字串（"ACC-001"）
                .andExpect(jsonPath("$.accountId").value(seed.get("accountId").asText()))
                // 回應的 available 要等於 seed JSON 的 available（asInt() → 100000）
                .andExpect(jsonPath("$.available").value(seed.get("available").asInt()));
    }

    /** TRADE-001：Given 不存在的訂單 id，When GET /api/v1/trades/missing-order，Then 404 且錯誤訊息含該 id。 */
    @Test
    @DisplayName("TRADE-001: GET unknown order → 404")
    void getUnknownOrder_404() throws Exception {
        // ===== When：查一筆不存在的訂單 =====
        // TradeQueryService.getOrder 查不到 → 拋 ResourceNotFoundException("Order not found: missing-order")
        mockMvc.perform(get("/api/v1/trades/missing-order"))
                // ===== Then：由 GlobalExceptionHandler 轉成穩定的 404 JSON =====
                // 期望 HTTP 404（而不是 500）
                .andExpect(status().isNotFound())
                // 錯誤 JSON 的 error 欄位固定為 "Not Found"
                .andExpect(jsonPath("$.error").value("Not Found"))
                // message 欄位為例外訊息，containsString 只要求包含查詢的 id，不綁死整句文字
                .andExpect(jsonPath("$.message", containsString("missing-order")));
    }

    /** TRADE-002：Given 語法錯誤的 JSON body，When POST /api/v1/trades，Then 400 而非 500，且不會建立 Saga。 */
    @Test
    @DisplayName("TRADE-002: POST malformed JSON → 400")
    void malformedBody_400() throws Exception {
        // ===== When：送出缺右大括號的 JSON =====
        mockMvc.perform(post("/api/v1/trades")
                        // 宣告為 JSON，Spring 才會交給 Jackson 解析並在解析失敗時丟 HttpMessageNotReadableException
                        .contentType(MediaType.APPLICATION_JSON)
                        // 故意少了結尾 }，Jackson 無法產生 TradeRequest
                        .content("{\"accountId\":\"ACC-001\""))
                // ===== Then：GlobalExceptionHandler.handleNotReadable 轉成 400 =====
                // 期望 HTTP 400（呼叫端錯誤），而不是落到兜底的 500
                .andExpect(status().isBadRequest())
                // error 欄位為標準短語
                .andExpect(jsonPath("$.error").value("Bad Request"))
                // message 固定文字，不外洩 Jackson 解析細節
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"));
    }

    /** TRADE-002：Given /api/v1/trades 只支援 GET／POST，When 送 DELETE，Then 405 且 Allow header 列出 GET 與 POST。 */
    @Test
    @DisplayName("TRADE-002: DELETE /api/v1/trades → 405 + Allow")
    void unsupportedMethod_405() throws Exception {
        // ===== When：對下單路徑送不支援的 DELETE =====
        // Spring MVC 找到路徑但沒有對應方法 → HttpRequestMethodNotSupportedException
        mockMvc.perform(delete("/api/v1/trades"))
                // ===== Then：GlobalExceptionHandler.handleMethodNotSupported 轉成 405 =====
                // 期望 HTTP 405 Method Not Allowed
                .andExpect(status().isMethodNotAllowed())
                // Allow header 要同時含 GET 與 POST（順序由 Spring 決定，故只檢查包含）
                .andExpect(header().string("Allow", allOf(containsString("GET"), containsString("POST"))))
                // message 帶出被拒的方法名
                .andExpect(jsonPath("$.message").value("Request method 'DELETE' is not supported"));
    }

    /** SAGA-001：Given 種子餘額 100000，When 下單 1 × 10000，Then Saga COMPLETED、available 90000／frozen 0、軌跡含 RESERVE_FUNDS 與 FUNDS_CONFIRMED。 */
    @Test
    @DisplayName("SAGA-001: POST fixture → FILLED / COMPLETED, available 90000")
    void happyPath_filled() throws Exception {
        // ===== Given／When：以 SAGA-001 fixture 下單 =====
        // 送出 docs/test-data/trade/SAGA-001-SUCCESS.json（ACC-001 BUY BTCUSDT 1 × 10000，forceFail=false），取回 sagaId
        String sagaId = placeFixture("SAGA-001-SUCCESS");
        // ===== Then①：等 Saga 走到 COMPLETED =====
        // 最多等 12 秒，期間反覆執行 lambda；斷言失敗（AssertionError）就稍後重試，通過即結束、逾時則測試失敗
        // 正向流程：RESERVE_FUNDS → Try 成功 → FUNDS_RESERVED → CONFIRM_FUNDS → Confirm 成功 → FUNDS_CONFIRMED → COMPLETED
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() ->
                // 查 Saga 狀態（TradeController.getSaga）
                mockMvc.perform(get("/api/v1/sagas/" + sagaId))
                        // Saga 在下單交易內就已建立，所以一開始就是 200
                        .andExpect(status().isOk())
                        // 真正需要等待的條件：status 變成 COMPLETED
                        .andExpect(jsonPath("$.status").value("COMPLETED")));
        // ===== Then②：檢查帳戶餘額 =====
        // 不必再等待：帳戶 Confirm 在 accountdb 交易提交後才發 FUNDS_CONFIRMED，Saga 能變 COMPLETED 代表扣款已落庫
        mockMvc.perform(get("/api/v1/accounts/ACC-001"))
                // 100000（種子）− 10000（1 × 10000）＝ 90000：Try 把 10000 從 available 移到 frozen，Confirm 再把 frozen 消耗掉
                .andExpect(jsonPath("$.available").value(90000))
                // Confirm 後凍結歸零（真正扣款，不退回 available）
                .andExpect(jsonPath("$.frozen").value(0));
        // ===== Then③：檢查本次 Saga 的 Kafka 軌跡 =====
        // 查記憶體軌跡（EventLogService；KafkaTemplateMessageSender 每送出一則訊息就記一筆，新到舊）
        mockMvc.perform(get("/api/v1/events"))
                // 本次 sagaId 的軌跡要有 RESERVE_FUNDS（Outbox 排程送出的 Try 命令）
                .andExpect(jsonPath(typesOf(sagaId), hasItem("RESERVE_FUNDS")))
                // 也要有 FUNDS_CONFIRMED（帳戶側 Confirm 成功後發出的事件）
                .andExpect(jsonPath(typesOf(sagaId), hasItem("FUNDS_CONFIRMED")));
        // ===== Then④（TCC-001）：帳戶庫的預留票已兌現 =====
        // FUNDS_CONFIRMED 在預留票 CONFIRMED 提交後才發，Saga COMPLETED 時票必為 CONFIRMED
        mockMvc.perform(get("/api/v1/tcc/reservations/" + sagaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.state").value("CONFIRMED"));
    }

    /** SAGA-002：Given 種子餘額 100000，When 下單 1 × 999999（餘額不足），Then Saga COMPENSATED、帳戶餘額不變。 */
    @Test
    @DisplayName("SAGA-002: POST fixture → COMPENSATED, account unchanged")
    void insufficient_compensated() throws Exception {
        // ===== Given／When：以 SAGA-002 fixture 下單 =====
        // 送出 SAGA-002-INSUFFICIENT.json（1 × 999999 > available 100000），HTTP 仍回 202，失敗發生在後續非同步流程
        String sagaId = placeFixture("SAGA-002-INSUFFICIENT");
        // ===== Then①：等 Saga 走到 COMPENSATED =====
        // 負向流程：RESERVE_FUNDS → Try 餘額不足回 false → FUNDS_FAILED → OrderMarkFailedAction → COMPENSATED（訂單 FAILED）
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() ->
                // 輪詢 Saga 狀態
                mockMvc.perform(get("/api/v1/sagas/" + sagaId))
                        // Saga 已存在 → 200
                        .andExpect(status().isOk())
                        // 等到 status 為補償終態 COMPENSATED
                        .andExpect(jsonPath("$.status").value("COMPENSATED")));
        // ===== Then②：帳戶完全沒被動到 =====
        // 查帳戶餘額
        mockMvc.perform(get("/api/v1/accounts/ACC-001"))
                // Try 失敗時不改餘額、不寫預留票，available 維持種子 100000
                .andExpect(jsonPath("$.available").value(100000))
                // 也沒有任何凍結
                .andExpect(jsonPath("$.frozen").value(0));
        // ===== Then③（TCC-001）：Try 失敗根本不寫票 =====
        mockMvc.perform(get("/api/v1/tcc/reservations/" + sagaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false));
    }

    /** TCC-002：Given 種子餘額 100000，When 下單 1 × 10000 且 forceFail=true，Then Try 成功後在 Confirm 改走 Cancel，Saga COMPENSATED、餘額還原。 */
    @Test
    @DisplayName("TCC-002: forceFail fixture → COMPENSATED, account restored")
    void forceFail_compensated() throws Exception {
        // ===== Given／When：以 TCC-002 fixture 下單 =====
        // 送出 TCC-002-FORCE-FAIL.json（1 × 10000，forceFail=true），取回 sagaId
        String sagaId = placeFixture("TCC-002-FORCE-FAIL");
        // ===== Then①：等 Saga 走到 COMPENSATED =====
        // 流程：Try 成功（available 90000／frozen 10000）→ FUNDS_RESERVED → CONFIRM_FUNDS(forceFail=true)
        //      → AccountTccService.confirm 改呼叫 cancel 還原 → FUNDS_CANCELLED → 補償 → COMPENSATED
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() ->
                // 輪詢 Saga 狀態
                mockMvc.perform(get("/api/v1/sagas/" + sagaId))
                        // Saga 已存在 → 200
                        .andExpect(status().isOk())
                        // 等到 status 為 COMPENSATED（此時是從 ACCOUNT_CONFIRMING 轉入補償）
                        .andExpect(jsonPath("$.status").value("COMPENSATED")));
        // ===== Then②：帳戶已還原 =====
        // Cancel 在 accountdb 交易提交後才發 FUNDS_CANCELLED，所以 Saga 變 COMPENSATED 時餘額必已還原，不必再等
        mockMvc.perform(get("/api/v1/accounts/ACC-001"))
                // Cancel 把凍結的 10000 加回 available：90000 + 10000 ＝ 100000
                .andExpect(jsonPath("$.available").value(100000))
                // 凍結歸零
                .andExpect(jsonPath("$.frozen").value(0));
        // ===== Then③（TCC-001）：預留票已退回 =====
        mockMvc.perform(get("/api/v1/tcc/reservations/" + sagaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.state").value("CANCELLED"));
    }

    /**
     * DASH-001：Given 同埠靜態前台，When GET index.html／dashboard.js／test/dashboard.spec.js，
     * <br>Then 200 且含 Dashboard 標記（三條 lane、導航列、時間軸）與狀態推導函式；單元層為 dashboard.spec.js。
     */
    @Test
    @DisplayName("DASH-001: static dashboard assets served with lane markers")
    void dashboardAssets_served() throws Exception {
        // index.html：Dashboard 容器、導航列、三個情境按鈕、時間軸都要在（UI Smoke 依這些 data-testid 操作）
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("data-testid=\"sm-dashboard\""),
                        containsString("data-testid=\"main-nav\""),
                        containsString("data-testid=\"btn-saga-001\""),
                        containsString("data-testid=\"timeline-row\""),
                        containsString("<state-lane"))));
        // dashboard.js：三條 lane 的推導與時間軸重建都由它輸出
        mockMvc.perform(get("/dashboard.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("export function buildLanes"),
                        containsString("export function buildTimeline"),
                        // 靜態資源回應未必帶 charset，MockMvc 會以 ISO-8859-1 解碼，故只比對 ASCII 片段
                        containsString("tcc_reservations"))));
        // 規格檔要能被瀏覽器 runner 載入（UI Smoke 的 DASH-001 在瀏覽器內再跑一次）
        mockMvc.perform(get("/test/dashboard.spec.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("export function runDashboardSpecs")));
    }

    /** TCC-001：Given 從未下單的 sagaId，When GET /api/v1/tcc/reservations/{sagaId}，Then 200 且 exists=false（無票是合法狀態，不是 404）。 */
    @Test
    @DisplayName("TCC-001: GET unknown reservation → 200 exists=false")
    void reservation_unknown_noTicket() throws Exception {
        mockMvc.perform(get("/api/v1/tcc/reservations/no-such-saga"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sagaId").value("no-such-saga"))
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.state").doesNotExist());
    }

    /** OUTBOX-001：Given 下單成功寫入 Outbox，When 排程轉送，Then 本次 sagaId 的 Kafka 軌跡出現 RESERVE_FUNDS，且 Saga 最終 COMPLETED。 */
    @Test
    @DisplayName("OUTBOX-001: fixture place eventually publishes RESERVE_FUNDS")
    void outbox_reachesKafkaTrail() throws Exception {
        // ===== Given／When：以 OUTBOX-001 fixture 下單 =====
        // body 與 SAGA-001 相同；下單交易內只把 RESERVE_FUNDS 寫進 outbox_events，尚未送 Kafka
        String sagaId = placeFixture("OUTBOX-001-RESERVE");
        // ===== Then①：等 Outbox 排程把本次的命令真的送進 Kafka =====
        // OutboxRelayJob 每 50ms（見上方 TestPropertySource）呼叫 publishPending；送成功才會記進軌跡
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() ->
                // 查記憶體 Kafka 軌跡
                mockMvc.perform(get("/api/v1/events"))
                        // 端點正常 → 200
                        .andExpect(status().isOk())
                        // 只看本次 sagaId 的軌跡：出現 RESERVE_FUNDS 證明「先落庫、後發送」的發件匣有運作
                        .andExpect(jsonPath(typesOf(sagaId), hasItem("RESERVE_FUNDS"))));
        // ===== Then②：等 Saga 走到終態再結束 =====
        // 流程仍在背景跑；若不等，晚到的 CONFIRM_FUNDS 可能在下一個 Test reset 帳戶後才扣款，污染其期望值
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/sagas/" + sagaId))
                        // body 與 SAGA-001 相同，正常流程終點是 COMPLETED
                        .andExpect(jsonPath("$.status").value("COMPLETED")));
    }

    /**
     * 【職責】組出「只取指定 sagaId 的軌跡 type」的 JsonPath。
     * <p>【技巧】JsonPath 篩選語法 {@code $[?(@.sagaId == 'x')].type}：{@code ?()} 逐筆過濾陣列元素，{@code @} 代表當前元素；
     * <br>結果仍是陣列，可直接搭配 {@code hasItem(...)}。
     * <p>【概念】EventLogService 是全應用共用的記憶體軌跡，跨 Test 不會清空；以 sagaId 篩選才能確定訊息屬於本次下單。
     *
     * @param sagaId 本次下單取得的 sagaId（UUID，不含單引號，可安全嵌入運算式）
     * @return JsonPath 運算式
     */
    private static String typesOf(String sagaId) {
        return "$[?(@.sagaId == '" + sagaId + "')].type";
    }

    /**
     * 【職責】以指定 Case ID 的 fixture 呼叫 {@code POST /api/v1/trades}，斷言 202 並回傳 sagaId。
     * <p>【技巧】{@code andReturn()} 取得 {@link MvcResult}，再用 Jackson 讀回應 JSON 的 {@code sagaId}，
     * <br>供後續 {@code GET /api/v1/sagas/{sagaId}} 輪詢。
     * <p>【概念】202 Accepted＝「已受理、開始編排」，不等於扣款完成；回應裡的訂單多為 PENDING。
     *
     * @param caseId {@code docs/test-data/trade/} 下不含副檔名的檔名（如 SAGA-001-SUCCESS）
     * @return 本次下單產生的 sagaId（隨機 UUID）
     */
    private String placeFixture(String caseId) throws Exception {
        // 送出下單請求，並在全部期望通過後取回完整結果
        MvcResult result = mockMvc.perform(post("/api/v1/trades")
                        // 宣告 body 為 JSON，Spring 才會用 Jackson 轉成 TradeRequest（並觸發 @Valid 驗證）
                        .contentType(MediaType.APPLICATION_JSON)
                        // body 直接用 fixture 原文；多出的 description 欄位會被 Spring Boot 預設的 Jackson 設定忽略
                        .content(SagaTestFixtures.loadJson("trade", caseId)))
                // TradeController.place 固定回 202 Accepted
                .andExpect(status().isAccepted())
                // 回應必須帶 sagaId，否則後面無法輪詢
                .andExpect(jsonPath("$.sagaId").exists())
                // 取回 MvcResult 以讀取回應內容
                .andReturn();
        // 把回應 body 字串解析成 JsonNode，取出 sagaId 欄位文字回傳
        return SagaTestFixtures.mapper().readTree(result.getResponse().getContentAsString())
                .get("sagaId").asText();
    }
}
