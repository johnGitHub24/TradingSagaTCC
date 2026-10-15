/**
 * 【職責】依 flows.mjs 劇本自動操作真瀏覽器、截圖並加上編號紅框，產出導覽圖與兩份操作手冊（HTML＋Markdown）。
 * 【技巧】sagaId 從 POST /api/v1/trades 的 202 回應取得（不猜列表順序）；終態用 waitForFunction 等「Dashboard 換成這筆＋
 * 頂部提示變成功／補償」（不寫死 sleep）；紅框是暫時注入頁面的絕對定位 div，截完就移除，不改前端原始碼；
 * 任何一步失敗就存 _fail 截圖並結束，不輸出半套手冊。
 * 【概念】手冊＝可重跑的產物：畫面改版後重跑一次就更新，selector 對不上會直接失敗，手冊不會悄悄過期（真開埠 ≠ check）。
 * 產出的 HTML／shots 由 Gradle processResources 複製到 static/manual，前台導航列「操作手冊」直接開。
 * 執行：docs/run-manual.ps1（puppeteer 共用 docs/ui-smoke/node_modules）
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { meta, chapters, steps, outcomes } from './flows.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(__dirname, '../..');
const requireFromSmoke = createRequire(path.resolve(__dirname, '../ui-smoke/package.json'));
const pptrModule = requireFromSmoke('puppeteer');
const puppeteer = pptrModule.default ?? pptrModule;

const arg = (name) => process.argv.find((a) => a.startsWith(`--${name}=`))?.split('=').slice(1).join('=');
const baseUrl = (arg('baseUrl') || process.env.MANUAL_BASE_URL || 'http://localhost:8093').replace(/\/$/, '');
const headed = process.argv.includes('--headed');
const outDir = __dirname;
const shotsDir = path.join(outDir, 'shots');
const htmlFile = path.join(outDir, '操作手冊.html');
const mdFile = path.join(outDir, '操作手冊.md');

const codeTrace = JSON.parse(fs.readFileSync(path.join(projectRoot, 'src/main/resources/static/code-trace.json'), 'utf8'));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const resolve = (v, s) => (typeof v === 'function' ? v(s) : v);
const nextFrames = (page) => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));

// ---------- 瀏覽器操作 ----------

/**
 * 【職責】劇本可用的共用動作（開頁、還原帳戶、按劇情鈕、定格、回看訂單、hover）。
 * 【技巧】所有動作都等「看得到的結果」才返回，劇本不用自己處理時序。
 */
