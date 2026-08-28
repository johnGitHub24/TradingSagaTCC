# TradingSagaTCC — 專案規則（薄）

繼承：EngineeringOS eos-minimal @ **0.1.13**
公版：`EngineeringOS/eos-minimal/`
權威規格：[TradingSagaTCC 規格書.md](TradingSagaTCC%20規格書.md)

## 與公版差異

- Backend port: **8093**
- Framework: Spring Boot 3.2 · Java 21 · JPA（**雙 DataSource**）
- DB: 雙 H2（`orderdb`／`accountdb`）；禁止 XA 跨庫寫入
- Kafka：預設**內嵌 broker**（`trading.kafka.embedded=true`）；不必 Docker
- Frontend: 同埠靜態 Vue 3（`src/main/resources/static/`）；optional-frontend: **no**
- 驗證入口：`.\scripts\check.ps1`（`gradlew check`＝unit + integration）
- 本機 Demo：IntelliJ／Gradle `bootRun`（**勿**對 `*Application` 綠箭頭）→ http://localhost:8093/
- **Demo-ready：** 權威 `docs/testing.md`＋公版 `testing.md`；教學 `demo-ready-guide.md`；L1 編排 `docs/run-smoke-l1.ps1`
- Docs standard：`knowledge/documentation.md`
- 無 Security → 無 `docs/驗證設計.md`

## 本專案專屬

- Domain: Kafka + 編排式 Saga + 帳戶 TCC + 補償；訂單 Outbox
- 擴增點：`TccResource`／`CompensationAction`／`OutboxRelay`／`DomainEventConsumer`（雙庫邊界不變）
- Case：SAGA-001／SAGA-002／TCC-002／TRADE-001／ACCOUNT-001／OUTBOX-001（單元+整合成對）
- 架構：`docs/architecture.md`；DB：`docs/資料庫設計.md`；測試：`docs/testing.md`
- API：[API規格書.md](API規格書.md)

## 註解深度
- comment_verbosity: **detailed**
- 權威：`EngineeringOS/eos-minimal/knowledge/comments.md` §0／§3b（eos-minimal @ 0.1.13）
- 結構：【職責】【技巧】【概念】；簡單 getter 可併入類別說明

## Git Remote
- 帳號：`johnGitHub24`；一專案一 repo
- 規範：`EngineeringOS/eos-minimal/knowledge/專案上船-GitHub.md`

## 回寫

問題與公版改善建議 → `EngineeringOS/eos-minimal/feedback/SYNC_LOG.md`
