#!/usr/bin/env node

import crypto from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { evidenceCommands } from "./run-claim-evidence.mjs";

const scriptPath = fileURLToPath(import.meta.url);
const defaultRepo = path.resolve(path.dirname(scriptPath), "..");
const HASH = /^[a-f0-9]{64}$/;
const CLAIM_ID = /^RW-CLAIM-[0-9]{4}$/;
const ACTIVE_EXEMPT_STATUSES = new Set(["VERIFIED_TECHNICAL", "EXTERNALLY_APPROVED"]);
const CATEGORIES = new Set(["LEGAL", "REGULATORY", "SECURITY", "PRIVACY", "SETTLEMENT", "OPERATIONS"]);
const STATUSES = new Set([
  "SELF_ASSESSED_UNREVIEWED", "VERIFIED_TECHNICAL", "CONDITIONAL", "PROTOTYPE", "FALSE_RETIRED",
  "EXTERNAL_REVIEW_REQUIRED", "EXTERNALLY_APPROVED", "BLOCKED"
]);
/**
 * The only reviewer string permitted when nobody has actually reviewed a claim, and the only
 * value an automated contributor may write. Naming a person or body that did not review the
 * claim would fabricate an audit trail, which is exactly what this registry exists to prevent,
 * so the sentinel and the SELF_ASSESSED_UNREVIEWED status are locked to each other.
 */
const UNREVIEWED_REVIEWER = "UNREVIEWED — automated self-assessment, no human or panel review";
const EVIDENCE_TYPES = new Set(["REPOSITORY", "TEST", "OFFICIAL", "DECISION"]);
const MAX_REVIEW_DAYS = 183;
const MAX_SCANNABLE_BYTES = 2_000_000;
const EXPECTED_SCHEMA_SHA256 = "c08d7c3958b3b7b301760418fd0c997ff9f8dbf3a36e13c6ee0afd8510379b4a";
const APPROVED_SUPPRESSIONS = Object.freeze({});

const highRiskPatterns = [
  ["unqualified-compliance", /\b(?:fully[- ]compliant|completely[- ]compliant|the compliant alternative)\b/gi],
  ["generic-compliance", /\bcompliant (?:with|under|pursuant to)\b/gi],
  ["regime-compliance", /\b(?:MiCAR|MiFIR|DORA|eWpG|GDPR|AMLR|DAC8|KStTG|CARF)[- ]compliant\b/gi],
  ["production-readiness", /\b(?:production[- ]ready|ready for production)\b/gi],
  ["universal-chain-support", /\b(?:supports? all major[^.\n]{0,50}chains|all (?:major )?(?:chains|standards)[^.\n]{0,60}(?:supported|implemented))\b/gi],
  ["pii-encryption", /\b(?:PII|personal data|natural[- ]person fields?)[^\n.]{0,90}\bencrypted at rest\b/gi],
  ["legal-effect", /\blegally (?:binding|effective)\b/gi],
  ["automatic-filing", /\b(?:automatically|automatic)[^\n.]{0,80}\b(?:filed|submitted)\b/gi],
  ["compliance-guarantee", /\b(?:guarantees?|ensures?) compliance\b/gi],
  ["regulatory-obligation", /\b(?:satisf(?:y|ies|ied)|meets?|fulfils?|fulfills?)[^\n.]{0,100}\b(?:statutory |regulatory )?(?:obligations?|requirements?)\b/gi],
  ["atomic-settlement", /\b(?:atomic (?:settlement|DvP)|settle[sd]? atomically|atomically settle[sd]?)\b/gi]
];

const scanExtensions = new Set([
  ".cairo", ".daml", ".graphql", ".html", ".java", ".js", ".json", ".md", ".mjs",
  ".sh", ".sol", ".sql", ".toml", ".ts", ".tsx", ".xml", ".yaml", ".yml"
]);
const excludedDirectories = new Set([
  ".git", ".docusaurus", ".next", ".turbo", "artifacts", "build", "cache", "coverage",
  "dist", "generated", "lib", "node_modules", "out", "target"
]);
const scannerFixtureFiles = new Set([
  "docs/claims/registry.json", "docs/claims/registry.schema.json", "docs/claims/registry.history.json",
  "docs/claims/suppressions.json",
  "scripts/claims-scan-fixtures.json", "scripts/verify-claims.mjs", "scripts/verify-claims.test.mjs"
]);

export function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

export function recordDigest(claim) {
  const copy = structuredClone(claim);
  delete copy.recordSha256;
  return sha256(canonical(copy));
}