function createUi(page) {
  const text = (sel) => page.$eval(sel, (e) => e.textContent.trim());
  const laneStates = () => page.evaluate(() => Object.fromEntries(['saga', 'order', 'tcc']
    .map((k) => [k, document.querySelector(`[data-testid="lane-${k}"]`)?.dataset.state || '—'])));

  const ui = {
    page,
    /** 【技巧】關掉自動重播（畫面才停在終態）；頁面 CSS 是 smooth scroll，改成立即捲動，截圖才不會截到捲一半。 */
    async open() {
      await page.goto(`${baseUrl}/?autoReplay=0`, { waitUntil: 'networkidle2', timeout: 30000 });
      await page.addStyleTag({ content: 'html{scroll-behavior:auto !important}' });
      await page.waitForSelector('[data-testid="btn-saga-001"]:not([disabled])', { visible: true });
      await page.waitForNetworkIdle({ idleTime: 300, timeout: 10000 }).catch(() => {});
    },
    async resetAccount() {
      const [resp] = await Promise.all([
        page.waitForResponse((r) => r.url().endsWith('/api/v1/accounts/ACC-001/reset') && r.request().method() === 'POST', { timeout: 15000 }),
        page.click('[data-testid="btn-reset"]')
      ]);
      if (!resp.ok()) throw new Error(`還原帳戶 HTTP ${resp.status()}`);
      await page.waitForFunction(() => document.querySelector('[data-testid="toast"]')?.textContent.includes('已還原'), { timeout: 10000 });
      return text('[data-testid="acc-available"]');
    },
    /**
     * 按劇情鈕 → 拿 sagaId → 等 Dashboard 換成這筆且頂部提示是成功／補償（輪詢逾時就失敗）。
     * @return {{sagaId,saga,order,tcc,availBefore,availAfter}}
     */
    async placeScenario(testid) {
      const availBefore = await text('[data-testid="acc-available"]');
      const [resp] = await Promise.all([
        page.waitForResponse((r) => r.url().endsWith('/api/v1/trades') && r.request().method() === 'POST', { timeout: 15000 }),
        page.click(`[data-testid="${testid}"]`)
      ]);
      if (resp.status() !== 202) throw new Error(`${testid} 預期 202，實際 HTTP ${resp.status()}`);
      const { sagaId } = await resp.json();
      await page.waitForFunction((id) => {
        const dash = document.querySelector('[data-testid="sm-dashboard"]');
        const toast = document.querySelector('[data-testid="toast"]')?.textContent.trim() || '';
        if (toast.startsWith('輪詢逾時')) return true;
        return dash?.dataset.sagaId === id && (toast.startsWith('成功') || toast.startsWith('補償完成'))
          && !document.querySelector('[data-testid="btn-saga-001"]').disabled;
      }, { timeout: 30000 }, sagaId);
      const toast = await text('[data-testid="toast"]');
      if (toast.startsWith('輪詢逾時')) throw new Error(`${testid} 輪詢逾時：${toast}`);
      await page.waitForNetworkIdle({ idleTime: 300, timeout: 10000 }).catch(() => {});
      return { sagaId, ...(await laneStates()), availBefore, availAfter: await text('[data-testid="acc-available"]') };
    },
    /** 點時間軸第 index 列（0 起算）→ 等定格橫幅出現。 */
    async freezeAt(index) {
      const rows = await page.$$('[data-testid="timeline-row"]');
      if (rows.length <= index) throw new Error(`時間軸只有 ${rows.length} 列，無法點第 ${index + 1} 列`);
      await rows[index].click();
      await page.waitForSelector('[data-testid="replay-banner"]', { visible: true, timeout: 5000 });
      const label = await rows[index].evaluate((tr) => tr.children[3]?.childNodes[0]?.textContent.trim());
      return { no: index + 1, total: rows.length, label };
    },
    /** 【技巧】inspectOrder 內部用 smooth scrollIntoView（不受 CSS 覆寫影響），等它捲完再截圖。 */
    async inspectOrder(sagaId) {
      const sel = `tr[data-testid="order-row"][data-saga-id="${sagaId}"]`;
      await page.waitForSelector(sel, { visible: true });
      const [resp] = await Promise.all([
        page.waitForResponse((r) => r.url().endsWith(`/api/v1/sagas/${sagaId}`), { timeout: 15000 }),
        page.click(sel)
      ]);
      if (!resp.ok()) throw new Error(`回看訂單 HTTP ${resp.status()}`);
      await page.waitForFunction((id) => document.querySelector('[data-testid="sm-dashboard"]')?.dataset.sagaId === id
        && document.querySelector('tr[data-testid="order-row"].table-active'), { timeout: 10000 }, sagaId);
      await page.waitForNetworkIdle({ idleTime: 300, timeout: 10000 }).catch(() => {});
      await sleep(800);
    },
    /** 【技巧】捲動後等兩個 animation frame 再 hover：頁面 scroll 事件會收起 tooltip，晚到的事件不能蓋掉 hover。 */
    async hover(sel, traceKey) {
      const el = await page.waitForSelector(sel, { visible: true });
      await el.evaluate((e) => e.scrollIntoView({ block: 'center' }));
      await nextFrames(page);
      await el.hover();
      await page.waitForSelector(`[data-testid="trace-tip"][data-trace-key="${traceKey}"]`, { visible: true, timeout: 3000 });
      await sleep(250);
    }
  };
  return ui;
}

/**
 * 【職責】在頁面上畫編號紅框（截完即移除）。
 * 【技巧】座標＝getBoundingClientRect＋scroll，用絕對定位疊在最上層、pointer-events:none 不影響 hover。
 * @return missing＝找不到或不可見；outside＝viewport 截圖時超出可視範圍（會被裁掉）
 */
