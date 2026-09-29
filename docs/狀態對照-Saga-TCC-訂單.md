# 狀態對照：Saga／TCC 預留票／訂單

> 衝突以主規格／[architecture.md](architecture.md) 為準。本檔為學習敘述（VIEW）。  
> 程式權威：`SagaStatus`、`TccState`、`OrderStatus` 三個 enum 的 JavaDoc。

## 1. 一句話

**Saga 是「流程進度表」，TCC 預留票是「帳戶的資金憑證」，訂單是「給使用者看的結果」。**  
三者存在不同表（Saga／訂單在 orderdb，預留票在 accountdb），彼此不直接讀取，只靠同一個 `sagaId` 與 Kafka 事件串起來。

## 2. 三組狀態總覽

| | Saga 狀態 | TCC 預留票狀態 | 訂單狀態 |
|--|-----------|----------------|----------|
| Enum | `SagaStatus` | `TccState` | `OrderStatus` |
| 表 | orderdb `saga_instances` | accountdb `tcc_reservations` | orderdb `trade_orders` |
| 管什麼 | 整個流程走到哪一步 | 這筆錢是凍結、已扣、還是已退 | 對使用者而言成交了沒 |
| 誰改它 | 訂單側：`SagaOrchestrator`、`OrderSagaEventHandler`、`OrderMarkFailedAction` | 帳戶側：`AccountTccService` | 訂單側：`OrderSagaEventHandler`、`OrderMarkFailedAction` |
| 值 | `STARTED`、`ACCOUNT_TRYING`、`ACCOUNT_CONFIRMING`、`COMPLETED`、`COMPENSATING`、`COMPENSATED`、`FAILED` | `TRYING`、`CONFIRMED`、`CANCELLED` | `PENDING`、`FILLED`、`FAILED` |
| 什麼時候出現 | 下單當下建立 | **Try 成功才寫一列**；Try 失敗根本沒有這張票 | 下單當下建立 |
| 轉換規則放哪 | `SagaStatus.canTransitionTo`（集中規則表） | `TccReservation.markConfirmed`／`markCancelled` | `TradeOrder.markFilled`／`markFailed` |

### 全景圖：狀態怎麼在兩庫之間流動

兩庫之間**沒有直接連線**，只靠 Kafka 事件傳話。帳戶側改完預留票才發事件，訂單側收到事件才改 Saga 和訂單。

```mermaid
flowchart LR
    subgraph ORDERSIDE["訂單側（orderdb）"]
        OH["OrderSagaEventHandler<br/>OrderMarkFailedAction"]
        SAGA[("saga_instances<br/>Saga 狀態")]
        ORD[("trade_orders<br/>訂單狀態")]
        OB[("outbox_events<br/>發件匣")]
    end
    subgraph KAFKA["Kafka"]
        CMD[["command topic"]]
        EVT[["event topic"]]
    end
    subgraph ACCOUNTSIDE["帳戶側（accountdb）"]
        AH["AccountCommandHandler<br/>AccountTccService"]
        TCC[("tcc_reservations<br/>預留票狀態")]
        ACC[("accounts<br/>available／frozen")]
    end

    OB -- "① OutboxRelayJob 寄出命令" --> CMD
    CMD -- "② RESERVE／CONFIRM_FUNDS" --> AH
    AH -- "③ 先改" --> TCC
    AH -- "③ 先改" --> ACC
    AH -- "④ 再發結果事件" --> EVT
    EVT -- "⑤ FUNDS_RESERVED／CONFIRMED<br/>FUNDS_FAILED／CANCELLED" --> OH
    OH -- "⑥ 推進" --> SAGA
    OH -- "⑥ FILLED／FAILED" --> ORD
    OH -. "⑦ 需要下一步時<br/>登記 CONFIRM_FUNDS" .-> OB
```

圖上編號就是一輪的順序：命令從發件匣出發（①②），帳戶側先改自己的表（③）才發事件（④），訂單側收到後改 Saga 與訂單（⑤⑥）；成功路徑還需要 Confirm，就再往發件匣登記一道命令（⑦），回到 ①。

## 3. 各自的狀態圖

### Saga（流程）

