/**
 * DASH-001 單元層（Node）：不需 bootRun、不需瀏覽器，直接跑 static/test/dashboard.spec.js。
 * 【技巧】以 data URL 載入 static 下的 ES module，避開「.js 在 Node 預設當 CommonJS」的問題；
 * 規格檔不 import 任何東西，model 由這裡傳入。
 * 執行：node docs/ui-smoke/run-dashboard-unit.mjs（run-ui-smoke.ps1 會先跑這支）
 */
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const staticDir = path.resolve(here, '..', '..', 'src', 'main', 'resources', 'static');

const loadModule = async (file) => {
    const src = await readFile(path.join(staticDir, file));
    return import('data:text/javascript;base64,' + src.toString('base64'));
};

const model = await loadModule('dashboard.js');
const { runDashboardSpecs } = await loadModule('test/dashboard.spec.js');
const { passed, failed, results } = runDashboardSpecs(model);

for (const r of results) {
    console.log(`${r.pass ? 'PASS' : 'FAIL'}  DASH-001 ${r.name}${r.pass ? '' : '\n      ' + r.error}`);
}
console.log(`DASH-001 unit: ${passed} passed, ${failed} failed`);
if (failed > 0) {
    console.error('DASH_UNIT_FAILED');
    process.exitCode = 1;
} else {
    console.log('ALL_DASH_UNIT_OK');
}