export function parseRealDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value ?? "")) return null;
  const date = new Date(`${value}T00:00:00.000Z`);
  return Number.isNaN(date.valueOf()) || date.toISOString().slice(0, 10) !== value ? null : date;
}

function exactKeys(value, allowed, required, location, errors) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    errors.push(`${location}: expected object`);
    return false;
  }
  for (const key of required) if (!Object.hasOwn(value, key)) errors.push(`${location}: missing ${key}`);
  for (const key of Object.keys(value)) if (!allowed.includes(key)) errors.push(`${location}: unexpected ${key}`);
  return true;
}

function resolveRepositoryFile(repo, reference, location, errors) {
  if (typeof reference !== "string" || !reference || reference.includes("#") || path.isAbsolute(reference)) {
    errors.push(`${location}: reference must be a repository-relative file path without an ignored anchor`);
    return null;
  }
  const target = path.resolve(repo, reference);
  const rootPrefix = `${path.resolve(repo)}${path.sep}`;
  if (!target.startsWith(rootPrefix)) {
    errors.push(`${location}: path escapes the repository`);
    return null;
  }
  if (!fs.existsSync(target) || !fs.lstatSync(target).isFile()) {
    errors.push(`${location}: expected an existing regular file: ${reference}`);
    return null;
  }
  const realRoot = fs.realpathSync(repo);
  const realTarget = fs.realpathSync(target);
  if (!realTarget.startsWith(`${realRoot}${path.sep}`)) {
    errors.push(`${location}: symlink resolves outside the repository`);
    return null;
  }
  return realTarget;
}

function statusCanControl(claim, externalApprovalEnabled) {
  if (!ACTIVE_EXEMPT_STATUSES.has(claim.status)) return false;
  if (claim.category === "LEGAL" || claim.category === "REGULATORY") {
    return externalApprovalEnabled && claim.status === "EXTERNALLY_APPROVED";
  }
  return true;
}

function containingSegment(lines, lineIndex, matchIndex) {
  const line = lines[lineIndex];
  if (line.includes("|")) {
    const cellStart = line.lastIndexOf("|", matchIndex);
    const cellEnd = line.indexOf("|", matchIndex);
    if (cellEnd >= 0) {
      const nextCellEnd = line.indexOf("|", cellEnd + 1);
      return line.slice(Math.max(0, cellStart + 1), nextCellEnd >= 0 ? nextCellEnd : line.length);
    }
  }
  let logical = line;
  let offset = matchIndex;
  const previous = lines[lineIndex - 1] ?? "";
  if (/^\s+/.test(line) && previous && !/[.!?;|]\s*$/.test(previous)) {
    logical = `${previous} ${line}`;
    offset += previous.length + 1;
  }
  const boundaries = [];
  const boundaryPattern = /[.!?;,:]|\s(?:—|–|-)\s|\b(?:and|but|although|however|yet|while|whereas|though|despite|notwithstanding)\b/gi;
  for (const boundary of logical.matchAll(boundaryPattern)) {
    boundaries.push({ start: boundary.index ?? 0, end: (boundary.index ?? 0) + boundary[0].length });
  }
  const left = boundaries.filter(boundary => boundary.end <= offset).at(-1)?.end ?? 0;
  const right = boundaries.find(boundary => boundary.start > offset)?.start ?? logical.length;
  return logical.slice(left, right);
}

function isExplicitlyQualified(segment, matchedText) {
  const escaped = matchedText.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const immediateNegation = new RegExp(
    `\\b(?:(?:not|never)\\s+(?:(?:currently|fully|completely|universally|legally|technically)\\s+)?|no\\s+longer\\s+)${escaped}`,
    "i"
  );
  const auxiliaryNegation = new RegExp(
    `\\b(?:(?:does?|do|did|can|could|must|will|would|shall)\\s+not|cannot)\\s+(?:by itself\\s+)?${escaped}`,
    "i"
  );
  const evidentiaryNegation = new RegExp(
    `\\b(?:(?:does?|do|did|can|could|must|will|would|shall)\\s+not|cannot|never)\\s+`
      + `(?:by itself\\s+)?(?:prove|establish|demonstrate|constitute|guarantee|show|be considered|be described as)\\s+`
      + `(?:(?:that|this|the|it|a|an)\\s+){0,2}${escaped}`,
    "i"
  );
  const qualifiedAfter = new RegExp(`${escaped}[^.;|]{0,100}(?:\\bis (?:false|unverified|unvalidated)\\b|\\bonly (?:if|for|under|within)\\b)`, "i");
  return immediateNegation.test(segment)
    || auxiliaryNegation.test(segment)
    || evidentiaryNegation.test(segment)
    || qualifiedAfter.test(segment)
    || (/\|/.test(segment) && /\|\s*(?:False|Placeholder|Blocked|External review required)\b/i.test(segment));
}

