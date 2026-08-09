import assert from 'node:assert/strict';
import { chromium } from 'playwright';

const baseUrl = process.env.DOCS_BASE_URL ?? 'http://127.0.0.1:8003';
const browser = await chromium.launch();
const page = await browser.newPage();
const pageErrors = [];

page.on('pageerror', (error) => pageErrors.push(error.message));
page.on('console', (message) => {
  if (message.type() === 'error') pageErrors.push(message.text());
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

  const initialScheme = await page.locator('body').getAttribute('data-md-color-scheme');
  assert.equal(initialScheme, 'default');
  await page.locator('label[for="__palette_1"]').click();
  await page.waitForFunction(() => document.body.dataset.mdColorScheme === 'slate');
  assert.equal(await page.locator('body').getAttribute('data-md-color-scheme'), 'slate');

  const germanLink = page.locator('.md-select__link[hreflang="de"]').first();
  await germanLink.waitFor();
  const germanUrl = new URL(await germanLink.getAttribute('href'));
  assert.equal(germanUrl.port, '8003');
  assert.equal(germanUrl.origin, baseUrl);

  await germanLink.locator('xpath=ancestor::div[contains(@class, "md-select")]//button').click();
  await germanLink.click();
  await page.waitForLoadState('networkidle');
  const switchedUrl = new URL(page.url());
  assert.equal(switchedUrl.port, '8003');
  assert.match(switchedUrl.pathname, /^\/de\//);
  assert.equal(await page.locator('body').getAttribute('data-md-color-scheme'), 'slate');

  assert.deepEqual(pageErrors, [], `Docs runtime emitted errors: ${pageErrors.join('; ')}`);
  console.log('Docs theme switching, Mermaid rendering, and language-port preservation verified.');
} finally {
  await browser.close();
}
