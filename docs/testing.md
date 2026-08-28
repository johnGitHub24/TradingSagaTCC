# 測試與驗證

> 衝突以主規格書為準。規範：EngineeringOS `knowledge/testing.md`  
> 教學／路線 A～C／疑難排解：EngineeringOS `knowledge/demo-ready-guide.md`

## 驗證入口

```powershell
.\scripts\check.ps1
```

`gradlew check`＝`test`（排除 `@Tag integration`）+ `integrationTest`。Performance：N/A。

## Case（單元 ↔ 整合成對）

| Case | 單元 | 整合 |
|------|------|------|
| SAGA-001 | `SagaOrchestratorTest`、`AccountTest` Try-Confirm、`AccountTccServiceTest` confirm | POST 1×10000 → COMPLETED／available 90000 |
| SAGA-002 | `AccountTest` 不足、`AccountTccServiceTest` false、`OrderMarkFailedActionTest` | POST 1×999999 → COMPENSATED／餘額不變 |
| TCC-002 | `AccountTest` Try-Cancel、`AccountTccServiceTest` forceFail | `forceFail=true` → COMPENSATED／餘額還原 |
| TRADE-001 | `TradeQueryServiceTest`、`GlobalExceptionHandlerTest` | GET 未知訂單 404 |
| ACCOUNT-001 | `AccountQueryServiceTest` | GET ACC-001 200 |
| OUTBOX-001 | `OutboxPublisherServiceTest`、`SagaOrchestratorTest` | events 含 `RESERVE_FUNDS` |

## DoD

- [x] 每個公開 Service 行為有單元測
- [x] 每個對外 API Happy + 錯誤路徑（404）
- [x] 契約成對、禁止單邊
- [x] `.\scripts\check.ps1` 綠
- [x] Runtime Smoke L1（API + 可選 UI）

## Runtime Smoke（:8093 · 不進 check）

| 腳本 | 級別 | 需 Node | 通過訊號 |
|------|------|---------|----------|
| `docs/run-l0-smoke.ps1` | L0 | 否 | `ALL_L0_SMOKE_OK` |
| `docs/run-api-smoke.ps1` | L1 API | 否 | `ALL_API_SMOKE_OK` |
| `docs/run-ui-smoke.ps1` | L1 UI | 是 | `ALL_UI_SMOKE_OK` |
| `docs/run-smoke-l1.ps1` | L1 編排 | 可選 | API 綠 + `UI automation=PASS\|N/A` |
| `docs/run-release-gate.ps1` | Release 閘 | 可選 | `check` 綠 + `ALL_RELEASE_GATE_OK`（含 L1） |

**Release 建議：** `bootRun` → `.\docs\run-release-gate.ps1`（`-SkipSmoke` 僅 check；`-SkipUi` 僅 API L1）。  
**Graph 路由：** `docs/graph-routing.md`（單 Agent → `EOS-GRAPH=N/A`）。

**L1 劇情（API／UI 共用）：** SAGA-001／002、TCC-002、TRADE-001。人看：`http://localhost:8093/test/runner.html`。

### 證據欄範例（`EOS-LOOP-RELEASE`）

```text
級別: L1
啟動: .\gradlew.bat bootRun
埠: 8093
探活: health=UP  UI=200
劇情: SAGA-001=COMPLETED/90000 ; SAGA-002=COMPENSATED/100000 ; TCC-002=COMPENSATED/100000 ; TRADE-001=404
UI automation: PASS
時間: <本地完成時刻>
```

公版範本來源：`EngineeringOS/eos-minimal/templates/docs/demo-ready/`（本專案為金樣實作）。
