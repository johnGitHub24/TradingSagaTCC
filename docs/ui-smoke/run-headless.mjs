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

    // 'failed' 也是終態：不等到逾時，直接印出失敗劇情的 log
    await page.waitForFunction(
        () => {
            const el = document.querySelector('[data-testid="smoke-status"]');
            return el && (el.dataset.value === 'completed' || el.dataset.value === 'failed');
        },
        { timeout: timeoutMs }
    );

    const failedCases = await page.$$eval('#results .card', (cards) => cards
        .filter((c) => c.querySelector('.fail'))
        .map((c) => c.querySelector('strong').textContent.trim() + ' ' + c.querySelector('.muted').textContent.trim()
            + '\n  ' + (c.querySelector('pre')?.textContent || '').replace(/\n/g, '\n  ')));
    if (failedCases.length > 0) {
        throw new Error(failedCases.length + ' 個劇情 FAIL\n' + failedCases.join('\n'));
    }

    const label = await page.$eval('.btn-run', (el) => el.textContent.trim());
    if (!label.includes('SERVICE COMPLETED')) {
        throw new Error('按鈕未顯示 SERVICE COMPLETED：' + label);
    }

    const failures = await page.$$eval('.fail', (nodes) => nodes.length);
    if (failures > 0) {
        throw new Error('畫面有 ' + failures + ' 個 FAIL');
    }

    console.log('ALL_UI_SMOKE_OK');
    const cases = await page.$$eval('#results strong', (nodes) => nodes.map((n) => n.textContent.trim()));
    console.log('劇情: ' + cases.map((c) => c + '=PASS').join('; '));
} catch (err) {
    console.error('UI_SMOKE_FAILED:', err.message || err);
    process.exitCode = 1;
} finally {
    await browser.close();
}
