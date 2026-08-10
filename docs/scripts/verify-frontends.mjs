import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';

// Use the documented browser origins: the backend's local CORS allowlist intentionally names
// localhost, while 127.0.0.1 is a different origin and should not silently bypass that policy.
const customerUrl = process.env.CUSTOMER_BASE_URL ?? 'http://localhost:4201';
const operatorUrl = process.env.OPERATOR_BASE_URL ?? 'http://localhost:4200';
const screenshotDir = process.env.BROWSER_SCREENSHOT_DIR ?? '/tmp/registerwerk-headless';
const customerEmail = process.env.CUSTOMER_SMOKE_EMAIL ?? 'maria.braun@nordbank-invest.de';
const customerPassword = process.env.CUSTOMER_SMOKE_PASSWORD ?? 'demo1234!';
const operatorEmail = process.env.DEFAULT_ADMIN_EMAIL ?? 'admin@local';
const operatorPassword = process.env.DEFAULT_ADMIN_PASSWORD ?? 'changeme-please';

await mkdir(screenshotDir, { recursive: true });
const browser = await chromium.launch();

function monitor(page, origin, failures = []) {
  page.on('pageerror', (error) => failures.push(`page: ${error.message}`));
  page.on('console', (message) => {
    if (message.type() === 'error') failures.push(`console: ${message.text()}`);
  });
  page.on('response', (response) => {
    if (response.url().startsWith(origin) && response.status() >= 400) {
      failures.push(`HTTP ${response.status()}: ${response.url()}`);
    }
  });
  return failures;
}

async function settle(page) {
  await page.waitForLoadState('domcontentloaded');
  await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {});
  await page.locator('app-root').waitFor({ state: 'visible' });
  await page.evaluate(() => document.fonts.ready);
}

async function assertVisualFoundation(page, label) {
  const result = await page.evaluate(() => {
    const root = document.querySelector('app-root');
    const icon = document.querySelector('mat-icon');
    const iconStyle = icon ? getComputedStyle(icon) : null;
    const rootBox = root?.getBoundingClientRect();
    return {
      rootWidth: rootBox?.width ?? 0,
      rootHeight: rootBox?.height ?? 0,
      horizontalOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      fontFamily: getComputedStyle(document.body).fontFamily,
      iconFontFamily: iconStyle?.fontFamily ?? '',
      iconWidth: icon?.getBoundingClientRect().width ?? 0,
      iconHeight: icon?.getBoundingClientRect().height ?? 0,
      stylesheets: document.styleSheets.length,
    };
  });
  assert.ok(result.rootWidth > 300 && result.rootHeight > 200, `${label}: application root must be visible`);
  assert.ok(result.stylesheets > 0, `${label}: stylesheets must be loaded`);
  assert.match(result.fontFamily, /Manrope/i, `${label}: Manrope must be active`);
  assert.match(result.iconFontFamily, /Material Icons/i, `${label}: Material Icons font must be active`);
  assert.ok(result.iconWidth > 0 && result.iconHeight > 0, `${label}: icons must occupy visible space`);
  assert.ok(result.horizontalOverflow <= 1, `${label}: page must not overflow the desktop viewport`);
}

async function visit(page, baseUrl, path, expectedText) {
  await page.goto(new URL(path, baseUrl).href, { waitUntil: 'domcontentloaded' });
  await settle(page);
  assert.ok(!new URL(page.url()).pathname.startsWith('/login'), `${path}: session must remain authenticated`);
  await page.getByText(expectedText, { exact: false }).first().waitFor({ state: 'visible' });
}

async function waitForDashboard(page, label, failures) {
  try {
    await page.waitForFunction(() => location.pathname === '/dashboard', undefined, { timeout: 30_000 });
  } catch (error) {
    const visibleText = (await page.locator('body').innerText()).replaceAll(/\s+/g, ' ').slice(0, 500);
    throw new Error(
      `${label} login did not reach the dashboard (URL: ${page.url()}). `
      + `Page: ${visibleText}. Runtime: ${failures.join('; ')}`,
      { cause: error },
    );
  }
}

async function submitLogin(page, button, loginUrl, label) {
  const responsePromise = page.waitForResponse(
    (response) => response.url().endsWith(loginUrl) && response.request().method() === 'POST',
  );
  await button.click();
  const response = await responsePromise;
  if (!response.ok()) {
    const submitted = response.request().postDataJSON();
    const body = await response.text();
    throw new Error(
      `${label} login returned HTTP ${response.status()} for ${submitted?.email ?? '<missing email>'} `
      + `(password length ${submitted?.password?.length ?? 0}): ${body}`,
    );
  }
}