function drawCallouts(items, viewportOnly) {
  document.getElementById('__manual_anno')?.remove();
  const layer = document.createElement('div');
  layer.id = '__manual_anno';
  layer.style.cssText = 'position:absolute;left:0;top:0;width:0;height:0;z-index:2147483647;pointer-events:none';
  document.body.appendChild(layer);
  const missing = [];
  const outside = [];
  items.forEach((sel, i) => {
    const el = document.querySelector(sel);
    const r = el?.getBoundingClientRect();
    if (!r || r.width === 0 || r.height === 0) {
      missing.push(sel);
      return;
    }
    if (viewportOnly && (r.top < 0 || r.bottom > window.innerHeight)) outside.push(sel);
    const x = r.left + window.scrollX;
    const y = r.top + window.scrollY;
    const box = document.createElement('div');
    box.style.cssText = `position:absolute;left:${x - 4}px;top:${y - 4}px;width:${r.width + 8}px;height:${r.height + 8}px;`
      + 'border:3px solid #ff3b30;border-radius:8px;box-shadow:0 0 0 2px rgba(255,255,255,.65),0 0 14px rgba(255,59,48,.55)';
    const badge = document.createElement('div');
    badge.textContent = String(i + 1);
    badge.style.cssText = `position:absolute;left:${Math.max(2, x - 16)}px;top:${Math.max(2, y - 16)}px;width:28px;height:28px;`
      + 'border-radius:50%;background:#ff3b30;color:#fff;font:700 15px/28px system-ui,sans-serif;text-align:center;'
      + 'box-shadow:0 1px 4px rgba(0,0,0,.5);border:2px solid #fff';
    layer.append(box, badge);
  });
  return { missing, outside };
}

async function capture(page, step, file) {
  if (!step.keepScroll) {
    await page.mouse.move(2, 2);
    await page.waitForSelector('[data-testid="trace-tip"]', { hidden: true, timeout: 2000 });
  }
  if (step.scrollTo) {
    await page.$eval(step.scrollTo, (e) => {
      e.scrollIntoView({ block: 'start' });
      window.scrollBy(0, -70);
    });
  } else if (!step.keepScroll) {
    await page.evaluate(() => window.scrollTo(0, 0));
  }
  await sleep(150);
  const { missing, outside } = await page.evaluate(drawCallouts, step.callouts.map((c) => c.sel), step.shot === 'viewport');
  if (missing.length) throw new Error(`標註元素找不到（畫面改版？）：${missing.join(' | ')}`);
  if (outside.length) throw new Error(`標註元素在截圖範圍外（改 shot: 'full'／'element' 或 scrollTo）：${outside.join(' | ')}`);
  if (step.shot === 'element') {
    // 【技巧】先捲回頂端（sticky 導航列留在文件最上方，不會疊進區塊），再用文件座標 clip＋captureBeyondViewport 截超出視窗的整塊
    await page.evaluate(() => window.scrollTo(0, 0));
    const box = await page.$eval(step.el, (e) => {
      const r = e.getBoundingClientRect();
      return { y: r.top + window.scrollY, h: r.height };
    });
    const pad = 20;
    await page.screenshot({ path: file, captureBeyondViewport: true,
      clip: { x: 0, y: Math.max(0, box.y - pad), width: 1440, height: box.h + pad * 2 } });
  } else {
    await page.screenshot({ path: file, fullPage: step.shot === 'full' });
  }
  await page.evaluate(() => document.getElementById('__manual_anno')?.remove());
}

// ---------- 呼叫鏈（重用 code-trace.json 的中文；本專案每個 key 單一路徑） ----------

/** 與 static/dashboard.js entryKey 同規則：entries 字典的 key。 */
function entryKey(e) {
  const detail = { HTTP: `${e.method} ${e.path}`, KAFKA: e.topic, SCHEDULED: e.property ?? e.every }[e.kind];
  return detail ? `${e.kind} ${detail}` : '';
}

function traceInfo(key) {
  const t = codeTrace.traces[key];
  if (!t) throw new Error(`traceKey ${key} 不在 code-trace.json`);
  const k = t.entry ? entryKey(t.entry) : '';
  return {
    zh: t.zh,
    entry: k || '（無程式入口）',
    entryZh: codeTrace.entries[k] || '',
    hops: (t.chain || []).map((ref) => {
      const [fqcn, m] = ref.split('#');
      return { short: `${fqcn.slice(fqcn.lastIndexOf('.') + 1)}.${m}`, zh: codeTrace.methods[ref] || '' };
    })
  };
}

