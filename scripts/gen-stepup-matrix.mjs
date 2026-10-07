#!/usr/bin/env node
// Generates two reference pages from the backend sources:
//   docs/compliance/step-up-matrix.md   every route with @RequiresStepUp, plus the DualControlGate reasons
//   docs/platform/api-routes.md         every mapped REST route (method, path, roles, step-up)
//
// Source of truth: every `@RequiresStepUp(...)` annotation (method level, or class level for every
// handler method without its own annotation) under backend/src/main/java, plus the reasons passed to
// DualControlGate.require / requireIfNotBootstrap (RouteApprovalActionCatalog.GATE_REASONS), plus the
// body-opt-out list in application.yml.
//
//   node scripts/gen-stepup-matrix.mjs           write both pages
//   node scripts/gen-stepup-matrix.mjs --check   exit 1 when a committed page is stale, or when a
//                                                gate reason has no endpoint hint below
//
// The scanner is deliberately small (comment/string-aware tokenising, annotation block parsing). When
// it cannot place a handler (no mapping found) it fails instead of silently dropping the row.

import { readdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const javaRoot = join(root, 'backend/src/main/java');
const appYml = join(root, 'backend/src/main/resources/application.yml');
const outFile = join(root, 'docs/compliance/step-up-matrix.md');
const routesFile = join(root, 'docs/platform/api-routes.md');
const check = process.argv.includes('--check');

// Endpoint hints for reasons that are enforced inside a service through DualControlGate (not visible as an
// annotation). Keyed by reason; the generator fails when GATE_REASONS contains a reason that is missing here.
const GATE_HINTS = {
  OPERATOR_USER_INVITE: ['POST /api/v1/admin/users', 'granting a gated operator role (REGISTRY_ADMIN, COMPLIANCE_OFFICER, SUPPORT_AGENT)'],
  OPERATOR_USER_ROLES: ['PATCH /api/v1/admin/users/{userId}/roles', 'granting or removing a gated role'],
  OPERATOR_USER_ENABLE: ['POST /api/v1/admin/users/{userId}/enable', 'privileged account'],
  OPERATOR_USER_DISABLE: ['POST /api/v1/admin/users/{userId}/disable', 'privileged account'],
  OPERATOR_USER_DELETE: ['DELETE /api/v1/admin/users/{userId}', 'privileged account'],
  OPERATOR_USER_REINSTATE: ['POST /api/v1/admin/users/{userId}/enable', 'account that an access review revoked'],
  DORA_INCIDENT_DOWNGRADE: ['POST /api/v1/dora/incidents/{id}/classify', 'downgrading an active MAJOR incident'],
  DORA_INCIDENT_CLOSE: ['PATCH /api/v1/dora/incidents/{id}/status', 'closing an active MAJOR incident'],
  CLIENT_CLASSIFICATION_DOWNGRADE: ['POST /api/v1/entities/{id}/classification', 'moving a client to a less protective MiFID II category'],
};

function walk(dir) {
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p));
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out.sort();
}

/** Blanks comments and the contents of string/char literals' escapes is NOT done: strings are kept, comments become spaces. */
function stripComments(src) {
  let out = '';
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (c === '/' && n === '*') {
      const end = src.indexOf('*/', i + 2);
      const stop = end < 0 ? src.length : end + 2;
      out += src.slice(i, stop).replace(/[^\n]/g, ' ');
      i = stop;
    } else if (c === '/' && n === '/') {
      const end = src.indexOf('\n', i);
      const stop = end < 0 ? src.length : end;
      out += ' '.repeat(stop - i);
      i = stop;
    } else if (src.startsWith('"""', i)) {
      const end = src.indexOf('"""', i + 3);
      const stop = end < 0 ? src.length : end + 3;
      out += src.slice(i, stop);
      i = stop;
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < src.length && src[j] !== c) j += src[j] === '\\' ? 2 : 1;
      out += src.slice(i, j + 1);
      i = j + 1;
    } else {
      out += c;
      i++;
    }
  }
  return out;
}

