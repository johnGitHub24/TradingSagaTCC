# Graph 路由（最小落地）

> **權威規則：** EngineeringOS `knowledge/agent-engineering.md` §3 Graph Engineering  
> **本檔定位：** 本專案怎麼套「節點／邊／狀態」；**非**第二套規則。  
> **與 CodeGraphic 無關：** `docs/codeGraphic.html` 是架構教學圖，不是多 Agent 編排。

## 一句話

- **單 Agent**（你 + 一個 Cursor）：完工報告 `EOS-GRAPH`＝**N/A** 即合規。  
- **≥2 Agent 並行**：Plan 必須寫清本檔的節點、寫入路徑、匯合點。  
- **狀態真相（SSOT）：** 規格／Case ID、`git` 工作區、`check` 結果、Smoke 終端訊號、完工報告——不靠「對話記憶」。

---

## 本專案預設拓樸（單 Agent · Release）

日常與推版共用同一條 Loop；Smoke **不**進 `scripts/check.ps1`（Pure），由 `docs/` 編排。

```mermaid
flowchart LR
  P[Plan] --> W[Work: TDD + Case]
  W --> H[Harness: check.ps1]
  H -->|紅| W
  H --> CR[CodeReviewer: 唯讀 diff]
  CR -->|Major>0| W
  CR --> RA[Release Agent: run-release-gate]
  RA -->|紅| W
  RA --> REP[完工報告 + 證據欄]
```

| 節點 | 產物 | 通過條件 |
|------|------|----------|
| **Plan** | Scope、Case 是否受影響 | 人工／任務描述核准 |
| **Work** | 程式 + 成對測試 | 對應 SAGA/TCC/TRADE… Case 有改到 |
| **Harness** | `scripts/check.ps1` | `BUILD SUCCESSFUL` |
| **CodeReviewer** | Major/Minor 報告 | Major=0；EOS `prompt/code-reviewer-agent-v1.md` |
| **Release Agent** | `docs/run-release-gate.ps1` | `ALL_RELEASE_GATE_OK`；prompt `release-agent-v1.md` |
| **匯合** | `docs/work-completion-reports/*.md` | 雙通道：檔案 + 對話面板 |

**編排腳本（不破 Pure）：**

```powershell
.\scripts\check.ps1                    # Harness（CodeReviewer 前置）
# check 綠 → CodeReviewer Task（見 EngineeringOS/eos-minimal/prompt/code-reviewer-agent-v1.md）
# check 綠 + Major=0 → Release Agent Task（見 prompt/release-agent-v1.md）
.\gradlew.bat bootRun                  # 終端 1
.\docs\run-release-gate.ps1            # 終端 2：check + L1（bootRun 須已 UP）
# 僅 check：.\docs\run-release-gate.ps1 -SkipSmoke
# 僅 Smoke（check 已綠）：.\docs\run-release-gate.ps1 -SkipCheck
```

---

## CodeReviewer 節點（Harness 綠 → 唯讀審查）

> **權威 prompt：** `EngineeringOS/eos-minimal/prompt/code-reviewer-agent-v1.md`  
> **Loop ID：** `EOS-LOOP-REVIEW`

| 項目 | 規則 |
|------|------|
| **進入** | Plan Scope（path/Case）+ diff + **check 已綠** |
| **行為** | 唯讀；依 `review-checklist.md`；T1～T4 觸發時對照 SAGA-* Case |
| **退出** | Major=0 → Release Agent；Major≥1 → 回 Work |
| **單 Agent** | check 綠後 Cursor Task；`EOS-GRAPH`＝**N/A** |

**Cursor Task 範例：**

```text
Full Repository Path: d:\SouceDemo\RemoteSpringBoot\TradingSagaTCC
Diff: uncommitted changes
Plan Scope: src/.../saga/；Case SAGA-001、TCC-002
Check evidence: .\scripts\check.ps1 → BUILD SUCCESSFUL

依 EngineeringOS eos-minimal/prompt/code-reviewer-agent-v1.md 執行 CodeReviewer。
```

---

## Release Agent 節點（腳本 + 證據欄 · 不改 code）