// ---------- 輸出：HTML ----------

const esc = (s) => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const inline = (s) => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>');
const CIRCLED = '①②③④⑤⑥⑦⑧⑨⑩⑪⑫';

function stepNo(st) {
  const ci = chapters.findIndex((c) => c.id === st.chapter);
  const si = steps.filter((x) => x.chapter === st.chapter).indexOf(st);
  return `${ci + 1}.${si + 1}`;
}

const stepOf = (id) => steps.find((x) => x.id === id);

function overviewHtml(results, s) {
  const rows = chapters.map((c, ci) => {
    const nodes = steps.filter((x) => x.chapter === c.id).map((x) => {
      const r = results[x.id];
      return `<a class="ov-node" href="#step-${x.id}"><img src="shots/${r.file}" alt=""><span><b>${stepNo(x)}</b> ${esc(x.title)}</span></a>`;
    }).join('<span class="ov-arrow">→</span>');
    return `<div class="ov-chapter"><div class="ov-ch-title">${ci + 1}. ${esc(c.title)}</div><div class="ov-steps">${nodes}</div></div>`;
  }).join('<div class="ov-down">↓</div>');
  const ends = outcomes.map((o) => {
    const r = s[o.key] || {};
    return `<a class="oc oc-${o.tone}" href="#step-${o.step}"><b>${esc(o.label)}</b><span>Saga ${esc(r.saga)}</span><span>訂單 ${esc(r.order)}</span><span>預留票 ${esc(r.tcc)}</span></a>`;
  }).join('');
  const state = `<div class="ov-state"><div class="ov-ch-title">一筆交易的三種結局（本次實測終態；點卡片跳到對應步驟）</div>
    <div class="oc-grid"><span class="oc-start">POST /api/v1/trades<br>Saga STARTED</span><span class="ov-arrow">→</span><div class="oc-ends">${ends}</div></div></div>`;
  return `<section id="overview"><h2>導覽圖</h2><p class="muted">依畫面使用順序排列；點任一縮圖跳到該步驟說明。</p>${rows}${state}</section>`;
}

function traceHtml(key) {
  const t = traceInfo(key);
  return `<details class="trace"><summary>背後程式（hover 呼叫鏈 <code>${esc(key)}</code>）</summary><p>${esc(t.zh)}</p>
  <div class="tr-path"><div class="tr-entry"><span class="tag">入口</span><code>${esc(t.entry)}</code>${t.entryZh ? `<div class="zh">${esc(t.entryZh)}</div>` : ''}</div>
  <ol>${t.hops.map((h) => `<li><code>${esc(h.short)}</code>${h.zh ? `<div class="zh">${esc(h.zh)}</div>` : ''}</li>`).join('')}</ol></div></details>`;
}

function stepHtml(st, r, s) {
  const callouts = st.callouts.map((c, i) => `<li><span class="num">${i + 1}</span>${inline(resolve(c.text, s))}</li>`).join('');
  const ops = resolve(st.ops, s).map((o) => `<li>${inline(o)}</li>`).join('');
  const cases = st.cases.length ? st.cases.map((c) => `<span class="case">${esc(c)}</span>`).join(' ') : '<span class="muted">—</span>';
  return `<article class="step" id="step-${st.id}">
  <h3><span class="no">${stepNo(st)}</span>${esc(st.title)}</h3>
  <p class="purpose">${inline(resolve(st.purpose, s))}</p>
  <a class="shot" href="shots/${r.file}" target="_blank"><img src="shots/${r.file}" alt="${esc(st.title)}" loading="lazy"></a>
  <div class="cols">
    <div><h4>畫面標註</h4><ol class="callouts">${callouts}</ol></div>
    <div><h4>操作步驟</h4><ol>${ops}</ol><h4>預期結果</h4><p>${inline(resolve(st.expect, s))}</p>
      <h4>API／驗收案例</h4><p><code>${esc(st.api)}</code></p><p>${cases}</p></div>
  </div>
  ${st.traceKey ? traceHtml(st.traceKey) : ''}
</article>`;
}

