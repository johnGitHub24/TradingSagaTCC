# 架構說明

> 衝突以 [TradingSagaTCC 規格書.md](../TradingSagaTCC%20規格書.md) 為準。  
> **詳細圖（角色／method／正負向／Case）：** [codeGraphic.html](codeGraphic.html)（非權威 VIEW）。

## 分層

| 層 | 職責 |
|----|------|
| Controller | HTTP／DTO；禁止 Repository |
| Service | Saga 編排、TCC、補償、查詢；`@Transactional` 指定哪一庫 |
| Repository | 單一庫存取 |
| Messaging | Outbox Relay、Kafka listener |

## 雙庫邊界（不變）

```text
訂單庫 orderdb          Kafka                 帳戶庫 accountdb
trade_orders            commands ──────────►  accounts
saga_instances     ◄─── events                tcc_reservations
saga_steps
outbox_events
```

訂單 Service **不得** 寫帳戶表；帳戶 TCC **不得** 寫訂單表。讀帳戶是否存在（啟動前）允許。

## 元件角色（摘要）

| 角色 | Class | 關鍵 function |
|------|-------|----------------|
| Entry UI | `app.js` | `place`／`pollSaga`／`reportOutcome` |
| HTTP | `TradeController` | `place` |
| 編排起點 | `SagaOrchestrator` | `start` |
| Outbox | `OutboxRelayJob`／`OutboxPublisherService` | `tick`／`append`／`publishPending` |
| Kafka 配線 | `SagaKafkaListeners` | `onCommand`／`onEvent` |
| TCC 參與者 | `AccountCommandHandler` | `onMessage` |
| TCC 資金 | `AccountTccService`／`Account` | `tryReserve`／`confirm`／`cancel` |
| 編排推進 | `OrderSagaEventHandler` | `onReserved`／`onConfirmed` |
| 補償 | `OrderMarkFailedAction` | `compensate` |

完整表與圖：codeGraphic Tab①。

## Entry → method（正向 SAGA-001）

| 步驟 | 元件 | Method |
|------|------|--------|
| 1 | `app.js` | `place(false)` → `POST /api/v1/trades` |
| 2 | `TradeController` | `place` → `SagaOrchestrator.start` |
| 3 | `SagaOrchestrator` | `start`：`TradeOrder.pending`＋`ACCOUNT_TRYING`＋Outbox `RESERVE_FUNDS` |
| 4 | `OutboxRelayJob` | `tick` → `OutboxPublisherService.publishPending` |
| 5 | `SagaKafkaListeners` | `onCommand` → `AccountCommandHandler.onMessage` |
| 6 | `AccountTccService` | `tryReserve` → `Account.tryReserve` |
| 7 | `OrderSagaEventHandler` | `onReserved` → Outbox `CONFIRM_FUNDS` |
| 8 | `AccountTccService` | `confirm(false)` → `Account.confirm`（扣款） |
| 9 | `OrderSagaEventHandler` | `onConfirmed` → `markFilled`＋`COMPLETED` |
| 10 | `app.js` | `pollSaga` → `reportOutcome` |

## 負向 method

| Case | 分歧點 | 鏈 |
|------|--------|-----|
| SAGA-002 | `tryReserve`→false | `FUNDS_FAILED` → `OrderSagaEventHandler.onMessage` → `compensate` → `markFailed`＋`COMPENSATED` |
| TCC-002 | `confirm(true)` | 內呼 `cancel` → `FUNDS_CANCELLED` → `compensate` |

**狀態語意：** 成功＝Saga `COMPLETED`＋訂單 `FILLED`；補償＝Saga `COMPENSATED`＋訂單 `FAILED`。

## 模組地圖

| 套件 | 說明 |
|------|------|
| `order` | 訂單／Saga 查詢與 HTTP |
| `account` | 帳戶查詢＋`AccountTccService` |
| `saga` | `SagaOrchestrator`、補償、事件推進 |
| `messaging` | Outbox、Kafka、EventLog |
| `expansion` | 預留介面（TCC／補償／Relay／Consumer） |
| `config` | 雙 DataSource、內嵌 Kafka、排程 |

## Case → Hotspot

見 [codeGraphic.html](codeGraphic.html) Tab④ 與 [testing.md](testing.md)。公版原則：EOS `knowledge/documentation.md` §流程案例圖。