```mermaid
stateDiagram-v2
    [*] --> STARTED: SagaInstance.start
    STARTED --> ACCOUNT_TRYING: SagaOrchestrator.start 登記 RESERVE_FUNDS
    ACCOUNT_TRYING --> ACCOUNT_CONFIRMING: 收到 FUNDS_RESERVED，登記 CONFIRM_FUNDS
    ACCOUNT_CONFIRMING --> COMPLETED: 收到 FUNDS_CONFIRMED
    STARTED --> COMPENSATING: 失敗事件（理論上）
    ACCOUNT_TRYING --> COMPENSATING: 收到 FUNDS_FAILED（SAGA-002）
    ACCOUNT_CONFIRMING --> COMPENSATING: 收到 FUNDS_CANCELLED（TCC-002）
    COMPENSATING --> COMPENSATED: 同一 TX 緊接
    COMPENSATING --> FAILED: 預留，目前未使用
    COMPLETED --> [*]
    COMPENSATED --> [*]
    FAILED --> [*]

    classDef ok fill:#d1e7dd,stroke:#198754,color:#111
    classDef warn fill:#fff3cd,stroke:#ffc107,color:#111
    classDef unused fill:#eeeeee,stroke:#999999,stroke-dasharray: 4 4,color:#111
    class COMPLETED ok
    class COMPENSATED warn
    class FAILED unused
```

<details>
<summary>純文字版</summary>

```text
正向：STARTED → ACCOUNT_TRYING → ACCOUNT_CONFIRMING → COMPLETED
負向：STARTED／ACCOUNT_TRYING／ACCOUNT_CONFIRMING → COMPENSATING → COMPENSATED
預留：COMPENSATING → FAILED（目前沒有程式走到）
```

</details>

| 狀態 | 誰設定 | 觸發時機 |
|------|--------|----------|
| `STARTED` | `SagaInstance.start` | 建立實體（初始值），同 TX 立刻轉下一步 |
| `ACCOUNT_TRYING` | `SagaOrchestrator.start` | 發件匣登記 `RESERVE_FUNDS` |
| `ACCOUNT_CONFIRMING` | `OrderSagaEventHandler.onReserved` | 收到 `FUNDS_RESERVED`，登記 `CONFIRM_FUNDS` |
| `COMPLETED` | `OrderSagaEventHandler.onConfirmed` | 收到 `FUNDS_CONFIRMED` |
| `COMPENSATING` | `OrderMarkFailedAction.compensate` | 收到 `FUNDS_FAILED`／`FUNDS_CANCELLED`（中間態） |
| `COMPENSATED` | `OrderMarkFailedAction.compensate` | 同一 TX 緊接 `COMPENSATING` |
| `FAILED` | （無） | 預留：補償本身也失敗時使用 |

### TCC 預留票（資金）

```mermaid
stateDiagram-v2
    state "（無票）" as NO_TICKET
    [*] --> NO_TICKET: 收到 RESERVE_FUNDS
    NO_TICKET --> TRYING: tryReserve 成功，available 轉 frozen
    NO_TICKET --> [*]: tryReserve 失敗（餘額不足，不寫票）
    TRYING --> CONFIRMED: confirm(false)，真正扣款
    TRYING --> CANCELLED: cancel，frozen 退回 available
    CONFIRMED --> [*]
    CANCELLED --> [*]

    classDef ok fill:#d1e7dd,stroke:#198754,color:#111
    classDef warn fill:#fff3cd,stroke:#ffc107,color:#111
    class CONFIRMED ok
    class CANCELLED warn
```

<details>
<summary>純文字版</summary>

```text
（無票）──Try 成功──► TRYING ──Confirm──► CONFIRMED
                         │
                         └──Cancel──► CANCELLED

Try 失敗（餘額不足）：不寫票，停在「無票」
```

</details>

| 狀態 | 帳戶餘額變化 | 誰設定 |
|------|--------------|--------|
| （無票） | 沒動 | — |
| `TRYING` | available → frozen（凍結） | `AccountTccService.tryReserve` |
| `CONFIRMED` | frozen 消失（真正扣款，total 下降） | `AccountTccService.confirm(false)` |
| `CANCELLED` | frozen → available（退回） | `AccountTccService.cancel`（forceFail 時由 `confirm(true)` 內部呼叫） |

