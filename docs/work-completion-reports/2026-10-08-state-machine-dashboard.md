# 完工報告 — TradingSagaTCC（三方狀態機 Dashboard＋TCC 預留票查詢）

> 權威：`eos-minimal/knowledge/os-architecture.md` §6

- 日期：2026-10-08
- 專案：TradingSagaTCC
- 任務：網頁端以圖像區塊（階段一 → 階段二 → 終態分岔）同步中文呈現 Saga／訂單／帳戶 TCC 三組狀態機，具導航效果；單元、整合、Smoke（API＋UI）完整驗證
- 執行者：AI

## 變更摘要

| 項目 | 變更 |
|------|------|
| 後端 | 新增 `GET /api/v1/tcc/reservations/{sagaId}`（`TccReservationController`、`TccReservationResponse`、`AccountQueryService.getReservation`）；唯讀帳戶庫，無票回 200 `exists=false` |
| 前端模型 | 新增 `static/dashboard.js` 純函式：`liveSnapshot`／`buildLanes`／`buildVerdict`／`buildTimeline`／`diffLanes` |
| 前端畫面 | `index.html`／`app.js`：固定導航列（捲動高亮）、三條箭頭區塊狀態機（中文＋代碼、成功／補償分岔、分岔點／略過）、總結列、圖例；時間軸由 Saga 步驟＋Kafka 軌跡重建，可定格；終態自動慢動作重播（0.5／1／2 秒／格）；點訂單列回看舊交易；`?autoReplay=0` 供 Smoke |
| 新 Case | **TCC-001**（預留票查詢，單元＋整合成對）、**DASH-001**（Dashboard 模型，JS 單元＋整合成對）、**DASH-002／003**（UI 層專屬 Runtime Smoke） |
| 測試 | `AccountQueryServiceTest` +2；`TradeSagaIntegrationTest` +2（TCC-001、DASH-001）並於三情境補預留票終態斷言；`static/test/dashboard.spec.js` 11 條（Node `docs/ui-smoke/run-dashboard-unit.mjs`＋瀏覽器 runner）；`suite.js`／`runner.html` iframe 實際點主畫面驗區塊 |
| Smoke 腳本 | `run-api-smoke.ps1` 加預留票終態＋Dashboard 資源；`run-ui-smoke.ps1` 開瀏覽器前先跑 JS 單元；`run-headless.mjs` 劇情改讀畫面 Case 清單 |
| 文件 | `API規格書.md`、主規格書 §1／§6／§7、`README.md`、`docs/testing.md`、`docs/architecture.md`、`docs/狀態對照-Saga-TCC-訂單.md`、`docs/codeGraphic.html`（Case 卡片／總表＋重嵌文件）、`CLAUDE.md`／`AGENTS.md` |

## 已跑

| ID | 結果 | 證據 |
|----|------|------|
| EOS-LOOP-WORK | 已跑 | 上表各檔 |
| EOS-HARNESS-CHECK | 已跑 | `scripts\check.ps1` → BUILD SUCCESSFUL；unit 34／integration 10，0 failure／0 skipped |
| EOS-LOOP-RELEASE | 已跑（L1 API＋UI） | `bootRun` + `docs\run-release-gate.ps1` → `ALL_API_SMOKE_OK`＋`ALL_DASH_UNIT_OK`＋`ALL_UI_SMOKE_OK` → `ALL_RELEASE_GATE_OK` |
| 文件同步 | 已跑 | `docs\sync-codegraphic-docs.ps1 -Check` → up to date |

### Runtime Smoke（`EOS-LOOP-RELEASE`）

```text
級別: L1（API＋UI）
啟動: .\gradlew.bat bootRun
埠: 8093
探活: health=UP  UI=200
劇情: SAGA-001=COMPLETED/90000.0000/CONFIRMED ; SAGA-002=COMPENSATED/100000.0000/NO_TICKET ; TCC-002=COMPENSATED/100000.0000/CANCELLED ; TCC-001=unknown=NO_TICKET ; TRADE-001=404 ; DASH-001=assets=200
DASH-001 unit: 11 passed, 0 failed（Node）；瀏覽器 runner 同一份規格 PASS
UI automation: PASS（SAGA-001／002、TCC-002、TCC-001、TRADE-001、DASH-001、DASH-002×3、DASH-003）
BROWSER 404 log：TRADE-001 刻意查無訂單＋iframe favicon，預期
時間: 2026-10-08（check 10:44 綠燈之後）
EOS-GRAPH: N/A — 單 Agent
```

### 畫面驗證

headless 截圖逐一確認 SAGA-001（三條成功終態）、SAGA-002（階段二分岔、TCC Try 失敗無票）、TCC-002（階段三分岔、預留票已退回）、時間軸定格第 4 格（預留票已凍結、Saga 仍凍結資金中＝最終一致）。截圖時發現並修正：終態分岔格說明被裁切（`flex: 1 0 auto`）、時間軸定格列深底深字（表格改深色主題變數）。

## N/A

| ID | 理由 |
|----|------|
| EOS-LOOP-PR | 直接 commit main，未開 PR |
| EOS-HARNESS-EOS | 未改公版 |
| EOS-LOOP-SYNC | 無公版缺陷需回寫（本機殘留背景 `bootRun` 佔用 8093 已當場釋放） |

## 對話面板

本報告內容已於同輪對話顯示：是