async function verifyCustomer() {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const failures = [];
  try {
    await page.goto(new URL('/login', customerUrl).href, { waitUntil: 'domcontentloaded' });
    await page.locator('#email').fill(customerEmail);
    await page.locator('#password').fill(customerPassword);
    await submitLogin(page, page.locator('button[type="submit"]'), '/api/v1/public/auth/login', 'Customer');
    await waitForDashboard(page, 'Customer', failures);
    await settle(page);
    monitor(page, new URL(customerUrl).origin, failures);
    await assertVisualFoundation(page, 'customer dashboard');
    await page.screenshot({ path: `${screenshotDir}/customer-dashboard.png`, fullPage: true });

    for (const [path, text] of [
      ['/positions', 'Positions'],
      ['/lending', 'Securities-backed Lending'],
      ['/investments', 'Investments'],
      ['/trading', 'Trading'],
    ]) {
      await visit(page, customerUrl, path, text);
      await assertVisualFoundation(page, `customer ${path}`);
    }

    await visit(page, customerUrl, '/repo-desk', 'Repo Desk');
    await page.locator('[aria-label="Repo Desk summary"]').waitFor({ state: 'visible' });
    assert.equal(await page.locator('[aria-label="Repo Desk summary"] > div').count(), 4);
    assert.ok(await page.locator('.rfq-panel').count() >= 2, 'Repo Desk must render the visible seeded RFQs');
    assert.ok(await page.getByText('New RFQ', { exact: true }).isVisible(), 'Repo Desk must expose RFQ creation');
    await assertVisualFoundation(page, 'customer Repo Desk');
    await page.screenshot({ path: `${screenshotDir}/customer-repo-desk.png`, fullPage: true });

    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(250);
    const mobileOverflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    assert.ok(mobileOverflow <= 1, 'Repo Desk must not overflow a mobile viewport');
    await page.screenshot({ path: `${screenshotDir}/customer-repo-desk-mobile.png`, fullPage: true });

    assert.deepEqual(failures, [], `Customer runtime failures:\n${failures.join('\n')}`);
  } catch (error) {
    await page.screenshot({ path: `${screenshotDir}/customer-failure.png`, fullPage: true });
    throw error;
  } finally {
    await context.close();
  }
}

async function verifyOperator() {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const failures = [];
  try {
    await page.goto(new URL('/login', operatorUrl).href, { waitUntil: 'domcontentloaded' });
    await page.locator('input[formcontrolname="email"]').fill(operatorEmail);
    await page.locator('input[formcontrolname="password"]').fill(operatorPassword);
    await submitLogin(page, page.locator('button[type="submit"]'), '/api/v1/public/auth/login', 'Operator');
    await waitForDashboard(page, 'Operator', failures);
    await settle(page);
    monitor(page, new URL(operatorUrl).origin, failures);
    await assertVisualFoundation(page, 'operator dashboard');
    await page.screenshot({ path: `${screenshotDir}/operator-dashboard.png`, fullPage: true });

    for (const [path, text] of [
      ['/customers', 'Customers'],
      ['/assets', 'Assets'],
      ['/organizations', 'Organizations'],
    ]) {
      await visit(page, operatorUrl, path, text);
      await assertVisualFoundation(page, `operator ${path}`);
    }

    await visit(page, operatorUrl, '/registry', 'Entities and capital relationships');
    await page.locator('.graph-surface').waitFor({ state: 'visible' });
    assert.ok(await page.locator('.graph-node').count() >= 2, 'Relationship graph must render company nodes');
    assert.ok(await page.locator('.graph-lines path').count() >= 1, 'Relationship graph must render connecting paths');
    await assertVisualFoundation(page, 'operator relationship graph');
    await page.screenshot({ path: `${screenshotDir}/operator-relationship-graph.png`, fullPage: true });

    assert.deepEqual(failures, [], `Operator runtime failures:\n${failures.join('\n')}`);
  } catch (error) {
    await page.screenshot({ path: `${screenshotDir}/operator-failure.png`, fullPage: true });
    throw error;
  } finally {
    await context.close();
  }
}

try {
  await verifyCustomer();
  await verifyOperator();
  console.log(`Customer and operator headless Chromium checks passed. Screenshots: ${screenshotDir}`);
} finally {
  await browser.close();
}