限制：`CONFIRMED` 之後不能再 Cancel（`markCancelled` 會丟例外）；已 `CANCELLED` 再 Cancel 是空操作（冪等）。

### 訂單（結果）

```mermaid
stateDiagram-v2
    [*] --> PENDING: TradeOrder.pending（下單當下）
    PENDING --> FILLED: onConfirmed 收到 FUNDS_CONFIRMED
    PENDING --> FAILED: compensate 收到 FUNDS_FAILED／FUNDS_CANCELLED
    FILLED --> [*]
    FAILED --> [*]

    classDef ok fill:#d1e7dd,stroke:#198754,color:#111
    classDef warn fill:#fff3cd,stroke:#ffc107,color:#111
    class FILLED ok
    class FAILED warn
```

## 4. 三個情境逐步對照

每個情境先看時序圖（誰傳話給誰、狀態在哪一刻改變），再看表格（每一步三張表各自的值）。注意 TCC 永遠比 Saga 先變。

### SAGA-001 成功

```mermaid
sequenceDiagram
    autonumber
    participant UI as 前台 app.js
    participant O as 訂單側
    participant K as Kafka
    participant A as 帳戶側 TCC
    UI->>O: POST /api/v1/trades
    Note over O: Saga ACCOUNT_TRYING<br/>訂單 PENDING<br/>發件匣登記 RESERVE_FUNDS
    O-->>UI: 202 PENDING（附 sagaId）
    O->>K: RESERVE_FUNDS（OutboxRelayJob 寄出）
    K->>A: RESERVE_FUNDS
    Note over A: 預留票 TRYING<br/>available 轉 frozen
    A->>K: FUNDS_RESERVED
    K->>O: FUNDS_RESERVED
    Note over O: Saga ACCOUNT_CONFIRMING<br/>發件匣登記 CONFIRM_FUNDS
    O->>K: CONFIRM_FUNDS（OutboxRelayJob 寄出）
    K->>A: CONFIRM_FUNDS
    Note over A: 預留票 CONFIRMED<br/>frozen 扣掉
    A->>K: FUNDS_CONFIRMED
    K->>O: FUNDS_CONFIRMED
    Note over O: Saga COMPLETED<br/>訂單 FILLED
    UI->>O: GET /api/v1/sagas/{sagaId}（輪詢）
    O-->>UI: COMPLETED
```

| 步驟 | 事件／動作 | Saga | 預留票 | 訂單 |
|------|-----------|------|--------|------|
| 1 | `POST /trades`，`SagaOrchestrator.start` | `ACCOUNT_TRYING` | （無票） | `PENDING` |
| 2 | 郵差寄出 `RESERVE_FUNDS` → 帳戶 Try 成功 | `ACCOUNT_TRYING` | `TRYING` | `PENDING` |
| 3 | 訂單側收到 `FUNDS_RESERVED` | `ACCOUNT_CONFIRMING` | `TRYING` | `PENDING` |
| 4 | 郵差寄出 `CONFIRM_FUNDS` → 帳戶 Confirm | `ACCOUNT_CONFIRMING` | `CONFIRMED` | `PENDING` |
| 5 | 訂單側收到 `FUNDS_CONFIRMED` | **`COMPLETED`** | `CONFIRMED` | **`FILLED`** |

### SAGA-002 餘額不足

```mermaid
sequenceDiagram
    autonumber
    participant UI as 前台 app.js
    participant O as 訂單側
    participant K as Kafka
    participant A as 帳戶側 TCC
    UI->>O: POST /api/v1/trades（金額大於 available）
    Note over O: Saga ACCOUNT_TRYING<br/>訂單 PENDING
    O-->>UI: 202 PENDING
    O->>K: RESERVE_FUNDS（OutboxRelayJob 寄出）
    K->>A: RESERVE_FUNDS
    Note over A: tryReserve 回 false<br/>不寫預留票，餘額沒動
    A->>K: FUNDS_FAILED
    K->>O: FUNDS_FAILED
    Note over O: OrderMarkFailedAction.compensate<br/>Saga COMPENSATING 轉 COMPENSATED<br/>訂單 FAILED
    UI->>O: GET /api/v1/sagas/{sagaId}（輪詢）
    O-->>UI: COMPENSATED
```

