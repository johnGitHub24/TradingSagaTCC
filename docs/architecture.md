# 架構說明

> 衝突以 [TradingSagaTCC 規格書.md](../TradingSagaTCC%20規格書.md) 為準。

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

## 模組地圖

| 套件 | 說明 |
|------|------|
| `order` | 訂單／Saga 查詢與 HTTP |
| `account` | 帳戶查詢＋`AccountTccService` |
| `saga` | `SagaOrchestrator`、補償、事件推進 |
| `messaging` | Outbox、Kafka、EventLog |
| `expansion` | 預留介面（TCC／補償／Relay／Consumer） |
| `config` | 雙 DataSource、內嵌 Kafka、排程 |

## 執行時拓撲

```text
Browser :8093
  → TradeController 202
  → 訂單庫 TX：order + saga + outbox
  → OutboxRelay @Scheduled
  → Kafka commands
  → AccountTccService（帳戶庫 TX）
  → Kafka events
  → 完成或補償（訂單庫 TX）
```

內嵌 Kafka 預設開；`trading.kafka.embedded=false` 可改外接，**不必改雙庫邊界**。
