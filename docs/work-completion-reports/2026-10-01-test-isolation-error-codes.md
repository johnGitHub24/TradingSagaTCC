# 完工報告 — TradingSagaTCC（整合測試隔離／TRADE-002 錯誤碼／金額 scale／測試補洞）

> 權威：`eos-minimal/knowledge/os-architecture.md` §6

- 日期：2026-10-01
- 專案：TradingSagaTCC
- 任務：修正補註解時發現的 5 項問題（測試隔離、狀態機測試歸類、測試缺口、框架例外 500、文件與實作不一致）
- 執行者：AI

## 變更摘要

| 項目 | 變更 |
|------|------|
| 整合測試隔離 | Kafka 軌跡斷言改以 JsonPath 篩本次 sagaId；OUTBOX-001 等到 COMPLETED 才結束 |
| 狀態機測試 | `STARTED → COMPENSATING` 移至補償測試；補 `COMPENSATING → FAILED`、`FAILED` 終態 |
| 測試缺口 | `AccountCommandHandlerTest` 補 CONFIRM（成功／forceFail／失敗）、CANCEL 四個分支 |
| 框架例外 | `GlobalExceptionHandler`：壞 JSON → 400、方法不支援 → 405＋Allow；新 Case **TRADE-002**（單元＋整合成對） |
| 金額 scale | `TradeOrder.amount` HALF_UP 捨入到 4 位，202 回應／Kafka 命令／DB 三處一致；新增 `TradeOrderTest` |
| DB 索引 | `SagaStep` 宣告 `idx_saga_steps_saga_id`，對齊 `docs/資料庫設計.md` |
| 文件 | `API規格書.md`（400／405、amount 規則、狀態碼表）、`docs/testing.md`、主規格書 Case 表、`CLAUDE.md`／`AGENTS.md` |

## 已跑

| ID | 結果 | 證據 |
|----|------|------|
| EOS-LOOP-WORK | 已跑 | 上表各檔 |
| EOS-HARNESS-CHECK | 已跑 | `gradlew check` → BUILD SUCCESSFUL；unit 32／integration 8，0 failure |
| EOS-LOOP-RELEASE | 已跑（API L1） | `bootRun` + `docs\run-api-smoke.ps1` → `ALL_API_SMOKE_OK`；TRADE-002 手動驗證 |

### Runtime Smoke（`EOS-LOOP-RELEASE`）

```text
級別: L1（API）
啟動: .\gradlew.bat bootRun
埠: 8093
探活: health=UP  UI=200
劇情: SAGA-001=COMPLETED/90000.0000 ; SAGA-002=COMPENSATED/100000.0000 ; TCC-002=COMPENSATED/100000.0000 ; TRADE-001=404
TRADE-002: POST 壞 JSON=400 ; DELETE /api/v1/trades=405 Allow=POST,GET
amount: 0.3333 × 0.3333 → 202 回應 0.1111
UI automation: N/A（本次未改前端）
時間: 2026-10-01 15:49
EOS-GRAPH: N/A — 單 Agent
```

## N/A

| ID | 理由 |
|----|------|
| EOS-LOOP-PR | 直接 commit main，未開 PR |
| EOS-HARNESS-EOS | 未改公版 |
| UI Smoke | 前端未變更 |

## 對話面板

本報告內容已於同輪對話顯示：是
