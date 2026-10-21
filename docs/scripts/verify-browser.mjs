import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { isNetworkChange, withNetworkChangeRetry } from './lib/network-retry.mjs';

const baseUrl = process.env.DOCS_BASE_URL ?? 'http://127.0.0.1:48003';
const docsOrigin = new URL(baseUrl).origin;
const docsPort = new URL(docsOrigin).port;

/**
 * One complete run of the checks on a fresh browser. Resolves to the console/page errors it saw (the caller asserts
 * there are none) together with a description of every failed request. Chromium aborts requests with
 * `net::ERR_NETWORK_CHANGED` when the host's network changes underneath it, which a CI runner does when Docker starts
 * a container; `withNetworkChangeRetry` runs the whole check again in that case and in no other.
 */
async function runChecks() {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const pageErrors = [];
  const failedRequests = [];

  page.on('pageerror', (error) => pageErrors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') pageErrors.push(message.text());
  });
  // The console message for a failed load carries no URL; this names the resource.
  page.on('requestfailed', (request) => {
    failedRequests.push(`${request.url()} (${request.failure()?.errorText ?? 'unknown error'})`);
  });

  try {
    await page.goto(baseUrl, { waitUntil: 'networkidle' });

    const diagrams = page.locator('.md-content div.mermaid');
    await diagrams.first().waitFor();
    assert.equal(await diagrams.count(), 2, 'The homepage must render both Mermaid diagrams');
    for (const box of await diagrams.evaluateAll((elements) => elements.map((element) => {
      const rect = element.getBoundingClientRect();
      return { width: rect.width, height: rect.height };
    }))) {
      assert.ok(box.width > 0 && box.height > 0, 'Rendered Mermaid diagrams must occupy visible space');
    }
    assert.equal(
      await page.locator('.md-content code.mermaid').count(),
      0,
      'Mermaid source blocks must be replaced by rendered diagrams',
    );

    const chaincacheLink = page.getByRole('link', { name: 'Chaincache documentation' });
    await chaincacheLink.waitFor();
    assert.equal(await page.getByText('Optional component', { exact: true }).count(), 1);
    assert.equal(new URL(await chaincacheLink.getAttribute('href'), page.url()).pathname, '/chaincache/');

    await page.goto(new URL('/chaincache/', docsOrigin).href, { waitUntil: 'networkidle' });
    await page.getByRole('heading', { level: 1, name: 'Chaincache documentation' }).waitFor();
    assert.ok(await page.getByText('Chaincache (optional)', { exact: true }).count());

    const initialScheme = await page.locator('body').getAttribute('data-md-color-scheme');
    assert.equal(initialScheme, 'default');
    await page.locator('label[for="__palette_1"]').click();
    await page.waitForFunction(() => document.body.dataset.mdColorScheme === 'slate');
    assert.equal(await page.locator('body').getAttribute('data-md-color-scheme'), 'slate');

    // Test a nested page: Material normalises contextual language destinations without their
    // trailing slash, which exercises nginx's directory redirect. The redirect must be relative;
    // an absolute redirect would use the container's internal port 80 and drop public port 48003.
    await page.goto(new URL('/customer/intro/', docsOrigin).href, { waitUntil: 'networkidle' });
    const redirectResponse = await page.request.get(
      new URL('/fr/customer/intro', docsOrigin).href,
      { maxRedirects: 0 },
    );
    assert.equal(redirectResponse.status(), 301);
    assert.equal(redirectResponse.headers().location, '/fr/customer/intro/');

    const frenchLink = page.locator('.md-select__link[hreflang="fr"]').first();
    await frenchLink.waitFor();
    const frenchUrl = new URL(await frenchLink.getAttribute('href'));
    assert.equal(frenchUrl.port, docsPort);
    assert.equal(frenchUrl.origin, docsOrigin);

    await frenchLink.locator('xpath=ancestor::div[contains(@class, "md-select")]//button').click();
    await frenchLink.click();
    await page.waitForLoadState('networkidle');
    const switchedUrl = new URL(page.url());
    assert.equal(switchedUrl.port, docsPort);
    assert.equal(switchedUrl.pathname, '/fr/customer/intro/');
    assert.equal(await page.locator('body').getAttribute('data-md-color-scheme'), 'slate');

    return { errors: pageErrors, failedRequests };
  } catch (error) {
    // An exception after the browser reported a network change is most likely a consequence of it (a script that
    // never loaded, a navigation that was cut off); let the retry policy decide.
    if (pageErrors.some(isNetworkChange) || failedRequests.some(isNetworkChange)) error.networkChangeSeen = true;
    throw error;
  } finally {
    await browser.close();
  }
}

const { errors, failedRequests } = await withNetworkChangeRetry(runChecks);
const failed = failedRequests.length > 0 ? ` (failed requests: ${failedRequests.join(', ')})` : '';
assert.deepEqual(errors, [], `Docs runtime emitted errors: ${errors.join('; ')}${failed}`);
console.log('Docs theme switching, Mermaid rendering, and language-port preservation verified.');