export function scanText(relative, content, claims = [], suppressions = [], externalApprovalEnabled = false) {
  const findings = [];
  const lines = content.split(/\r?\n/);
  for (let lineIndex = 0; lineIndex < lines.length; lineIndex++) {
    const line = lines[lineIndex];
    for (const [policy, pattern] of highRiskPatterns) {
      pattern.lastIndex = 0;
      for (const match of line.matchAll(pattern)) {
        const controlled = claims.some(claim => statusCanControl(claim, externalApprovalEnabled)
          && claim.path === relative
          && line.includes(claim.statement)
          && claim.statement.includes(match[0]));
        const suppressedImmutableHistory = suppressions.some(suppression =>
          suppression.path === relative
          && line.trim() === suppression.statement
          && suppression.statement.includes(match[0]));
        const segment = containingSegment(lines, lineIndex, match.index ?? 0);
        if (!controlled && !suppressedImmutableHistory && !isExplicitlyQualified(segment, match[0])) {
          findings.push(`${relative}:${lineIndex + 1}: unlabelled ${policy} claim: ${match[0]}`);
        }
      }
    }
  }
  return findings;
}

function allScannableFiles(repo, errors) {
  const files = [];
  function walk(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      if (entry.isDirectory() && excludedDirectories.has(entry.name)) continue;
      const child = path.join(directory, entry.name);
      if (entry.isDirectory()) walk(child);
      else if (entry.isSymbolicLink() && scanExtensions.has(path.extname(entry.name).toLowerCase())) {
        errors.push(`${path.relative(repo, child).split(path.sep).join("/")}: scannable text must not be a symlink`);
      }
      else if (entry.isFile() && scanExtensions.has(path.extname(entry.name).toLowerCase())) files.push(child);
    }
  }
  walk(repo);
  return files.sort();
}

export function workflowEvidenceErrors(content) {
  const errors = [];
  const lines = content.split(/\r?\n/);
  for (const commandId of Object.keys(evidenceCommands)) {
    const escaped = commandId.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const commandPattern = new RegExp(`^(\\s*)-\\s+run:\\s+node scripts/run-claim-evidence\\.mjs ${escaped}\\s*$`);
    const matches = [];
    for (let index = 0; index < lines.length; index++) {
      const match = lines[index].match(commandPattern);
      if (match) matches.push({ index, indent: match[1].length });
    }
    if (matches.length !== 1) {
      errors.push(`claims workflow must contain exactly one executable evidence step for ${commandId}; found ${matches.length}`);
      continue;
    }
    const { index, indent } = matches[0];
    if (indent !== 6) {
      errors.push(`claims workflow evidence step ${commandId} must be a direct six-space-indented job step`);
      continue;
    }
    let jobStart = -1;
    for (let cursor = index - 1; cursor >= 0; cursor--) {
      if (/^  [A-Za-z0-9_-]+:\s*$/.test(lines[cursor])) {
        jobStart = cursor;
        break;
      }
    }
    if (jobStart < 0 || !lines.slice(jobStart, index).some(line => /^    steps:\s*$/.test(line))) {
      errors.push(`claims workflow evidence step ${commandId} is not inside a canonical job steps block`);
      continue;
    }
    let jobEnd = lines.length;
    for (let cursor = jobStart + 1; cursor < lines.length; cursor++) {
      if (/^  [A-Za-z0-9_-]+:\s*$/.test(lines[cursor])) {
        jobEnd = cursor;
        break;
      }
    }
    if (lines.slice(jobStart, jobEnd).some(line => /^    (?:if|continue-on-error|needs):\s*/.test(line))) {
      errors.push(`claims workflow evidence job ${commandId} must be unconditional, independent, and gating`);
    }
    let end = lines.length;
    for (let cursor = index + 1; cursor < lines.length; cursor++) {
      const step = lines[cursor].match(/^(\s*)-\s+/);
      if (step && step[1].length === indent) {
        end = cursor;
        break;
      }
    }
    const stepBody = lines.slice(index, end).join("\n");
    if (/^\s*(?:if|continue-on-error):/m.test(stepBody)) {
      errors.push(`claims workflow evidence step ${commandId} must be unconditional and gating`);
    }
  }
  return errors;
}