function renderHtml(results, s, info) {
  const toc = chapters.map((c, ci) => `<li><a href="#ch-${c.id}">${ci + 1}. ${esc(c.title)}</a><ol>${steps.filter((x) => x.chapter === c.id)
    .map((x) => `<li><a href="#step-${x.id}">${stepNo(x)} ${esc(x.title)}</a></li>`).join('')}</ol></li>`).join('');
  const body = chapters.map((c, ci) => `<section class="chapter" id="ch-${c.id}"><h2>${ci + 1}. ${esc(c.title)}</h2>
    ${steps.filter((x) => x.chapter === c.id).map((x) => stepHtml(x, results[x.id], s)).join('\n')}</section>`).join('\n');
  const prereq = meta.prereq.map((p) => `<tr><td>${esc(p.item)}</td><td><code>${esc(p.value)}</code></td><td>${inline(p.note)}</td></tr>`).join('');
  return `<!doctype html>
<html lang="zh-Hant"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(meta.title)}</title>
<style>
:root{--bg:#0f1720;--card:#16212d;--line:#26384a;--fg:#e6edf3;--muted:#8b9cb3;--acc:#4da3ff;--red:#ff3b30}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.7 "Microsoft JhengHei","Noto Sans TC",system-ui,sans-serif}
.wrap{max-width:1180px;margin:0 auto;padding:24px}a{color:var(--acc)}code{background:#0b1218;border:1px solid var(--line);border-radius:4px;padding:0 5px;font-size:.88em}
header.cover{border-bottom:1px solid var(--line);margin-bottom:16px}header.cover h1{margin:.2em 0 0}.muted{color:var(--muted)}
nav.toc{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:8px 20px;margin:16px 0}nav.toc ol ol{font-size:.92em}
table.acc{border-collapse:collapse;width:100%;margin:8px 0 20px}table.acc td,table.acc th{border:1px solid var(--line);padding:6px 10px;text-align:left}
#overview{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px 20px;margin:20px 0}
.ov-chapter{border:1px dashed var(--line);border-radius:10px;padding:10px 12px}.ov-ch-title{font-weight:700;margin-bottom:8px}
.ov-steps{display:flex;flex-wrap:wrap;align-items:center;gap:6px}.ov-arrow,.ov-down{color:var(--acc);font-weight:700;font-size:20px}.ov-down{text-align:center;line-height:1.6}
.ov-node{display:flex;flex-direction:column;width:170px;background:#0b1218;border:1px solid var(--line);border-radius:8px;overflow:hidden;text-decoration:none;color:var(--fg);font-size:13px}
.ov-node:hover{border-color:var(--acc)}.ov-node img{width:100%;height:96px;object-fit:cover;object-position:top}.ov-node span{padding:4px 8px}
.ov-state{margin-top:14px;border:1px dashed var(--line);border-radius:10px;padding:10px 12px}
.oc-grid{display:flex;align-items:center;gap:14px;flex-wrap:wrap}.oc-start{background:#1e3a8a;border-radius:8px;padding:8px 14px;font-weight:700;text-align:center}
.oc-ends{display:flex;gap:10px;flex-wrap:wrap}.oc{display:flex;flex-direction:column;min-width:190px;padding:8px 14px;border-radius:10px;text-decoration:none;color:#fff;font-size:13px}
.oc b{font-size:15px}.oc-ok{background:#2f855a}.oc-warn{background:#b45309}
.chapter>h2{border-left:5px solid var(--acc);padding-left:10px;margin-top:40px}
.step{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px 20px;margin:18px 0}
.step h3{margin:0 0 4px}.step .no{display:inline-block;background:var(--acc);color:#04111f;border-radius:6px;padding:0 8px;margin-right:8px;font-size:.9em}
.purpose{color:#c9d6e3}.shot img{width:100%;border:1px solid var(--line);border-radius:8px;display:block}
.cols{display:grid;grid-template-columns:1fr 1fr;gap:20px}@media(max-width:860px){.cols{grid-template-columns:1fr}}
h4{margin:12px 0 4px;color:var(--acc)}ol.callouts{list-style:none;padding-left:0}ol.callouts li{margin:6px 0;display:flex;gap:8px}
.num{flex:none;width:24px;height:24px;border-radius:50%;background:var(--red);color:#fff;font-weight:700;font-size:13px;text-align:center;line-height:24px}
.case{display:inline-block;background:#0b1218;border:1px solid var(--line);border-radius:999px;padding:0 10px;font-size:12px;margin:2px 0}
details.trace{margin-top:10px;background:#0b1218;border:1px solid var(--line);border-radius:8px;padding:8px 12px}details.trace summary{cursor:pointer;font-weight:700}
.tr-path{margin:8px 0}.tr-entry .tag{background:var(--acc);color:#04111f;border-radius:4px;padding:0 6px;margin-right:6px;font-size:12px}.zh{color:var(--muted);font-size:13px}
footer{color:var(--muted);font-size:13px;border-top:1px solid var(--line);margin-top:40px;padding-top:12px}
</style></head><body><div class="wrap">
<header class="cover"><h1>${esc(meta.title)}</h1><p class="muted">${esc(meta.subtitle)}</p>
<p class="muted">產生時間 ${esc(info.when)} · 環境 <code>${esc(baseUrl)}</code> · 截圖由 <code>docs/run-manual.ps1</code> 自動操作瀏覽器取得 · <a href="/">回 Dashboard</a></p></header>
<h2>前置條件</h2><table class="acc"><tr><th>項目</th><th>值</th><th>說明</th></tr>${prereq}</table>
${overviewHtml(results, s)}
<nav class="toc"><h2>目錄</h2><ol>${toc}</ol></nav>
${body}
<footer><p>重新產生：先 Gradle <code>bootRun</code>（:8093），再執行 <code>.\\docs\\run-manual.ps1</code>；之後重新 <code>bootRun</code>（或 <code>processResources</code>）前台「操作手冊」就是新版。劇本在 <code>docs/manual/flows.mjs</code>；畫面元素改名時產生會失敗並留下 <code>shots/_fail-*.png</code>。</p>
<p>sagaId、available 等數字是本次執行時抓到的值，每次重跑會不同；要回到種子狀態請按「還原種子 100000」或重啟服務（H2 記憶體庫）。</p></footer>
</div></body></html>`;
}

