# TradingSagaTCC API 規格書

衝突以 [TradingSagaTCC 規格書.md](TradingSagaTCC%20規格書.md) 為準。

Base：`http://localhost:8093`  
OpenAPI：`/swagger-ui.html`、`/v3/api-docs`

## POST /api/v1/trades

啟動跨庫 Saga。合法請求 **202**，body 含 `orderId`／`sagaId`／`status`（當下多為 `STARTED`／`PENDING`）。

```json
{
  "accountId": "ACC-001",
  "symbol": "BTCUSDT",
  "side": "BUY",
  "quantity": 1,
  "price": 10000,
  "forceFail": false
}
```

| 欄位 | 規則 |
|------|------|
| accountId | 必填；不存在 → 404 |
| symbol | 必填、≤32 |
| side | `BUY` 或 `SELL` |
| quantity | 必填、> 0 |
| price | 必填、> 0 |
| forceFail | 可省略，預設 false；true 時帳戶 Confirm 改走 Cancel |

驗證失敗 → **422** `{ error, message, fieldErrors }`。

## GET /api/v1/trades

訂單陣列（新到舊），元素同單筆。

## GET /api/v1/trades/{orderId}

**200** 或 **404**。

```json
{
  "orderId": "...",
  "sagaId": "...",
  "accountId": "ACC-001",
  "symbol": "BTCUSDT",
  "side": "BUY",
  "quantity": 1,
  "price": 10000,
  "amount": 10000,
  "status": "FILLED",
  "forceFail": false,
  "createdAt": "2026-08-27T08:00:00Z"
}
```

## GET /api/v1/sagas/{sagaId}

**200** 或 **404**。

```json
{
  "sagaId": "...",
  "orderId": "...",
  "status": "COMPLETED",
  "steps": [
    { "name": "ORDER_CREATED", "detail": "...", "at": "..." }
  ]
}
```

終態：`COMPLETED`｜`COMPENSATED`｜`FAILED`。

## GET /api/v1/accounts/{accountId}

**200** 或 **404**。`total` = available + frozen。

## POST /api/v1/accounts/{accountId}/reset

將指定帳戶還原為 available=100000、frozen=0（練習重複跑劇情）。**200** 帳戶 DTO；不存在 **404**。

## GET /api/v1/events

Kafka 軌跡（記憶體 ring，最多 100 筆，新到舊）。

```json
[{ "topic": "trading.saga.commands", "type": "RESERVE_FUNDS", "sagaId": "...", "payload": "...", "at": "..." }]
```

## GET /api/v1/demo/state

`{ "account", "orders", "events" }`，account 固定種子 `ACC-001`（若尚未 seed 則 404）。

## 錯誤形狀

```json
{
  "timestamp": "...",
  "status": 404,
  "error": "Not Found",
  "message": "..."
}
```