export function compareHistoricalState(registry, history, baseRegistry, baseHistory) {
  const errors = [];
  const currentClaims = new Set((registry.claims ?? []).map(claim => claim.id));
  const currentHistory = new Map((history.entries ?? []).map(entry => [entry.id, entry.firstRegisteredAt]));
  for (const claim of baseRegistry.claims ?? []) {
    if (!currentClaims.has(claim.id)) errors.push(`${claim.id}: claim present in base was removed; retain a FALSE_RETIRED tombstone`);
  }
  for (const entry of baseHistory.entries ?? []) {
    if (!currentHistory.has(entry.id)) errors.push(`${entry.id}: immutable history entry present in base was removed`);
    else if (currentHistory.get(entry.id) !== entry.firstRegisteredAt) {
      errors.push(`${entry.id}: immutable firstRegisteredAt changed from ${entry.firstRegisteredAt}`);
    }
  }
  const governanceChanged = canonical(registry) !== canonical(baseRegistry) || canonical(history) !== canonical(baseHistory);
  if (governanceChanged && (!Number.isInteger(registry.registryRevision)
      || !Number.isInteger(baseRegistry.registryRevision)
      || registry.registryRevision <= baseRegistry.registryRevision)) {
    errors.push(`registry.registryRevision must increase above base revision ${baseRegistry.registryRevision}`);
  }
  return errors;
}

