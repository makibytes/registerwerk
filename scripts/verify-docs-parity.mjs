#!/usr/bin/env node
// Ratchet for the translated documentation: a translated variant (docs/**/<page>.<lang>.md) must not fall
// further behind its English page than the committed baseline allows. It compares two structural counts that
// carry the safety content of a page: admonitions (`!!! warning`, `!!! danger`, ...) and fenced code blocks.
//
//   node scripts/verify-docs-parity.mjs            fail on a regression (a larger deficit than the baseline)
//   node scripts/verify-docs-parity.mjs --update   rewrite scripts/docs-parity-baseline.json
//
// A deficit is max(0, English count - variant count). A variant with more blocks than English is fine.
// New variants have no baseline entry, so their deficit must be zero. Improvements pass; run --update to
// tighten the baseline.

import { readdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const docs = join(root, 'docs');
const baselineFile = join(root, 'scripts/docs-parity-baseline.json');
const LANGS = ['de', 'fr', 'es', 'it'];
const SKIP = new Set(['node_modules', '_chaincache', 'chaincache', 'assets', 'overrides', 'includes', 'scripts']);

function walk(dir) {
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) {
      if (!SKIP.has(e.name)) out.push(...walk(join(dir, e.name)));
    } else if (e.name.endsWith('.md')) out.push(join(dir, e.name));
  }
  return out;
}

function counts(file) {
  const t = readFileSync(file, 'utf8');
  return {
    admonitions: (t.match(/^\s*!!!\s/gm) || []).length,
    fences: Math.floor((t.match(/^\s*```/gm) || []).length / 2),
  };
}

const current = {};
for (const file of walk(docs)) {
  const rel = relative(docs, file).split('\\').join('/');
  if (LANGS.some((l) => rel.endsWith(`.${l}.md`))) continue;
  const en = counts(file);
  for (const lang of LANGS) {
    const variant = file.replace(/\.md$/, `.${lang}.md`);
    if (!existsSync(variant)) continue;
    const v = counts(variant);
    const key = `${rel}|${lang}`;
    current[key] = {
      admonitions: Math.max(0, en.admonitions - v.admonitions),
      fences: Math.max(0, en.fences - v.fences),
    };
  }
}

if (process.argv.includes('--update')) {
  const gaps = Object.fromEntries(Object.entries(current).filter(([, d]) => d.admonitions || d.fences).sort(([a], [b]) => a.localeCompare(b)));
  writeFileSync(baselineFile, JSON.stringify(gaps, null, 2) + '\n');
  console.log(`baseline written: ${Object.keys(gaps).length} variants with a gap, ${Object.keys(current).length} variants checked`);
  process.exit(0);
}

const baseline = existsSync(baselineFile) ? JSON.parse(readFileSync(baselineFile, 'utf8')) : {};
const failures = [];
for (const [key, d] of Object.entries(current)) {
  const allowed = baseline[key] || { admonitions: 0, fences: 0 };
  if (d.admonitions > allowed.admonitions) failures.push(`${key}: ${d.admonitions} admonition(s) fewer than English (baseline allows ${allowed.admonitions})`);
  if (d.fences > allowed.fences) failures.push(`${key}: ${d.fences} fenced block(s) fewer than English (baseline allows ${allowed.fences})`);
}
if (failures.length) {
  console.error('Translated pages fell behind their English page:\n  ' + failures.join('\n  '));
  console.error('Port the missing warnings/code blocks into the variant (preferred), or, for a deliberate gap, run `node scripts/verify-docs-parity.mjs --update`.');
  process.exit(1);
}
const open = Object.values(current).filter((d) => d.admonitions || d.fences).length;
console.log(`docs parity OK: ${Object.keys(current).length} variants checked, ${open} with a gap inside the baseline`);