| 步驟 | 事件／動作 | Saga | 預留票 | 訂單 |
|------|-----------|------|--------|------|
| 1 | `POST /trades`（金額大於 available） | `ACCOUNT_TRYING` | （無票） | `PENDING` |
| 2 | 帳戶 Try 失敗，**不寫票** | `ACCOUNT_TRYING` | （無票） | `PENDING` |
| 3 | 訂單側收到 `FUNDS_FAILED` → 補償 | **`COMPENSATED`** | （無票） | **`FAILED`** |

帳戶餘額從頭到尾沒動過。

### TCC-002 forceFail（Confirm 階段故意失敗）

```mermaid
sequenceDiagram
    autonumber
    participant UI as 前台 app.js
    participant O as 訂單側
    participant K as Kafka
    participant A as 帳戶側 TCC
    UI->>O: POST /api/v1/trades（forceFail=true）
    Note over O: Saga ACCOUNT_TRYING<br/>訂單 PENDING
    O-->>UI: 202 PENDING
    O->>K: RESERVE_FUNDS
    K->>A: RESERVE_FUNDS
    Note over A: 預留票 TRYING<br/>available 轉 frozen
    A->>K: FUNDS_RESERVED
    K->>O: FUNDS_RESERVED
    Note over O: Saga ACCOUNT_CONFIRMING
    O->>K: CONFIRM_FUNDS（帶 forceFail）
    K->>A: CONFIRM_FUNDS
    Note over A: confirm(true) 內部改呼叫 cancel<br/>預留票 CANCELLED<br/>frozen 退回 available
    A->>K: FUNDS_CANCELLED
    K->>O: FUNDS_CANCELLED
    Note over O: compensate<br/>Saga COMPENSATED<br/>訂單 FAILED
    UI->>O: GET /api/v1/sagas/{sagaId}（輪詢）
    O-->>UI: COMPENSATED
```

| 步驟 | 事件／動作 | Saga | 預留票 | 訂單 |
|------|-----------|------|--------|------|
| 1 | `POST /trades`（`forceFail=true`） | `ACCOUNT_TRYING` | （無票） | `PENDING` |
| 2 | 帳戶 Try 成功 | `ACCOUNT_TRYING` | `TRYING` | `PENDING` |
| 3 | 訂單側收到 `FUNDS_RESERVED` | `ACCOUNT_CONFIRMING` | `TRYING` | `PENDING` |
| 4 | `confirm(true)` 內部改走 Cancel | `ACCOUNT_CONFIRMING` | `CANCELLED` | `PENDING` |
| 5 | 訂單側收到 `FUNDS_CANCELLED` → 補償 | **`COMPENSATED`** | `CANCELLED` | **`FAILED`** |

凍結的錢已退回 available。

### 終態組合速查

| 情境 | Saga | 預留票 | 訂單 | 帳戶 available |
|------|------|--------|------|----------------|
| SAGA-001 成功 | `COMPLETED` | `CONFIRMED` | `FILLED` | 減少 |
| SAGA-002 餘額不足 | `COMPENSATED` | （無票） | `FAILED` | 不變 |
| TCC-002 forceFail | `COMPENSATED` | `CANCELLED` | `FAILED` | 不變（先凍結後退回） |

## 5. 為什麼會「暫時對不上」

帳戶側先改預留票，再發 Kafka 事件；訂單側收到事件才更新 Saga 與訂單。  
所以上表步驟 2、4 會看到「預留票已經變了，Saga 還沒變」。這是**最終一致**：兩庫沒有 XA 全域事務（見 [資料庫設計.md](資料庫設計.md)），只保證事件送達後會對齊。

前台因此要：

- **輪詢 Saga**（`GET /api/v1/sagas/{sagaId}`）直到終態，而不是看 HTTP 202 就下結論；
- **同時看 Saga 與訂單**再下結論（`app.js` 的 `reportOutcome`）；
- 輪詢逾時只能說「還沒結束」，不能誤報成失敗。

前台判讀流程（`pollSaga` → `reportOutcome`）：