export function verifyClaims({ repo = defaultRepo, asOf = new Date().toISOString().slice(0, 10) } = {}) {
  const errors = [];
  const registryPath = path.join(repo, "docs/claims/registry.json");
  const schemaPath = path.join(repo, "docs/claims/registry.schema.json");
  const registry = JSON.parse(fs.readFileSync(registryPath, "utf8"));
  const schemaContent = fs.readFileSync(schemaPath);
  JSON.parse(schemaContent.toString("utf8"));
  if (sha256(schemaContent) !== EXPECTED_SCHEMA_SHA256) {
    errors.push("registry schema content differs from the closed validator version");
  }

  const rootAllowed = ["$schema", "schemaVersion", "registryRevision", "generatedAt", "historyPath", "suppressionsPath", "externalApprovalEnabled", "claims"];
  exactKeys(registry, rootAllowed, rootAllowed, "registry", errors);
  if (registry.$schema !== "./registry.schema.json") errors.push("registry.$schema: unsupported schema reference");
  if (registry.schemaVersion !== 1) errors.push("registry.schemaVersion: unsupported version");
  if (!Number.isInteger(registry.registryRevision) || registry.registryRevision < 1) errors.push("registry.registryRevision: expected positive integer");
  if (registry.historyPath !== "docs/claims/registry.history.json") errors.push("registry.historyPath: unexpected path");
  if (registry.suppressionsPath !== "docs/claims/suppressions.json") errors.push("registry.suppressionsPath: unexpected path");
  if (registry.externalApprovalEnabled !== false) errors.push("registry.externalApprovalEnabled: external approvals are disabled in schema version 1");
  if (!Array.isArray(registry.claims) || registry.claims.length < 1) errors.push("registry.claims: requires at least one tombstone or active claim");

  const today = parseRealDate(asOf);
  const generatedAt = parseRealDate(registry.generatedAt);
  if (!today) throw new Error(`Invalid verifier asOf date: ${asOf}`);
  if (!generatedAt) errors.push("registry.generatedAt: invalid calendar date");
  else if (generatedAt > today) errors.push("registry.generatedAt: cannot be in the future");

  const claimAllowed = [
    "id", "statement", "path", "textSha256", "recordSha256", "category", "status", "scope",
    "owner", "reviewedBy", "reviewedAt", "expiresAt", "evidence", "limitations", "externalReview"
  ];
  const claimRequired = claimAllowed.filter(key => key !== "externalReview");
  const evidenceAllowed = ["type", "reference", "sha256", "ciCommandId"];
  const seenIds = new Set();

  for (const [index, claim] of (registry.claims ?? []).entries()) {
    const location = `registry.claims[${index}]`;
    if (!exactKeys(claim, claimAllowed, claimRequired, location, errors)) continue;
    if (!CLAIM_ID.test(claim.id ?? "")) errors.push(`${location}.id: invalid id`);
    if (seenIds.has(claim.id)) errors.push(`${location}.id: duplicate ${claim.id}`);
    seenIds.add(claim.id);
    if (typeof claim.statement !== "string" || claim.statement.length < 20) errors.push(`${claim.id}: statement too short`);
    if (!HASH.test(claim.textSha256 ?? "") || sha256(claim.statement ?? "") !== claim.textSha256) errors.push(`${claim.id}: text SHA-256 mismatch`);
    if (!HASH.test(claim.recordSha256 ?? "") || recordDigest(claim) !== claim.recordSha256) errors.push(`${claim.id}: canonical record SHA-256 mismatch`);
    if (!CATEGORIES.has(claim.category)) errors.push(`${claim.id}: invalid category`);
    if (!STATUSES.has(claim.status)) errors.push(`${claim.id}: invalid status`);
    if (claim.status === "EXTERNALLY_APPROVED" && !registry.externalApprovalEnabled) {
      errors.push(`${claim.id}: EXTERNALLY_APPROVED is disabled in schema version 1`);
    }
    for (const field of ["scope", "owner", "reviewedBy"]) {
      if (typeof claim[field] !== "string" || claim[field].trim().length < 3) errors.push(`${claim.id}: invalid ${field}`);
    }
    if (claim.owner?.trim().toLowerCase() === claim.reviewedBy?.trim().toLowerCase()) {
      errors.push(`${claim.id}: owner and reviewer must be independent`);
    }
    // Keep the unreviewed sentinel and its status locked together in both directions, so a
    // claim can neither claim review it never had nor hide a real review behind the sentinel.
    if (claim.status === "SELF_ASSESSED_UNREVIEWED" && claim.reviewedBy !== UNREVIEWED_REVIEWER) {
      errors.push(`${claim.id}: SELF_ASSESSED_UNREVIEWED requires reviewedBy to be exactly "${UNREVIEWED_REVIEWER}"`);
    }
    if (claim.status !== "SELF_ASSESSED_UNREVIEWED" && claim.reviewedBy === UNREVIEWED_REVIEWER) {
      errors.push(`${claim.id}: the unreviewed sentinel is only valid with status SELF_ASSESSED_UNREVIEWED`);
    }
    if (!Array.isArray(claim.limitations) || !claim.limitations.length || claim.limitations.some(v => typeof v !== "string" || v.length < 5)) {
      errors.push(`${claim.id}: limitations require non-empty strings`);
    }

    const reviewedAt = parseRealDate(claim.reviewedAt);
    const expiresAt = parseRealDate(claim.expiresAt);
    if (!reviewedAt) errors.push(`${claim.id}: reviewedAt is not a real date`);
    if (!expiresAt) errors.push(`${claim.id}: expiresAt is not a real date`);
    if (reviewedAt && generatedAt && reviewedAt > generatedAt) errors.push(`${claim.id}: reviewedAt is after registry generatedAt`);
    if (reviewedAt && expiresAt) {
      const horizon = (expiresAt - reviewedAt) / 86_400_000;
      if (reviewedAt > expiresAt) errors.push(`${claim.id}: reviewedAt is after expiresAt`);
      if (horizon > MAX_REVIEW_DAYS) errors.push(`${claim.id}: review horizon exceeds ${MAX_REVIEW_DAYS} days`);
    }
    if (expiresAt && expiresAt < today && claim.status !== "FALSE_RETIRED") errors.push(`${claim.id}: review expired on ${claim.expiresAt}`);

    const statementFile = resolveRepositoryFile(repo, claim.path, `${claim.id}.path`, errors);
    if (statementFile) {
      const occurrences = fs.readFileSync(statementFile, "utf8").split(claim.statement).length - 1;
      if (occurrences !== 1) errors.push(`${claim.id}: statement must occur exactly once in ${claim.path}; found ${occurrences}`);
    }

    let hasValidTestEvidence = false;
    if (!Array.isArray(claim.evidence) || !claim.evidence.length) {
      errors.push(`${claim.id}: evidence is required`);
    } else {
      for (const [evidenceIndex, evidence] of claim.evidence.entries()) {
        const evidenceLocation = `${claim.id}.evidence[${evidenceIndex}]`;
        if (!exactKeys(evidence, evidenceAllowed, ["type", "reference"], evidenceLocation, errors)) continue;
        if (!EVIDENCE_TYPES.has(evidence.type)) errors.push(`${evidenceLocation}: invalid type`);
        if (evidence.type === "OFFICIAL") {
          try {
            const url = new URL(evidence.reference);
            if (url.protocol !== "https:") throw new Error();
          } catch {
            errors.push(`${evidenceLocation}: OFFICIAL evidence requires a valid HTTPS URL`);
          }
          if (evidence.sha256 || evidence.ciCommandId) errors.push(`${evidenceLocation}: OFFICIAL evidence cannot use repository hash/command fields`);
        } else {
          const file = resolveRepositoryFile(repo, evidence.reference, evidenceLocation, errors);
          if (!HASH.test(evidence.sha256 ?? "")) errors.push(`${evidenceLocation}: pinned SHA-256 is required`);
          else if (file && sha256(fs.readFileSync(file)) !== evidence.sha256) errors.push(`${evidenceLocation}: evidence SHA-256 mismatch`);
          if (evidence.type === "TEST") {
            if (!Object.hasOwn(evidenceCommands, evidence.ciCommandId ?? "")) errors.push(`${evidenceLocation}: unknown ciCommandId`);
            else if (HASH.test(evidence.sha256 ?? "") && file && sha256(fs.readFileSync(file)) === evidence.sha256) {
              hasValidTestEvidence = true;
            }
          } else if (evidence.ciCommandId) {
            errors.push(`${evidenceLocation}: ciCommandId is only valid for TEST evidence`);
          }
          if (evidence.type === "DECISION" && !evidence.reference.startsWith("docs/decisions/")) {
            errors.push(`${evidenceLocation}: DECISION evidence must be under docs/decisions/`);
          }
        }
      }
    }
    if (claim.status === "VERIFIED_TECHNICAL" && !hasValidTestEvidence) {
      errors.push(`${claim.id}: VERIFIED_TECHNICAL requires valid pinned TEST evidence with a closed CI command`);
    }

    if (claim.category === "LEGAL" || claim.category === "REGULATORY") {
      if (claim.status === "VERIFIED_TECHNICAL" || claim.status === "CONDITIONAL") {
        errors.push(`${claim.id}: legal/regulatory claims cannot use a technical/conditional approval status`);
      }
      if (claim.status === "EXTERNALLY_APPROVED") {
        const review = claim.externalReview;
        if (!review || !exactKeys(review,
          ["reviewerName", "reviewerOrganization", "reviewerQualification", "outcome", "decisionDate", "validUntil", "jurisdictions", "scope", "decisionEvidence"],
          ["reviewerName", "reviewerOrganization", "reviewerQualification", "outcome", "decisionDate", "validUntil", "jurisdictions", "scope", "decisionEvidence"],
          `${claim.id}.externalReview`, errors)) {
          errors.push(`${claim.id}: externally approved claim requires externalReview`);
        } else {
          if (review.reviewerName.length < 5 || review.reviewerOrganization.length < 3
            || review.reviewerQualification.length < 10 || review.scope.length < 20 || review.outcome !== "APPROVED") {
            errors.push(`${claim.id}: invalid external reviewer/outcome`);
          }
          const decisionDate = parseRealDate(review.decisionDate);
          const validUntil = parseRealDate(review.validUntil);
          if (!decisionDate || !validUntil || decisionDate > validUntil || decisionDate > today || validUntil < today) {
            errors.push(`${claim.id}: invalid or expired external review dates`);
          }
          if (!Array.isArray(review.jurisdictions) || !review.jurisdictions.length
            || review.jurisdictions.some(value => typeof value !== "string" || value.trim().length < 2)) {
            errors.push(`${claim.id}: external approval requires explicit jurisdictions`);
          }
          if (!Array.isArray(review.decisionEvidence) || !review.decisionEvidence.length
            || review.decisionEvidence.some(value => {
              try { return new URL(value).protocol !== "https:"; } catch { return true; }
            })) errors.push(`${claim.id}: external approval requires valid HTTPS decision evidence`);
        }
      } else if (claim.externalReview) {
        errors.push(`${claim.id}: externalReview is only valid for EXTERNALLY_APPROVED`);
      }
    } else if (claim.externalReview) {
      errors.push(`${claim.id}: externalReview is reserved for legal/regulatory claims`);
    }
  }

  let history = { schemaVersion: 1, entries: [] };
  const historyFile = resolveRepositoryFile(repo, registry.historyPath, "registry.historyPath", errors);
  if (historyFile) {
    history = JSON.parse(fs.readFileSync(historyFile, "utf8"));
    const historyAllowed = ["schemaVersion", "entries"];
    exactKeys(history, historyAllowed, historyAllowed, "history", errors);
    if (history.schemaVersion !== 1 || !Array.isArray(history.entries)) errors.push("history: invalid schemaVersion or entries");
    const historyIds = new Set();
    for (const entry of history.entries ?? []) {
      exactKeys(entry, ["id", "firstRegisteredAt"], ["id", "firstRegisteredAt"], "history.entry", errors);
      if (!CLAIM_ID.test(entry.id ?? "") || historyIds.has(entry.id)) errors.push(`history: invalid/duplicate id ${entry.id}`);
      historyIds.add(entry.id);
      if (!parseRealDate(entry.firstRegisteredAt)) errors.push(`history ${entry.id}: invalid firstRegisteredAt`);
    }
    for (const id of seenIds) if (!historyIds.has(id)) errors.push(`${id}: missing immutable history entry`);
    for (const id of historyIds) if (!seenIds.has(id)) errors.push(`${id}: claim was deleted; retain it as FALSE_RETIRED tombstone`);
  }

  const suppressions = [];
  const suppressionsFile = resolveRepositoryFile(repo, registry.suppressionsPath, "registry.suppressionsPath", errors);
  if (suppressionsFile) {
    const document = JSON.parse(fs.readFileSync(suppressionsFile, "utf8"));
    exactKeys(document, ["schemaVersion", "entries"], ["schemaVersion", "entries"], "suppressions", errors);
    if (document.schemaVersion !== 1 || !Array.isArray(document.entries)) errors.push("suppressions: invalid schemaVersion or entries");
    const ids = new Set();
    for (const [index, suppression] of (document.entries ?? []).entries()) {
      const location = `suppressions.entries[${index}]`;
      const allowed = [
        "id", "path", "statement", "textSha256", "sourceSha256", "reason", "correctiveReference",
        "correctiveSha256", "owner", "reviewedBy", "reviewedAt", "expiresAt"
      ];
      if (!exactKeys(suppression, allowed, allowed, location, errors)) continue;
      if (!/^RW-SUP-[0-9]{4}$/.test(suppression.id ?? "") || ids.has(suppression.id)) errors.push(`${location}: invalid/duplicate id`);
      ids.add(suppression.id);
      const approved = APPROVED_SUPPRESSIONS[suppression.id];
      if (!approved) {
        errors.push(`${suppression.id}: suppression is not in the closed schema-version-1 allowlist`);
      } else {
        for (const [field, expected] of Object.entries(approved)) {
          if (suppression[field] !== expected) errors.push(`${suppression.id}: ${field} differs from the approved historical exception`);
        }
      }
      if (!/^backend\/src\/main\/resources\/db\/migration\/V[0-9]+__[^/]+\.sql$/.test(suppression.path ?? "")) {
        errors.push(`${suppression.id}: suppressions are restricted to immutable Flyway migrations`);
      }
      if (sha256(suppression.statement ?? "") !== suppression.textSha256) errors.push(`${suppression.id}: statement SHA-256 mismatch`);
      const source = resolveRepositoryFile(repo, suppression.path, `${suppression.id}.path`, errors);
      if (source) {
        const occurrences = fs.readFileSync(source, "utf8").split(suppression.statement).length - 1;
        if (occurrences !== 1) errors.push(`${suppression.id}: suppressed statement must occur exactly once; found ${occurrences}`);
        if (!HASH.test(suppression.sourceSha256 ?? "") || sha256(fs.readFileSync(source)) !== suppression.sourceSha256) {
          errors.push(`${suppression.id}: immutable source file SHA-256 mismatch`);
        }
      }
      if (typeof suppression.reason !== "string" || suppression.reason.length < 40) errors.push(`${suppression.id}: reason is too short`);
      for (const field of ["owner", "reviewedBy"]) if (typeof suppression[field] !== "string" || suppression[field].length < 5) errors.push(`${suppression.id}: invalid ${field}`);
      if (suppression.owner?.trim().toLowerCase() === suppression.reviewedBy?.trim().toLowerCase()) {
        errors.push(`${suppression.id}: owner and reviewer must be independent`);
      }
      const reviewed = parseRealDate(suppression.reviewedAt);
      const expires = parseRealDate(suppression.expiresAt);
      if (!reviewed || !expires || reviewed > expires || reviewed > today || (generatedAt && reviewed > generatedAt)
        || (expires - reviewed) / 86_400_000 > MAX_REVIEW_DAYS || expires < today) {
        errors.push(`${suppression.id}: invalid or expired review interval`);
      }
      const correction = resolveRepositoryFile(repo, suppression.correctiveReference, `${suppression.id}.correctiveReference`, errors);
      if (!HASH.test(suppression.correctiveSha256 ?? "") || (correction && sha256(fs.readFileSync(correction)) !== suppression.correctiveSha256)) {
        errors.push(`${suppression.id}: corrective evidence SHA-256 mismatch`);
      }
      const sourceVersion = suppression.path?.match(/\/V([0-9]+)__/i)?.[1];
      const correctionVersion = suppression.correctiveReference?.match(/\/V([0-9]+)__/i)?.[1];
      if (!sourceVersion || !correctionVersion || Number(correctionVersion) <= Number(sourceVersion)) {
        errors.push(`${suppression.id}: corrective evidence must be a later Flyway migration`);
      }
      suppressions.push(suppression);
    }
    for (const id of Object.keys(APPROVED_SUPPRESSIONS)) {
      if (!ids.has(id)) errors.push(`${id}: approved historical suppression must not be deleted`);
    }
  }

  for (const file of allScannableFiles(repo, errors)) {
    const relative = path.relative(repo, file).split(path.sep).join("/");
    if (scannerFixtureFiles.has(relative)) continue;
    const stat = fs.statSync(file);
    if (stat.size > MAX_SCANNABLE_BYTES) {
      errors.push(`${relative}: scannable text exceeds ${MAX_SCANNABLE_BYTES} bytes`);
      continue;
    }
    const content = fs.readFileSync(file, "utf8");
    if (content.includes("\u0000")) {
      errors.push(`${relative}: scannable text contains a NUL byte`);
      continue;
    }
    errors.push(...scanText(relative, content, registry.claims ?? [], suppressions, registry.externalApprovalEnabled));
  }

  const workflow = fs.readFileSync(path.join(repo, ".github/workflows/claims.yml"), "utf8");
  errors.push(...workflowEvidenceErrors(workflow));

  return { errors, registry, history };
}