/** Index of the paren matching the "(" at `open` (string aware). */
function matchParen(s, open) {
  let depth = 0;
  for (let i = open; i < s.length; i++) {
    const c = s[i];
    if (c === '"') {
      i++;
      while (i < s.length && s[i] !== '"') i += s[i] === '\\' ? 2 : 1;
    } else if (c === '(') depth++;
    else if (c === ')' && --depth === 0) return i;
  }
  return -1;
}

/** Parses annotations at the start of `text` (each `@Name` optionally followed by `(...)`); returns [list, endIndex]. */
function parseAnnotationsForward(s, from) {
  const anns = [];
  let i = from;
  for (;;) {
    while (i < s.length && /\s/.test(s[i])) i++;
    if (s[i] !== '@' || s.startsWith('@interface', i)) break;
    const m = /^@([\w.]+)/.exec(s.slice(i));
    if (!m) break;
    let j = i + m[0].length;
    let args = '';
    let k = j;
    while (k < s.length && /\s/.test(s[k])) k++;
    if (s[k] === '(') {
      const close = matchParen(s, k);
      args = s.slice(k + 1, close);
      j = close + 1;
    }
    anns.push({ name: m[1].split('.').pop(), args, at: i });
    i = j;
  }
  return [anns, i];
}

/** Start of the annotation block that contains position `pos` (scan back over annotations). */
function blockStart(s, pos) {
  let depth = 0;
  for (let i = pos - 1; i >= 0; i--) {
    const c = s[i];
    if (c === ')') depth++;
    else if (c === '(') depth = Math.max(0, depth - 1);
    else if (depth === 0 && (c === ';' || c === '}' || c === '{')) return i + 1;
  }
  return 0;
}

const strings = (args) => [...args.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1]);
function mappingPaths(ann) {
  // value = "x" | path = "x" | "x" | {"a","b"}; no args = empty path
  const named = /(?:value|path)\s*=\s*(\{[^}]*\}|"(?:[^"\\]|\\.)*")/.exec(ann.args);
  if (named) return strings(named[1]);
  const lead = /^\s*(\{[^}]*\}|"(?:[^"\\]|\\.)*")/.exec(ann.args);
  if (lead) return strings(lead[1]);
  return [''];
}
function httpMethod(ann) {
  switch (ann.name) {
    case 'GetMapping': return 'GET';
    case 'PostMapping': return 'POST';
    case 'PutMapping': return 'PUT';
    case 'DeleteMapping': return 'DELETE';
    case 'PatchMapping': return 'PATCH';
    case 'RequestMapping': {
      const m = /RequestMethod\.(\w+)/.exec(ann.args);
      return m ? m[1] : 'ANY';
    }
    default: return null;
  }
}
function roles(anns) {
  const pa = anns.find((a) => a.name === 'PreAuthorize');
  if (!pa) return null;
  const expr = strings(pa.args).join('');
  const found = [...expr.matchAll(/hasRole\('(\w+)'\)/g)].map((m) => m[1]);
  for (const m of expr.matchAll(/hasAnyRole\(([^)]*)\)/g)) found.push(...[...m[1].matchAll(/'(\w+)'/g)].map((x) => x[1]));
  return found.length ? [...new Set(found)] : [expr.trim()];
}
function stepUpOf(ann, constants) {
  const a = ann.args;
  const reasonM = /reason\s*=\s*(?:"([^"]*)"|([A-Za-z_][\w.]*))/.exec(a);
  let reason = 'REGULATOR_GRADE_ACTION';
  if (reasonM) reason = reasonM[1] ?? constants.get(reasonM[2].split('.').pop()) ?? `?${reasonM[2]}`;
  const maxM = /maxAgeMinutes\s*=\s*(\d+)/.exec(a);
  return {
    reason,
    maxAge: maxM ? Number(maxM[1]) : 10,
    second: /requireSecondApprover\s*=\s*true/.test(a),
  };
}

