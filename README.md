# TradingSagaTCC

練習 **Saga／TCC／補償**：雙 H2 邊界 + Kafka Outbox + 同埠 Vue 前台。

## 文件入口

單一入口：本 README。衝突以主規格為準。

| 文件 | 說明 |
|------|------|
| [TradingSagaTCC 規格書.md](TradingSagaTCC%20規格書.md) | **主規格書（權威）** |
| [API規格書.md](API規格書.md) | API 端點、錯誤碼 |
| [docs/architecture.md](docs/architecture.md) | 分層與雙庫／Kafka |
| [docs/codeGraphic.html](docs/codeGraphic.html) | Tab 式架構圖（非權威） |
| [docs/swagger.html](docs/swagger.html) | API（Swagger UI） |
| [docs/testing.md](docs/testing.md) | Case／check／DoD／**Runtime Smoke L0～L1** |
| [docs/graph-routing.md](docs/graph-routing.md) | **最小 Graph**（單 Agent N/A；Release 編排） |
| [docs/資料庫設計.md](docs/資料庫設計.md) | 雙庫表 |
| [CLAUDE.md](CLAUDE.md) | AI／工程薄規則（繼承 EOS） |
| [scripts/README.md](scripts/README.md) | Pure 驗證腳本 |

## Stack

| Layer | Technology |
|-------|-----------|
| Frontend | Vue 3 ESM（靜態，同埠 :8093） |
| Backend | Spring Boot 3.2.2 · Java 21 · 雙 JPA DataSource |
| Messaging | Spring Kafka（內嵌 broker） |
| DB | H2 `orderdb` + `accountdb` |
| Build | Gradle 8.5 · JDK 21 |

## Quick Start

```powershell
.\scripts\check.ps1
.\gradlew.bat bootRun
```

瀏覽器開 **http://localhost:8093/**：

1. **成功路徑** — 扣款 10000
2. **餘額不足** — Saga 補償，餘額不變
3. **故意失敗** — TCC Cancel，餘額還原

IntelliJ：Gradle `bootRun`（**不要**對 `TradingSagaTccApplication` 綠箭頭）。

| URL | 說明 |
|-----|------|
| http://localhost:8093/ | 練習前台 |
| http://localhost:8093/test/runner.html | L1 UI Smoke（RUN TESTING） |
| http://localhost:8093/swagger-ui.html | OpenAPI |
| http://localhost:8093/h2-console | H2（先連 order JDBC，再改連 account） |
| http://localhost:8093/actuator/health | 健康檢查 |

H2 JDBC：

- 訂單庫 `jdbc:h2:mem:orderdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`
- 帳戶庫 `jdbc:h2:mem:accountdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`
- 帳號 `sa`、密碼空白

## 練習技能

- 編排式 Saga（訂單庫擁有流程狀態）
- TCC Try-Confirm-Cancel（帳戶庫資金凍結）
- 補償（訂單 FAILED，凍結釋放）
- Outbox → Kafka command／event
- 雙庫邊界（禁止 XA）

## 驗證

`.\scripts\check.ps1`（unit + integration）。Runtime Smoke L0～L1、Case 表、證據欄 → [docs/testing.md](docs/testing.md)（教學見 EOS `demo-ready-guide.md`）。
