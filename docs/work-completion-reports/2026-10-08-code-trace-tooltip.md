# 完工報告 — TradingSagaTCC（Dashboard hover 呼叫鏈 DASH-004）

> 權威：`eos-minimal/knowledge/os-architecture.md` §6

- 日期：2026-10-08
- 專案：TradingSagaTCC
- 任務：前端畫面滑鼠停留時同步告知「入口 → Controller／Listener → Service → function」；狀態區塊＋按鈕＋時間軸列，含反射防漂移測試與 UI Smoke
- 執行者：AI

## 變更摘要

| 項目 | 變更 |
|------|------|
| 對照表 | 新增 `static/code-trace.json`（唯一來源）：30 個 key（saga／order／tcc 狀態、action 按鈕、step 步驟、msg 訊息），入口種類 HTTP／KAFKA／SCHEDULED／NONE；Saga FAILED、CANCEL_FUNDS 無程式路徑標 NONE |
| 前端模型 | `dashboard.js`：區塊與時間軸列帶 `trace` key；新增 `buildTrace`／`formatRef`／`layerOf`／`expectedTraceKeys`／`ACTION_TRACE_KEYS` |
| 前端畫面 | `index.html`／`app.js`：`[data-trace]` 事件委派 hover tooltip（入口徽章＋分層色 `Class.method` 鏈＋中文說明）、「顯示呼叫鏈」開關、150ms 延遲收起可移入 tooltip、**頁面捲動即收起**（fixed 定位避免指錯列） |
| 單元 | `CodeTraceMapTest`（反射：每個 `類別#方法` 存在、入口方法帶 `@RequestMapping`／`@KafkaListener`／`@Scheduled`）；`dashboard.spec.js` `traceSpecs` 5 條（Node＋瀏覽器同一份） |
| 整合 | `TradeSagaIntegrationTest.codeTraceEntries_matchRuntime`：JSON 200；HTTP 對上 `RequestMappingHandlerMapping`；Kafka topic 經 `Environment` 解析相等；排程 property 存在 |
| UI Smoke | `suite.js` +2 劇情（瀏覽器內 traceSpecs；hover 區塊／按鈕／時間軸列＋開關）；hover 前等 scroll 穩定 |
| Smoke 腳本 | `run-headless.mjs` 遇 `failed` 立即結束並印出失敗劇情 log（原本等滿 120s 逾時、看不到哪條紅）；`run-dashboard-unit.mjs` 帶入 traces |
| 靜態檔快取 | 使用者原瀏覽器沿用舊 `app.js`（無 `Cache-Control` → 啟發式快取），hover 無反應、換瀏覽器即正常 → `application.yml` 加 `spring.web.resources.cache.cachecontrol.no-cache: true`；DASH-001 整合斷言 `Cache-Control` 含 `no-cache` |
| 文件 | 主規格書 §7、`README.md`、`docs/testing.md`、`docs/architecture.md`、`docs/codeGraphic.html`（重嵌）、`CLAUDE.md`／`AGENTS.md` |

## 已跑

| ID | 結果 | 證據 |
|----|------|------|
| EOS-LOOP-WORK | 已跑 | 上表各檔 |
| EOS-HARNESS-CHECK | 已跑 | `scripts\check.ps1` → BUILD SUCCESSFUL；unit 91（含 TestFactory 動態案例）／integration 11，0 failure／0 skipped |
| 變異測試 | 已跑 | JSON 改 `onConfirmedX` → 反射單元 3 紅；JS 刪 key＋錯分層 → traceSpecs 紅；還原後綠 |
| EOS-LOOP-RELEASE | 已跑（L1 API＋UI） | `docs\run-release-gate.ps1` → `ALL_API_SMOKE_OK`＋`ALL_DASH_UNIT_OK`＋`ALL_UI_SMOKE_OK` → `ALL_RELEASE_GATE_OK` |
| 文件同步 | 已跑 | `docs\sync-codegraphic-docs.ps1 -Check` → up to date |

### Runtime Smoke（`EOS-LOOP-RELEASE`）

```text
級別: L1（API＋UI）
啟動: 使用者既有 bootRun（:8093，已確認提供新版靜態檔）
探活: health=UP  UI=200
劇情: SAGA-001=COMPLETED/90000.0000/CONFIRMED ; SAGA-002=COMPENSATED/100000.0000/NO_TICKET ; TCC-002=COMPENSATED/100000.0000/CANCELLED ; TCC-001=unknown=NO_TICKET ; TRADE-001=404 ; DASH-001=assets=200
DASH-001/004 unit: 16 passed, 0 failed（Node）
UI automation: PASS（SAGA-001／002、TCC-002、TCC-001、TRADE-001、DASH-001、DASH-002×3、DASH-003、DASH-004×2）
BROWSER 404 log：TRADE-001 刻意查無訂單＋iframe favicon，預期
EOS-GRAPH: N/A — 單 Agent
```

### 畫面驗證

headless 截圖確認：Saga「交易完成」區塊（Kafka `trading.saga.events` → `SagaKafkaListeners.onEvent` → `OrderSagaEventHandler.onMessage` → `onConfirmed` → `SagaInstance.transitionTo`）、按鈕「成功路徑」（HTTP `POST /api/v1/trades` → `TradeController.place` → `SagaOrchestrator.start` → `OutboxPublisherService.append`）、時間軸列 RESERVE_FUNDS（排程 `OutboxRelayJob.tick` → `publishPending` → `KafkaTemplateMessageSender.send`）。截圖時發現並修正：`scroll-behavior: smooth` 捲動中游標下換列、tooltip 仍停在舊位置 → 捲動即收起。

## N/A

| ID | 理由 |
|----|------|
| EOS-LOOP-PR | 直接 commit main，未開 PR |
| EOS-HARNESS-EOS | 未改公版 |
| EOS-LOOP-SYNC | 無公版缺陷需回寫 |

## 對話面板

本報告內容已於同輪對話顯示：是
