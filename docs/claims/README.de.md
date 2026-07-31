# Claims control

Registerwerk records review-sensitive product, legal, security, privacy, settlement, and
operational statements in `registry.json`. The documentation/marketing owner proposes wording;
the relevant domain owner supplies evidence; and a repository owner plus the IT review board must
approve changes. The current schema deliberately disables `EXTERNALLY_APPROVED`; legal or regulatory
conclusions remain prohibited until a separately reviewed governance change configures qualified
external approvers, jurisdiction and scope, validity dates, and decision evidence. `CODEOWNERS` currently routes this control to
the repository owner; replace or augment that entry when formal compliance/legal and
platform-security teams are configured.

The register and its CI scanner are engineering governance controls. They do not infer legal
compliance or replace qualified counsel, an auditor, an authority decision, deployment evidence,
or an instrument-specific approval.

## Status meanings

| Status | Meaning |
|---|---|
| `SELF_ASSESSED_UNREVIEWED` | Written by an automated contributor with no human or panel review. `reviewedBy` must be exactly the unreviewed sentinel, and the claim never exempts wording from the scanner. This is the only status an automated contributor may assign |
| `VERIFIED_TECHNICAL` | Narrow technical behavior reproduced by pinned repository evidence and a CI command; never valid for legal/regulatory conclusions. Requires a named independent reviewer and may only be set by that human review |
| `CONDITIONAL` | Narrow non-legal result whose explicitly listed conditions and limitations remain material |
| `PROTOTYPE` | Not approved for an affirmative high-risk assertion and never exempts wording from the scanner |
| `BLOCKED` | Decision or evidence is missing; positive wording remains prohibited |
| `EXTERNAL_REVIEW_REQUIRED` | Legal/regulatory review is absent; positive wording remains prohibited |
| `EXTERNALLY_APPROVED` | Reserved and disabled in schema version 1; it cannot currently authorize wording |
| `FALSE_RETIRED` | Permanent ID and explicit corrective-statement tombstone; it never exempts a positive assertion |

## Binding and evidence

`textSha256` hashes the exact UTF-8 statement text without a trailing newline. `recordSha256`
hashes the canonical, key-sorted claim record excluding `recordSha256` itself, thereby binding its
path, status, scope, owner, dates, evidence, and limitations. Repository/test/decision evidence is
restricted to a regular, non-escaping, non-symlinked repository file and pinned by its complete
file SHA-256. A `VERIFIED_TECHNICAL` claim must include at least one valid pinned test file and its
closed CI command. Official evidence must be a valid HTTPS URL. The claims workflow executes every
closed command named by test evidence.

The repository-wide scanner examines maintained documentation, examples, source, contracts,
configuration, migrations, scripts, and manifests while excluding vendored/generated/build
directories. A high-risk phrase is allowed only when its containing sentence directly negates or
qualifies it, or when an eligible exact controlled statement covers it. There is no inline
suppression syntax. Schema version 1 recognizes only the explicitly allowlisted `RW-SUP-0001`
historical V1 Flyway comment and its later V12 correction; arbitrary migrations cannot be added as
exceptions. Its exact text plus source and corrective file hashes are pinned, and its owner, independent reviewer,
review date, and expiry are checked. Current documentation and code cannot use that exception—add
a narrow controlled statement or correct the wording.

## Add, renew, correct, or retire

1. Give a new claim the next never-reused ID and add that ID to `registry.history.json`.
2. State one exact, scoped assertion in a maintained document. Do not register broad marketing
   language or use a blocked/prototype status to preserve positive wording.
3. Pin every supporting file, choose the relevant CI evidence command, list limitations, and use
   a maximum 183-day UTC review horizon. `reviewedAt` cannot follow `generatedAt` or `expiresAt`.
4. Compute the text and canonical-record hashes, run both verifier commands, then run the affected
   evidence command. Obtain the approvals described above.
5. Renewal repeats evidence execution/review, advances `reviewedAt`, `expiresAt`, registry revision
   and record hash. A changed statement gets a new review and text hash.
6. Never delete an ID. Retire it in place as `FALSE_RETIRED`, preserve its history entry, replace
   the registered statement with an explicit negative correction that remains in the referenced
   file, explain the correction in limitations, and re-hash the record.

For a schema change, first add adversarial fixtures, update the closed validator and JSON Schema,
then increment `schemaVersion` in the schema, registry, and history as one reviewed migration. The
dependency-free validator deliberately implements a closed versioned contract; unknown registry
fields and unsupported versions fail instead of being silently ignored.

Run locally:

```bash
node scripts/verify-claims.test.mjs
node scripts/verify-claims.mjs
node scripts/run-claim-evidence.mjs <command-id>
```

Current controlled statements:

- In the reviewed EVM contract version, an ERC-3525 address-form value transfer decreases the source exactly once and creates an equal destination value; this technical result does not establish indexed or legal-register reconciliation.
- The regulatory-reporting module produces only opt-in, non-production DRAFT_UNVALIDATED prototype documents; a successful SFTP write is transport evidence, not authority filing or acceptance.
- The EVM subgraph gate validates configured ABI handler signatures and compiles every mapping; it does not prove deployed bytecode identity, chain finality, replay correctness, or legal effect.