```mermaid
flowchart TD
    A["POST /trades 回 202<br/>拿到 sagaId"] --> B["pollSaga<br/>GET /sagas/{sagaId}"]
    B --> C{"Saga 到終態了嗎？"}
    C -- "還沒，未逾時" --> B
    C -- "還沒，已逾時" --> T["提示：流程尚未結束<br/>不可誤報失敗"]
    C -- "COMPLETED" --> D{"訂單是 FILLED？"}
    D -- "是" --> S["成功：已扣款"]
    D -- "否" --> W["提示：請重新整理"]
    C -- "COMPENSATED／FAILED" --> F["補償完成：訂單 FAILED<br/>帳戶應已還原"]

    classDef ok fill:#d1e7dd,stroke:#198754,color:#111
    classDef warn fill:#fff3cd,stroke:#ffc107,color:#111
    class S ok
    class F,T,W warn
```

## 6. 常見誤會

| 誤會 | 實際 |
|------|------|
| `CANCELLED` 和 `COMPENSATED` 是同一件事 | 層級不同。`CANCELLED` 是「這筆錢已退回」（單一資源）；`COMPENSATED` 是「整個流程的失敗處理已收尾」。SAGA-002 就是 Saga `COMPENSATED` 但根本沒有預留票 |
| 訂單 `FAILED` 表示系統出錯 | 不是。Saga `COMPENSATED`＋訂單 `FAILED` 是失敗路徑**正確收尾** |
| 看得到 `COMPENSATING` | 幾乎看不到：`compensate` 在同一 TX 內先轉 `COMPENSATING` 再立刻轉 `COMPENSATED` |
| Saga `FAILED` 會出現 | 目前沒有程式設定它，只是狀態機預留的出口 |
| TCC 知道流程在「確認中」 | 不知道。預留票只記錢的狀況；「現在在等誰」只有 Saga 會記 |
| 編排者會發 `CANCEL_FUNDS` | 目前不會。帳戶側有處理 `CANCEL_FUNDS`，但沒有程式發送；本版的 Cancel 只由 forceFail 觸發 |

## 7. 重送時怎麼不出錯（冪等）

Kafka 可能重送同一則事件，三邊各自防重：

| 位置 | 防重方式 |
|------|----------|
| Saga | `OrderSagaEventHandler`、`OrderMarkFailedAction` 看到 `isTerminal()` 直接 return；重送的 `FUNDS_RESERVED` 看到 `ACCOUNT_CONFIRMING` 也略過 |
| 預留票 | PK＝`sagaId`。Try 看到票已存在就不再凍結；Confirm 看到 `CONFIRMED` 直接回成功；Cancel 看到 `CANCELLED` 直接略過 |
| 狀態機 | `SagaStatus.canTransitionTo` 讓終態回 false，即使有漏網之魚也改不動已結束的 Saga |

## 8. 怎麼自己觀察

1. `bootRun` 後開 http://localhost:8093/
2. 下單後記下 `sagaId`，查：
   - `GET /api/v1/sagas/{sagaId}` — Saga 狀態與步驟軌跡
   - `GET /api/v1/trades/{orderId}` — 訂單狀態
   - `GET /api/v1/accounts/ACC-001` — available／frozen
   - `GET /api/v1/events` — Kafka 走過哪些訊息 type
3. 分別用一般下單、超額下單、`forceFail=true` 跑三個情境，對照第 4 節的表。
4. 每輪前可 `POST /api/v1/accounts/ACC-001/reset` 還原種子餘額。

## 9. 相關程式

| 主題 | 位置 |
|------|------|
| Saga 狀態與規則 | `order/domain/SagaStatus.java`、`SagaInstance.transitionTo` |
| 預留票 | `account/domain/TccState.java`、`TccReservation.java`、`AccountTccService.java` |
| 訂單 | `order/domain/OrderStatus.java`、`TradeOrder.java` |
| 事件推進 | `messaging/OrderSagaEventHandler.java` |
| 補償 | `saga/OrderMarkFailedAction.java` |
| 前台判讀 | `static/app.js` 的 `pollSaga`、`reportOutcome` |
| 延伸閱讀 | [outbox-發件匣.md](outbox-發件匣.md)（命令怎麼寄出）、[codeGraphic.html](codeGraphic.html)（正負向流程圖） |

## 10. 一句話複習

**預留票先變、Saga 後跟；成功看 `COMPLETED`＋`FILLED`，失敗看 `COMPENSATED`＋`FAILED`；錢有沒有還，看預留票和 available。**
