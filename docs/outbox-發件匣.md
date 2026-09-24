# Outbox（發件匣）說明

> 衝突以主規格／[architecture.md](architecture.md) 為準。本檔為學習敘述（VIEW）。

## 1. 中文怎麼叫

| 英文 | 建議中文 | 理解 |
|------|----------|------|
| **Outbox** | **發件匣**（或待送佇列） | 像 email 的 Outbox：信寫好了、還沒寄出 |
| **Relay** | **轉送／接力** | 把匣裡的信真正送到 Kafka |
| **OutboxRelayJob** | **發件匣轉送排程** | 定時郵差：每隔一段時間掃匣、寄信 |

口訣：**Outbox＝本庫的發件匣；Relay Job＝定時把匣裡的信送到 Kafka 的郵差。**

## 2. 為什麼需要發件匣

下單成功後若**直接**呼叫 Kafka：

- DB 已提交、Kafka 失敗 → 庫裡有單、外面沒訊息  
- 或順序顛倒造成重試困難  

**Transactional Outbox（交易型發件匣／提交後發訊）** 做法：

1. 與訂單／Saga **同一個訂單庫交易**寫入 `outbox_events`（`published_at = NULL`＝待發）  
2. 交易提交成功後，才由背景程式把待發列送到 Kafka  
3. 送成功才標記 `published_at`  

這樣「有訂單就一定有待發紀錄」；寄失敗可下一輪再寄。

## 3. 本專案角色對照

```text
SagaOrchestrator.start / OrderSagaEventHandler.onReserved
        │  同一 order TX
        ▼
OutboxPort.append  →  outbox_events（發件匣落庫）
        │  TX commit 後
        ▼
OutboxRelayJob.tick（定時）
        │
        ▼
OutboxPublisherService.publishPending（轉送）
        │  先 Kafka send，成功再 markPublished
        ▼
Kafka  →  SagaKafkaListeners
```

| 元件 | 中文角色 | 做什麼 |
|------|----------|--------|
| `outbox_events` | 發件匣表 | 存待發／已發訊息 |
| `OutboxPort.append` | 投遞進匣 | 業務同 TX 寫入 unpublished |
| `OutboxRelayJob.tick` | 定時郵差 | 排程呼叫轉送 |
| `OutboxPublisherService.publishPending` | 實際寄信 | 掃 unpublished → Kafka → 標已發 |
| `OutboxEvent` | 匣裡的一封信 | topic／key／payload／publishedAt |

## 4. 敘事（學習用）

想像你在櫃檯完成一筆下單：

1. **櫃台（Orchestrator）** 把「請幫我凍結資金」這句話寫進**發件匣**，跟訂單一起蓋章存檔（同交易）。  
2. 蓋章成功後，客人拿到的回條只是「受理中」（HTTP 202／PENDING），**不是**已扣款。  
3. **郵差（OutboxRelayJob）** 定時打開發件匣，把還沒寄的信送到 Kafka 郵局。  
4. **帳戶櫃檯（AccountCommandHandler／TCC）** 收到信才真正 Try／Confirm。  

若沒有發件匣、櫃台直接打電話給帳戶櫃檯：電話斷線時你可能已經蓋章，對方卻沒接到——兩邊對不上帳。

## 5. 與「預留票」差在哪

| | Outbox 發件匣 | TCC 預留票 |
|--|---------------|------------|
| 庫 | **訂單庫** `outbox_events` | **帳戶庫** `tcc_reservations` |
| 用途 | 可靠把命令／意圖送進 Kafka | 帳戶資金預留狀態與冪等 |
| Key | 列 id；訊息 key 常用 sagaId | PK＝sagaId |

兩者都服務「可靠與對帳」，但**一在訂單側寄信、一在帳戶側管錢**。

## 6. 相關程式與設定

- `OutboxRelayJob`：`trading.outbox.poll-ms`（預設 200；測試可 50）  
- Case：OUTBOX-001（軌跡出現 `RESERVE_FUNDS`）  
- 圖：`codeGraphic.html` 正向階段 A／B  

## 7. 一句話複習

**發件匣先落庫、提交後再寄 Kafka；郵差是排程，不是下單 HTTP 同步去寄。**