const bodyOptOut = (() => {
  const yml = readFileSync(appYml, 'utf8');
  const m = /^\s*body-opt-out-reasons:\s*([A-Z0-9_,\s]+)$/m.exec(yml);
  return new Set(m ? m[1].split(',').map((x) => x.trim()).filter(Boolean) : []);
})();

const rows = [];
const allRoutes = [];
const problems = [];

for (const file of walk(javaRoot)) {
  const raw = readFileSync(file, 'utf8');
  if (!/@(?:Rest)?Controller\b/.test(raw)) continue;
  const s = stripComments(raw);
  const rel = relative(javaRoot, file);
  const module = rel.split('/')[3];
  const cls = /\b(?:class|interface|record)\s+(\w+)/.exec(s)?.[1] ?? file;
  const constants = new Map([...s.matchAll(/static\s+final\s+String\s+(\w+)\s*=\s*"([^"]*)"/g)].map((m) => [m[1], m[2]]));

  // class-level annotations: the block before the first top-level type declaration
  const typeIdx = s.search(/\b(?:public\s+|final\s+|abstract\s+)*(?:class|interface)\s+\w+/);
  const classStart = blockStart(s, typeIdx);
  const [classAnns] = parseAnnotationsForward(s, classStart);
  const classMap = classAnns.find((a) => a.name === 'RequestMapping');
  const base = classMap ? (mappingPaths(classMap)[0] ?? '') : '';
  const classRoles = roles(classAnns);
  const classStep = classAnns.find((a) => a.name === 'RequiresStepUp');

  // every handler method: a mapping annotation followed by a method head
  const seen = new Set();
  const mappingRe = /@(Get|Post|Put|Delete|Patch|Request)Mapping\b/g;
  for (let m; (m = mappingRe.exec(s)); ) {
    const start = blockStart(s, m.index);
    if (seen.has(start) || start <= classStart && start === classStart) continue;
    seen.add(start);
    const [anns, after] = parseAnnotationsForward(s, start);
    if (anns.some((a) => a.name === 'RequestMapping') && start < typeIdx) continue;
    const mapAnn = anns.find((a) => httpMethod(a));
    if (!mapAnn) continue;
    const head = /^[\s\S]*?\b(\w+)\s*\(/.exec(s.slice(after, after + 600));
    const methodName = head ? head[1] : '?';
    const stepAnn = anns.find((a) => a.name === 'RequiresStepUp');
    const step = stepAnn ? stepUpOf(stepAnn, constants) : classStep ? stepUpOf(classStep, constants) : null;
    const paths = mappingPaths(mapAnn);
    for (const p of paths) {
      allRoutes.push({
        module,
        cls,
        method: httpMethod(mapAnn),
        path: (base + p).replace(/\/+$/, '') || '/',
        roles: roles(anns) ?? classRoles ?? ['(authenticated)'],
        reason: step ? step.reason : '',
        second: step ? step.second : false,
      });
    }
    if (!step) continue;
    for (const p of paths) {
      rows.push({
        module,
        handler: `${cls}.${methodName}`,
        endpoint: `${httpMethod(mapAnn)} ${(base + p).replace(/\/+$/, '') || '/'}`,
        roles: roles(anns) ?? classRoles ?? ['(authenticated)'],
        classLevel: !stepAnn,
        ...step,
      });
    }
  }
  // sanity: every annotation occurrence must have produced a row (or be the class-level annotation)
  const annCount = [...s.matchAll(/@RequiresStepUp\b/g)].length;
  if (annCount === 0) continue;
  const own = rows.filter((r) => r.handler.startsWith(`${cls}.`) && !r.classLevel).length;
  const classRows = rows.filter((r) => r.handler.startsWith(`${cls}.`) && r.classLevel).length;
  const expected = annCount - (classStep ? 1 : 0);
  if (own < expected) problems.push(`${rel}: ${annCount} @RequiresStepUp but only ${own} placed on a mapped handler`);
  if (classStep && classRows === 0) problems.push(`${rel}: class-level @RequiresStepUp with no mapped handler`);
}

// gate reasons
const catalog = readFileSync(join(javaRoot, 'de/makibytes/registerwerk/stepup/internal/RouteApprovalActionCatalog.java'), 'utf8');
const gateBlock = /GATE_REASONS\s*=\s*Set\.of\(([\s\S]*?)\);/.exec(catalog)?.[1] ?? '';
const gateReasons = strings(gateBlock);
if (!gateReasons.length) problems.push('GATE_REASONS not found in RouteApprovalActionCatalog');
const gateUse = {};
for (const file of walk(javaRoot)) {
  const t = stripComments(readFileSync(file, 'utf8'));
  for (const m of t.matchAll(/\b(requireIfNotBootstrap|require|requirePrivilegedApproval)\(\s*"([A-Z0-9_]+)"/g)) {
    if (m[1] === 'require' && !/dualControlGate\.require\(/.test(t.slice(Math.max(0, m.index - 20), m.index + 20))) continue;
    gateUse[m[2]] = m[1] === 'require' ? 'always' : 'unless bootstrap';
  }
}
for (const r of gateReasons) {
  if (!GATE_HINTS[r]) problems.push(`gate reason ${r} has no endpoint hint in scripts/gen-stepup-matrix.mjs (GATE_HINTS)`);
  if (!gateUse[r]) problems.push(`gate reason ${r} is in GATE_REASONS but no DualControlGate call site was found`);
}
for (const r of Object.keys(GATE_HINTS)) if (!gateReasons.includes(r)) problems.push(`GATE_HINTS has ${r}, GATE_REASONS does not`);

if (problems.length) {
  console.error('gen-stepup-matrix: ' + problems.join('\n  '));
  process.exit(1);
}

rows.sort((a, b) => a.module.localeCompare(b.module) || a.endpoint.localeCompare(b.endpoint) || a.reason.localeCompare(b.reason));
const esc = (x) => x.replace(/\|/g, '\\|');
const yesNo = (b) => (b ? 'yes' : 'no');
const bodyCell = (r) => (bodyOptOut.has(r.reason) ? 'no (method, path, query only)' : 'yes');

const lines = [];
lines.push('# Step-up matrix (generated)', '');
lines.push('!!! note "Generated file"');
lines.push('    This page is generated by `scripts/gen-stepup-matrix.mjs` from the `@RequiresStepUp` annotations');
lines.push('    in `backend/src/main/java`. Do not edit it by hand; a CI check (`--check`) fails when it is stale.');
lines.push('    English only. See [Step-up authentication and four-eyes](step-up-mfa.md) for how the controls work.', '');
lines.push(`${rows.length} annotated routes, ${new Set(rows.map((r) => r.reason)).size} distinct reasons, plus ${gateReasons.length} reasons enforced inside services through \`DualControlGate\`.`, '');
lines.push('Columns:', '');
lines.push('- **Reason** is the `@RequiresStepUp(reason)` value, recorded in the audit trail and bound into the second approver\'s token.');
lines.push('- **Max age** is how recent the initiator\'s step-up proof must be (minutes).');
lines.push('- **Second approver** is `requireSecondApprover = true`: a distinct, currently enabled REGISTRY_ADMIN or COMPLIANCE_OFFICER must approve (`X-Dual-Control-Token`).');
lines.push('- **Body bound**: for second-approver routes the approval is bound to method, path, query and the canonical JSON request body. All reasons bind the body except the opt-out list `registerwerk.auth.step-up.dual-control.body-opt-out-reasons` (' + [...bodyOptOut].map((x) => `\`${x}\``).join(', ') + '), which bind method, path and query only.');
lines.push('- **Roles** is the `@PreAuthorize` role expression on the method or class; the step-up control is additional to it.', '');

let currentModule = '';
for (const r of rows) {
  if (r.module !== currentModule) {
    currentModule = r.module;
    lines.push('', `## ${currentModule}`, '');
    lines.push('| Endpoint | Reason | Max age | Second approver | Body bound | Roles | Handler |');
    lines.push('|---|---|---|---|---|---|---|');
  }
  lines.push(`| \`${esc(r.endpoint)}\` | \`${r.reason}\` | ${r.maxAge} | ${yesNo(r.second)} | ${r.second ? bodyCell(r) : 'n/a'} | ${esc(r.roles.join(', '))} | \`${r.handler}\`${r.classLevel ? ' (class-level)' : ''} |`);
}

lines.push('', '## Reasons enforced inside services (`DualControlGate`)', '');
lines.push('These are not visible as annotations on the handler. The endpoint itself may carry a single-factor `@RequiresStepUp` (for example `OPERATOR_USER_ADMIN`); the service then demands the second approver when the condition holds.', '');
lines.push('| Reason | Endpoint | Second approver | When |');
lines.push('|---|---|---|---|');
for (const r of gateReasons) {
  const [ep, when] = GATE_HINTS[r];
  const how = gateUse[r] === 'always' ? 'yes' : 'yes (not while fewer than two enrolled administrators exist: bootstrap)';
  lines.push(`| \`${r}\` | \`${ep}\` | ${how} | ${when} |`);
}
lines.push('');

const content = lines.join('\n') + '\n';

// API route index
allRoutes.sort((a, b) => a.module.localeCompare(b.module) || a.cls.localeCompare(b.cls) || a.path.localeCompare(b.path) || a.method.localeCompare(b.method));
const rl = [];
rl.push('# API route index (generated)', '');
rl.push('!!! note "Generated file"');
rl.push('    This page is generated by `scripts/gen-stepup-matrix.mjs` from the Spring MVC mappings in');
rl.push('    `backend/src/main/java`. Do not edit it by hand; a CI check (`--check`) fails when it is stale.');
rl.push('    English only. It lists every mapped REST route with the role expression of its `@PreAuthorize` and whether it demands step-up (**S**) or step-up plus a second approver (**S+4**; the reason is in the [step-up matrix](../compliance/step-up-matrix.md)).');
rl.push('    A route without a role expression is only protected by authentication (or is public, under `/api/v1/public/**`).', '');
rl.push(`${allRoutes.length} routes in ${new Set(allRoutes.map((r) => r.cls)).size} controllers. For request and response schemas use the OpenAPI document (\`SWAGGER_ENABLED=true\`, see [REST API overview](api.md)).`, '');
let curModule = '';
let curCls = '';
for (const r of allRoutes) {
  if (r.module !== curModule) {
    curModule = r.module;
    rl.push('', `## ${curModule}`);
    curCls = '';
  }
  if (r.cls !== curCls) {
    curCls = r.cls;
    rl.push('', `### ${curCls}`, '', '| Method | Path | Roles | Step-up |', '|---|---|---|---|');
  }
  rl.push(`| ${r.method} | \`${esc(r.path)}\` | ${esc(r.roles.join(', '))} | ${r.reason ? (r.second ? 'S+4' : 'S') : ''} |`);
}
rl.push('');
const routesContent = rl.join('\n') + '\n';

const targets = [[outFile, content], [routesFile, routesContent]];
if (check) {
  let stale = false;
  for (const [file, text] of targets) {
    const current = existsSync(file) ? readFileSync(file, 'utf8') : '';
    if (current !== text) {
      console.error(`${relative(root, file)} is stale: run \`node scripts/gen-stepup-matrix.mjs\``);
      stale = true;
    }
  }
  if (stale) process.exit(1);
  console.log(`generated pages up to date (${rows.length} step-up routes, ${gateReasons.length} gate reasons, ${allRoutes.length} routes)`);
} else {
  for (const [file, text] of targets) writeFileSync(file, text);
  console.log(`wrote step-up matrix (${rows.length} routes, ${gateReasons.length} gate reasons) and API route index (${allRoutes.length} routes)`);
}