function readJsonFromGitBase(repo, ref, relative) {
  if (!/^[A-Za-z0-9._/-]+$/.test(ref) || ref.startsWith("-") || ref.includes("..") || ref.includes(":")) {
    throw new Error(`Unsafe Git base reference: ${ref}`);
  }
  const result = spawnSync("git", ["show", `${ref}:${relative}`], {
    cwd: repo,
    encoding: "utf8",
    shell: false
  });
  if (result.status !== 0) return null;
  return JSON.parse(result.stdout);
}

function requireGitCommit(repo, ref) {
  if (!/^[A-Za-z0-9._/-]+$/.test(ref) || ref.startsWith("-") || ref.includes("..") || ref.includes(":")) {
    throw new Error(`Unsafe Git base reference: ${ref}`);
  }
  const result = spawnSync("git", ["cat-file", "-e", `${ref}^{commit}`], {
    cwd: repo,
    encoding: "utf8",
    shell: false
  });
  if (result.status !== 0) throw new Error(`Git base reference is unavailable: ${ref}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === scriptPath) {
  const baseFlag = process.argv.indexOf("--base-ref");
  const baseRef = baseFlag >= 0 ? process.argv[baseFlag + 1] : null;
  const { errors, registry, history } = verifyClaims();
  if (baseFlag >= 0 && !baseRef) errors.push("--base-ref requires a Git commit or ref");
  if (baseRef) {
    try {
      requireGitCommit(defaultRepo, baseRef);
      const baseRegistry = readJsonFromGitBase(defaultRepo, baseRef, "docs/claims/registry.json");
      const baseHistory = readJsonFromGitBase(defaultRepo, baseRef, "docs/claims/registry.history.json");
      if (baseRegistry && baseHistory) errors.push(...compareHistoricalState(registry, history, baseRegistry, baseHistory));
      else if (baseRegistry || baseHistory) errors.push("base revision contains only part of the claims history control");
    } catch (error) {
      errors.push(`unable to compare claims base revision: ${error.message}`);
    }
  }
  if (errors.length) {
    process.stderr.write(`Claims verification failed (${errors.length}):\n- ${errors.join("\n- ")}\n`);
    process.exitCode = 1;
  } else {
    // "registered", not "controlled": only ACTIVE_EXEMPT_STATUSES claims can exempt scanner
    // findings, and SELF_ASSESSED_UNREVIEWED deliberately cannot.
    process.stdout.write(`Claims verification passed: ${registry.claims.length} registered claims; repository-wide high-risk scan clean.\n`);
  }
}