> **權威 prompt：** `EngineeringOS/eos-minimal/prompt/release-agent-v1.md`  
> **Loop ID：** `EOS-LOOP-RELEASE`

| 項目 | 規則 |
|------|------|
| **進入** | CodeReviewer Major=0；L1 時 **bootRun :8093 UP** |
| **行為** | 跑 `run-release-gate.ps1`；可選 `verify-runner-served.ps1` |
| **可寫** | 僅 `docs/work-completion-reports/` |
| **退出** | `ALL_RELEASE_GATE_OK` + 證據欄雙通道 |

**Cursor Task 範例：**

```text
Full Repository Path: d:\SouceDemo\RemoteSpringBoot\TradingSagaTCC
HTTP port: 8093
Review status: PASS
bootRun: UP

依 EngineeringOS eos-minimal/prompt/release-agent-v1.md 執行 Release Agent。
跑 .\docs\run-release-gate.ps1 -SkipCheck；填 EOS-LOOP-RELEASE 證據欄；禁止改 src/。
```

---

## 多 Agent 範例（僅在並行時啟用）

改 **Saga 後端** 與 **Smoke 腳本** 可拆兩個 Agent，但**禁止**無路由並行改同一檔。

```mermaid
flowchart TB
  P[Plan: 路由表] --> A[Agent A: src/ saga]
  P --> B[Agent B: docs/ smoke + static/test]
  A --> M[匯合: 單一工作區]
  B --> M
  M --> H[Harness: check]
  H --> CR[CodeReviewer]
  CR --> RA[Release Agent]
  RA --> REP[完工報告]
  H -->|紅| P
  CR -->|Major| P
  RA -->|紅| W
```

| 邊 | 規則 |
|----|------|
| A → M | 只寫 `src/`、`src/test/`；不動 `docs/run-*-smoke.ps1` |
| B → M | 只寫 `docs/`、`static/test/`；劇情對齊 Case ID |
| M → H | 匯合後 **先** `check` |
| H → CR | check 綠才 CodeReviewer（唯讀） |
| CR → RA | Major=0；bootRun UP（L1） |
| RA → REP | `ALL_RELEASE_GATE_OK` + 證據欄雙通道 |
| RA 紅 → Work | Smoke／health 失敗 → 回 Work |

---

## 狀態契約（可觀測）

| 狀態 | 放哪 | 禁止 |
|------|------|------|
| 契約 | `API規格書.md`、Case 表（`docs/testing.md`） | Agent 口頭改 API 不落盤 |
| 測試綠燈 | `gradlew check` 輸出 | 跳過 check 宣告完成 |
| Demo 綠燈 | `ALL_L0_SMOKE_OK`／`ALL_API_SMOKE_OK`／`ALL_UI_SMOKE_OK` | check 綠就說可 Demo |
| 留痕 | `docs/work-completion-reports/` | 只口頭說「好了」 |

---

## 完工報告：`EOS-GRAPH` 怎麼填

| 情境 | 填法 |
|------|------|
| 單 Agent 完成本次任務 | `EOS-GRAPH`＝**N/A**（理由：單 Agent） |
| ≥2 Agent 並行 | 列節點（誰改哪目錄）、匯合點（Review 前）、是否發生檔案衝突 |

範例（單 Agent）：

```text
EOS-GRAPH: N/A — 單 Agent；拓樸見 docs/graph-routing.md 預設圖
```

---

## 禁止（與公版一致）

```text
❌ 多 Agent 無路由表並行改同一模組
❌ 把 Smoke 塞進 scripts/check.ps1（破壞 Pure）
❌ Review 未 harness 綠就 CodeReview 或寫 Release 證據欄
❌ CodeReviewer 與 Work Agent 並行改同一檔
❌ 對話裡決策了 Case 卻沒更新測試／Smoke 劇情
```

## 延伸閱讀

- Loop／Gate：`EngineeringOS/eos-minimal/PLAYBOOK.md`
- Demo-ready：`docs/testing.md`、EOS `demo-ready-guide.md`
- CodeReviewer prompt：EOS `prompt/code-reviewer-agent-v1.md`
- 架構圖（非 Graph）：`docs/codeGraphic.html`