// ---------- 輸出：Markdown ----------

function renderMd(results, s, info) {
  const L = [];
  L.push(`# ${meta.title}`, '', `> ${meta.subtitle}`, '', `產生時間 ${info.when} · 環境 \`${baseUrl}\` · 由 \`docs/run-manual.ps1\` 自動截圖產生（HTML 版：[操作手冊.html](操作手冊.html)）`, '');
  L.push('## 前置條件', '', '| 項目 | 值 | 說明 |', '|------|----|------|');
  meta.prereq.forEach((p) => L.push(`| ${p.item} | \`${p.value}\` | ${p.note} |`));
  L.push('', '## 導覽圖', '', '![導覽圖](shots/00-overview.png)', '', '```mermaid', 'flowchart LR');
  chapters.forEach((c, ci) => {
    L.push(`  subgraph C${ci + 1}["${ci + 1}. ${c.title}"]`);
    const ids = steps.filter((x) => x.chapter === c.id);
    ids.forEach((x) => L.push(`    ${x.id.replace(/-/g, '_')}["${stepNo(x)} ${x.title}"]`));
    if (ids.length > 1) L.push(`    ${ids.map((x) => x.id.replace(/-/g, '_')).join(' --> ')}`);
    L.push('  end');
  });
  L.push(`  ${chapters.map((_, ci) => `C${ci + 1}`).join(' --> ')}`, '```', '');
  L.push('**三種結局（本次實測終態）**', '', '| 劇情 | Saga | 訂單 | 預留票 | 步驟 |', '|------|------|------|--------|------|');
  outcomes.forEach((o) => {
    const r = s[o.key] || {};
    const st = stepOf(o.step);
    L.push(`| ${o.label} | \`${r.saga}\` | \`${r.order}\` | \`${r.tcc}\` | [${stepNo(st)}](#${anchor(`${stepNo(st)} ${st.title}`)}) |`);
  });
  L.push('', '## 目錄', '');
  chapters.forEach((c, ci) => {
    L.push(`${ci + 1}. ${c.title}`);
    steps.filter((x) => x.chapter === c.id).forEach((x) => L.push(`   - [${stepNo(x)} ${x.title}](#${anchor(`${stepNo(x)} ${x.title}`)})`));
  });
  chapters.forEach((c, ci) => {
    L.push('', `## ${ci + 1}. ${c.title}`);
    steps.filter((x) => x.chapter === c.id).forEach((st) => {
      L.push('', `### ${stepNo(st)} ${st.title}`, '', resolve(st.purpose, s), '', `![${st.title}](shots/${results[st.id].file})`, '', '**畫面標註**', '');
      st.callouts.forEach((cl, i) => L.push(`${CIRCLED[i]} ${resolve(cl.text, s)}  `));
      L.push('', '**操作步驟**', '');
      resolve(st.ops, s).forEach((o, i) => L.push(`${i + 1}. ${o}`));
      L.push('', `**預期結果**：${resolve(st.expect, s)}`, '', `**API**：\`${st.api}\`　**驗收案例**：${st.cases.length ? st.cases.map((x) => `\`${x}\``).join(' ') : '—'}`);
      if (st.traceKey) {
        const t = traceInfo(st.traceKey);
        L.push('', '<details><summary>背後程式（hover 呼叫鏈 <code>' + st.traceKey + '</code>）</summary>', '', t.zh, '');
        L.push(`- **入口** \`${t.entry}\`${t.entryZh ? ` — ${t.entryZh}` : ''}`);
        t.hops.forEach((h, j) => L.push(`  ${j + 1}. \`${h.short}\`${h.zh ? ` — ${h.zh}` : ''}`));
        L.push('', '</details>');
      }
    });
  });
  L.push('', '---', '', '重新產生：先 Gradle `bootRun`（:8093），再 `.\\docs\\run-manual.ps1`。劇本：`docs/manual/flows.mjs`。sagaId、available 為執行當下的值。', '');
  return L.join('\n');
}

