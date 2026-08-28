# TradingSagaTCC 規格書（權威）

練習主題：**Saga／TCC／補償**（Kafka 當跨庫訊息橋）。  
衝突以此檔為準。EngineeringOS eos-minimal @ **0.1.13**。

## 1. 一句話

單一 JVM 模擬 **訂單庫** 與 **帳戶庫** 兩條交易邊界；用 Kafka + Outbox 串起編排式 Saga，帳戶資金走 **TCC（Try-Confirm-Cancel）**，失敗則 **補償** 訂單並釋放凍結。

## 2. 範圍 / 非範圍

### 做（簡單可運作）

- 雙 H2：`orderdb`／`accountdb`，禁止 XA／單事務跨庫寫入
- 本機 **內嵌 Kafka**（`bootRun` 不必 Docker）
- 訂單庫 Outbox → Kafka command；帳戶 Consumer 做 TCC；事件回訂單庫完成或補償
- 靜態 Vue 前台與後端同埠（`:8093`），可練成功／餘額不足／故意失敗三條劇情
- 單元＋整合成對 Case；驗證入口 `.\scripts\check.ps1`

### 不做（本版）

- Seata／獨立微服務進程／真實券商撮合
- 獨立 Outbox Relay 進程、獨立 Consumer 部署
- 認證／RBAC（N/A → 無 `docs/驗證設計.md`）
- Performance Gate（N/A）

### 預留擴增（契約先留，雙庫邊界不變）

| 擴增點 | 介面 | 本版行為 |
|--------|------|----------|
| 新 TCC 參與者（庫存等） | `TccResource` | 僅帳戶實作 |
| 新補償動作 | `CompensationAction` | 僅「訂單標失敗」 |
| 獨立 Outbox Relay | `OutboxRelay` | 同 JVM `@Scheduled` 輪詢 |
| 獨立 Consumer | `DomainEventConsumer` | 同 JVM `@KafkaListener` |
| 外接 Kafka | `trading.kafka.embedded=false` | 改 `bootstrap-servers` |

擴增時 **不得** 讓訂單 Service 寫帳戶表，也 **不得** 讓帳戶 Service 寫訂單表。

## 3. 劇情（SDD）

種子帳戶 `ACC-001`：available **100000**，frozen **0**。

| 劇情 | 操作 | 終態 |
|------|------|------|
| 成功 | BUY 1 × 10000 | 訂單 `FILLED`，Saga `COMPLETED`，available 90000 |
| 餘額不足 | BUY 1 × 999999 | 訂單 `FAILED`，Saga `COMPENSATED`，帳戶不變 |
| 故意失敗（練補償） | `forceFail=true`、金額合法 | Try 成功後 Confirm 改 Cancel；訂單 `FAILED`，Saga `COMPENSATED`，帳戶還原 |

HTTP：請求合法即 **202**（Saga 已建）；終態靠輪詢 `GET /api/v1/sagas/{sagaId}`。語法／欄位錯 **422**；資源不存在 **404**。

## 4. 狀態

**訂單** `PENDING` → `FILLED`｜`FAILED`  
**Saga** `STARTED` → `ACCOUNT_TRYING` → `ACCOUNT_CONFIRMING` → `COMPLETED`  
失敗：`ACCOUNT_TRYING`／`ACCOUNT_CONFIRMING` → `COMPENSATING` → `COMPENSATED`  
**TCC 預留** `TRYING` → `CONFIRMED`｜`CANCELLED`（Try／Cancel 須冪等）

## 5. Kafka

| Topic | 方向 |
|-------|------|
| `trading.saga.commands` | 訂單 Outbox → 帳戶：`RESERVE_FUNDS`／`CONFIRM_FUNDS`／`CANCEL_FUNDS` |
| `trading.saga.events` | 帳戶 → 訂單：`FUNDS_RESERVED`／`FUNDS_CONFIRMED`／`FUNDS_FAILED`／`FUNDS_CANCELLED` |

## 6. API（摘要）

權威細節見 [API規格書.md](API規格書.md)。

| Method | Path | 說明 |
|--------|------|------|
| POST | `/api/v1/trades` | 啟動 Saga（202） |
| GET | `/api/v1/trades` | 訂單列表 |
| GET | `/api/v1/trades/{orderId}` | 單筆 |
| GET | `/api/v1/sagas/{sagaId}` | Saga＋步驟 |
| GET | `/api/v1/accounts/{accountId}` | 餘額 |
| POST | `/api/v1/accounts/{accountId}/reset` | 還原種子（練習用） |
| GET | `/api/v1/events` | 記憶體 Kafka 軌跡 |
| GET | `/api/v1/demo/state` | 前台一次拉齊 |

## 7. 驗收 Case（單元＋整合成對）

| Case | 單元 | 整合 | Acceptance |
|------|------|------|------------|
| SAGA-001 | Orchestrator 成功路徑寫 Outbox `RESERVE_FUNDS` | POST 合法單 → 終態 FILLED／COMPLETED，扣款 | 成功劇情 |
| SAGA-002 | 帳戶不足時領域拒絕 Try | POST 超額 → COMPENSATED，帳戶不變 | 餘額不足 |
| TCC-002 | Try 後 Cancel 還原 available | `forceFail=true` → COMPENSATED，帳戶還原 | 故意失敗 |
| TRADE-001 | 查單不存在丟 404 語意 | GET 未知訂單 404 | 錯誤路徑 |
| ACCOUNT-001 | 查得到種子帳戶 | GET ACC-001 200 | 查餘額 |
| OUTBOX-001 | append 後 unpublished | 下單後 command topic 有訊息 | Outbox→Kafka |

TCC 成功 Confirm 由 **SAGA-001** 覆蓋（不另開重複 HTTP Case）。

## 8. 驗證

```powershell
.\scripts\check.ps1
.\gradlew.bat bootRun
```

前台：http://localhost:8093/  
Gate **不**要求 npm／Docker。
