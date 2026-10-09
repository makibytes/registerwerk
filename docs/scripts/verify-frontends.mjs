import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';

// Use the documented browser origins: the backend's local CORS allowlist intentionally names
// localhost, while 127.0.0.1 is a different origin and should not silently bypass that policy.
const customerUrl = process.env.CUSTOMER_BASE_URL ?? 'http://localhost:44201';
const operatorUrl = process.env.OPERATOR_BASE_URL ?? 'http://localhost:44200';
const screenshotDir = process.env.BROWSER_SCREENSHOT_DIR ?? '/tmp/registerwerk-headless';
const customerEmail = process.env.CUSTOMER_SMOKE_EMAIL ?? 'maria.braun@nordbank-invest.de';
const customerPassword = process.env.CUSTOMER_SMOKE_PASSWORD ?? 'demo1234!';
const operatorEmail = process.env.DEFAULT_ADMIN_EMAIL ?? 'admin@local';
const operatorPassword = process.env.DEFAULT_ADMIN_PASSWORD ?? 'changeme-please';

// The demo seeds its operator users (not the bootstrap admin) with this fixed authenticator secret
// (DemoDataSeeder.DEMO_TOTP_SECRET), so support-session step-up can be driven without a phone.
const supportEmail = process.env.SUPPORT_SMOKE_EMAIL ?? 'dual-control.admin@registerwerk-demo.internal';
const supportPassword = process.env.SUPPORT_SMOKE_PASSWORD ?? 'demo1234!';
const supportTotpSecret = process.env.SUPPORT_SMOKE_TOTP_SECRET ?? 'JBSWY3DPEHPK3PXP';
const only = process.env.VERIFY_ONLY; // 'customer' | 'operator' | 'impersonation' — default: everything

function totp(secret, now = Date.now()) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const bits = [...secret.replaceAll('=', '').toUpperCase()].map((c) => alphabet.indexOf(c).toString(2).padStart(5, '0')).join('');
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => Number.parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(now / 30_000)));
  const h = createHmac('sha1', key).update(counter).digest();
  const o = h[19] & 15;
  return String((((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3]) % 1_000_000).padStart(6, '0');
}

await mkdir(screenshotDir, { recursive: true });
const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});

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

    // PARK-T2-20 interim: the lender side must not be advertised as KYC-free while it is under legal review.
    await visit(page, customerUrl, '/lending/supply', 'Lender-side eligibility is under legal review');
    for (const path of ['/lending', '/lending/supply']) {
      await visit(page, customerUrl, path, path === '/lending' ? 'Securities-backed Lending' : 'Supply & Earn');
      assert.ok(!/no KYC/i.test(await page.locator('body').innerText()), `${path} must not advertise "no KYC"`);
    }

    await visit(page, customerUrl, '/repo-desk', 'Repo Desk');
    await page.locator('[aria-label="Repo Desk summary"]').waitFor({ state: 'visible' });
    // Cash principal is shown per role and currency over open trades only (P8A-09), so the tile count varies.
    assert.ok(await page.locator('[aria-label="Repo Desk summary"] > div').count() >= 3, 'Repo Desk summary must render its KPI tiles');
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

/**
 * The customer portal's support-session journey, against the real backend: sign in as an operator,
 * pick a company, give a reason and an authenticator code, land on the customer's dashboard under the
 * read-only banner, then leave again. Also proves the two ways this has failed before are visible:
 * a wrong code is explained inline (not swallowed), and the form is on screen right after choosing.
 */
async function verifyImpersonation() {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const failures = [];
  try {
    await page.goto(new URL('/login', customerUrl).href, { waitUntil: 'domcontentloaded' });
    await page.locator('#email').fill(supportEmail);
    await page.locator('#password').fill(supportPassword);
    await submitLogin(page, page.locator('button[type="submit"]'), '/api/v1/public/auth/login', 'Support');
    await page.waitForURL('**/select-company', { timeout: 30_000 });
    await settle(page);
    monitor(page, new URL(customerUrl).origin, failures);
    // Expected, asserted-on refusals below are not runtime failures.
    const expectedRefusals = [];
    page.on('response', (response) => {
      if (response.url().endsWith('/api/v1/auth/step-up') && response.status() === 403) {
        expectedRefusals.push(`HTTP 403: ${response.url()}`);
      }
    });

    const rows = page.locator('button.entity-row');
    await rows.first().waitFor({ state: 'visible' });
    assert.ok(await rows.count() >= 2, 'the company picker must list the seeded companies');
    await page.screenshot({ path: `${screenshotDir}/support-picker.png`, fullPage: true });

    // Choosing a company must bring the session form into view immediately.
    const companyName = (await rows.first().locator('.entity-name').innerText()).trim();
    await rows.first().click();
    const form = page.locator('[aria-label="Start support session"]');
    await form.waitFor({ state: 'visible' });
    const box = await form.boundingBox();
    assert.ok(box && box.y >= 0 && box.y + box.height <= 800, 'the session form must be fully inside the viewport');
    assert.ok((await form.innerText()).includes(companyName), 'the form must name the chosen company');

    // A wrong authenticator code is explained on the form, not as a bare "Access denied".
    await page.getByLabel('Reason', { exact: true }).fill('Smoke test: verifying the support-session journey');
    await page.getByLabel('Authenticator code').fill('000000');
    await page.getByRole('button', { name: 'Start session' }).click();
    const alert = form.locator('[role="alert"]');
    await alert.waitFor({ state: 'visible' });
    assert.match(await alert.innerText(), /TOTP code|code/i, 'a wrong code must be explained on the form');
    assert.ok(!/^Access denied/i.test((await alert.innerText()).trim()), 'the message must be specific');
    await page.screenshot({ path: `${screenshotDir}/support-wrong-code.png`, fullPage: true });

    // The real code starts the session. A code is single-use per 30 s step, so use the next step's.
    await page.getByLabel('Authenticator code').fill(totp(supportTotpSecret, Date.now() + 30_000));
    await page.getByRole('button', { name: 'Start session' }).click();
    await page.waitForURL('**/dashboard', { timeout: 30_000 });
    await settle(page);
    await page.getByText('read-only support session', { exact: false }).first().waitFor({ state: 'visible' });
    assert.ok((await page.locator('body').innerText()).includes(companyName), 'the banner must name the impersonated company');
    await assertVisualFoundation(page, 'impersonated dashboard');
    await page.screenshot({ path: `${screenshotDir}/support-dashboard.png`, fullPage: true });

    // Leaving restores the operator's own session (the picker), and the picker works a second time.
    await page.getByRole('button', { name: /Exit impersonation/i }).click();
    await page.waitForURL('**/select-company', { timeout: 30_000 });
    await page.locator('button.entity-row').first().waitFor({ state: 'visible' });

    const unexpected = failures.filter((f) => !expectedRefusals.includes(f) && !f.includes('/api/v1/demo/onchain')
      && !/Failed to load resource: the server responded with a status of (403|404)/.test(f));
    assert.deepEqual(unexpected, [], `Support-session runtime failures:\n${unexpected.join('\n')}`);
  } catch (error) {
    await page.screenshot({ path: `${screenshotDir}/support-failure.png`, fullPage: true });
    throw error;
  } finally {
    await context.close();
  }
}

try {
  if (!only || only === 'customer') await verifyCustomer();
  if (!only || only === 'operator') await verifyOperator();
  if (!only || only === 'impersonation') await verifyImpersonation();
  console.log(`Headless Chromium checks passed (${only ?? 'customer, operator, impersonation'}). Screenshots: ${screenshotDir}`);
} finally {
  await browser.close();
}
