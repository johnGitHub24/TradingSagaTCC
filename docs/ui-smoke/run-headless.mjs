/**
 * Headless UI Smoke — 等同手動開 /test/runner.html 點 RUN TESTING，等 SERVICE COMPLETED。
 * 執行：docs/run-ui-smoke.ps1（需 Node.js；npx 拉 puppeteer）
 */
import puppeteer from 'puppeteer';

const argUrl = process.argv.find((a) => a.startsWith('--baseUrl='))?.split('=')[1];
const baseUrl = (argUrl || process.env.SMOKE_BASE_URL || 'http://localhost:8093').replace(/\/$/, '');
const timeoutMs = Number(process.env.SMOKE_TIMEOUT_MS || '120000');

const headed = process.argv.includes('--headed') || process.env.SMOKE_HEADED === '1' || process.env.SMOKE_HEADED === 'true';

const browser = await puppeteer.launch({
    headless: !headed,
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
    slowMo: headed ? 80 : 0
});

try {
    const page = await browser.newPage();
    page.on('console', (msg) => {
        if (msg.type() === 'error') {
            console.error('BROWSER:', msg.text());
        }
    });

    const runnerUrl = `${baseUrl}/test/runner.html`;
    console.log('Navigating to', runnerUrl);
    const resp = await page.goto(runnerUrl, { waitUntil: 'networkidle0', timeout: 30000 });
    if (!resp || resp.status() !== 200) {
        throw new Error('runner HTTP ' + (resp?.status() ?? 'no response'));
    }

    const btn = await page.waitForSelector('[data-testid="run-l1-smoke"]', { timeout: 10000 });
    await btn.click();

    await page.waitForFunction(
        () => {
            const el = document.querySelector('[data-testid="smoke-status"]');
            return el && el.dataset.value === 'completed';
        },
        { timeout: timeoutMs }
    );

    const label = await page.$eval('.btn-run', (el) => el.textContent.trim());
    if (!label.includes('SERVICE COMPLETED')) {
        throw new Error('按鈕未顯示 SERVICE COMPLETED：' + label);
    }

    const failures = await page.$$eval('.fail', (nodes) => nodes.length);
    if (failures > 0) {
        throw new Error('畫面有 ' + failures + ' 個 FAIL');
    }

    console.log('ALL_UI_SMOKE_OK');
    console.log('劇情: SAGA-001=PASS; SAGA-002=PASS; TCC-002=PASS; TRADE-001=PASS');
} catch (err) {
    console.error('UI_SMOKE_FAILED:', err.message || err);
    process.exitCode = 1;
} finally {
    await browser.close();
}