/** GitHub 標題錨點規則：去標點、每個空白各換成一個 -（不合併）。 */
function anchor(text) {
  return text.trim().toLowerCase().replace(/[^\p{L}\p{N}\s-]/gu, '').replace(/\s/g, '-');
}

// ---------- 主流程 ----------

const browser = await puppeteer.launch({
  headless: !headed,
  args: ['--no-sandbox', '--disable-setuid-sandbox'],
  defaultViewport: { width: 1440, height: 900 },
  slowMo: headed ? 40 : 0
});
const state = {};
const results = {};
let current = null;
try {
  fs.mkdirSync(shotsDir, { recursive: true });
  for (const f of fs.readdirSync(shotsDir)) if (f.endsWith('.png')) fs.unlinkSync(path.join(shotsDir, f));
  // 無痕 context：與 FinTechDemo 金樣一致，避免瀏覽器提示泡泡吃掉滑鼠事件
  const page = await (await browser.createBrowserContext()).newPage();
  page.on('console', (m) => { if (m.type() === 'error') console.error('  BROWSER:', m.text()); });
  const ui = createUi(page);
  let n = 1;
  for (const st of steps) {
    current = { st, page };
    const file = `${String(n).padStart(2, '0')}-${st.id}.png`;
    await st.run({ ui, state });
    await capture(page, st, path.join(shotsDir, file));
    results[st.id] = { file };
    console.log(`  ok  ${stepNo(st)} ${st.title} → shots/${file}`);
    n += 1;
  }
  current = null;

  const info = { when: new Date().toLocaleString('zh-TW', { hour12: false }) };
  fs.writeFileSync(htmlFile, renderHtml(results, state, info), 'utf8');
  const ov = await page.browserContext().newPage();
  await ov.goto(pathToFileURL(htmlFile).href, { waitUntil: 'load' });
  await (await ov.$('#overview')).screenshot({ path: path.join(shotsDir, '00-overview.png') });
  fs.writeFileSync(mdFile, renderMd(results, state, info), 'utf8');
  console.log(`MANUAL_OK ${steps.length} steps → ${path.relative(projectRoot, htmlFile)} ＋ ${path.relative(projectRoot, mdFile)}`);
} catch (err) {
  console.error('MANUAL_FAILED:', current ? `${current.st.id}: ` : '', err.message || err);
  if (current) await current.page.screenshot({ path: path.join(shotsDir, `_fail-${current.st.id}.png`), fullPage: true }).catch(() => {});
  process.exitCode = 1;
} finally {
  await browser.close();
}
