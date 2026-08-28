# scripts/ — Pure Surface

> Norm: `eos-minimal/knowledge/pure-project-scripts.md`  
> Apply: `eos-minimal/hooks/apply-workspace.ps1`

| File | Role |
|------|------|
| `portable-env.*` / `env.*` | OS `JAVA_HOME` |
| `check.*` | `gradlew check` |
| `intellij-run.properties` | `BOOT_GRADLE_TASK` (per project) |
| `fix-intellij-run.ps1` | Local IDE helper |

Demo／docs 工具**不要**放這裡。Runtime Smoke（L0／L1）腳本放 `docs/run-*-smoke.ps1`（見 `docs/testing.md`）。
