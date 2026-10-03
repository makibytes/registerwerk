import { canonicalJson, parseStrictJson } from './canonical-json';

/**
 * The request a second approver is asked to approve. The initiator copies it from the step-up dialog
 * ("Copy approval request"); the approver pastes it into /approvals in THEIR OWN session, reviews the
 * summary and mints a token bound to exactly this request. No credential is ever part of the block.
 */
export interface ApprovalRequest {
  /** The exact `@RequiresStepUp(reason=...)` value. */
  action: string;
  /** `"METHOD /api/v1/path[?query]"`. */
  target: string;
  /** JSON body of the request (body-bound reasons only). */
  targetBody?: unknown;
}

const TARGET_PATTERN = /^(GET|POST|PUT|PATCH|DELETE) \/api\/v1\/\S*$/;

/**
 * Serialises the request as the JSON block the initiator hands to the approver. The body is written in
 * canonical form (see `canonical-json.ts`) - the very text the backend hashes - so what the approver pastes,
 * reviews and binds is what the server will compute from the live request.
 */
export function buildApprovalRequestBlock(request: ApprovalRequest): string {
  let block = `{\n  "action": ${JSON.stringify(request.action)},\n  "target": ${JSON.stringify(request.target)}`;
  if (request.targetBody !== undefined) block += `,\n  "targetBody": ${canonicalJson(request.targetBody)}`;
  return block + '\n}';
}

export type ParsedApprovalRequest = { request: ApprovalRequest; error?: undefined } | { request?: undefined; error: string };

/** Names of the query parameters that occur more than once (percent-decoded, as the server sees them). */
function repeatedQueryParameters(target: string): string[] {
  const q = target.indexOf('?');
  if (q < 0) return [];
  const seen = new Set<string>();
  const repeated = new Set<string>();
  for (const part of target.slice(q + 1).split('&')) {
    if (!part) continue;
    const eq = part.indexOf('=');
    let name = eq < 0 ? part : part.slice(0, eq);
    try {
      name = decodeURIComponent(name.replace(/\+/g, ' '));
    } catch {
      // keep the raw name
    }
    (seen.has(name) ? repeated : seen).add(name);
  }
  return [...repeated];
}

/**
 * Parses and validates a pasted approval request block. Strict like the backend: a repeated key, a number
 * the browser cannot hold exactly, or a repeated query parameter is refused instead of silently resolved.
 */
export function parseApprovalRequestBlock(text: string): ParsedApprovalRequest {
  let raw: unknown;
  try {
    raw = parseStrictJson(text);
  } catch (e) {
    return { error: `This is not a valid approval request block: ${(e as Error).message}` };
  }
  if (!raw || typeof raw !== 'object') return { error: 'This is not a valid approval request block.' };
  const { action, target, targetBody } = raw as Record<string, unknown>;
  if (typeof action !== 'string' || !action.trim()) return { error: 'The block has no action.' };
  if (typeof target !== 'string' || !TARGET_PATTERN.test(target.trim())) {
    return { error: 'The block target must look like "POST /api/v1/path".' };
  }
  const repeated = repeatedQueryParameters(target.trim());
  if (repeated.length > 0) {
    return { error: `The query parameter "${repeated[0]}" is repeated in the target; an approval cannot be bound to a repeated parameter.` };
  }
  return { request: { action: action.trim(), target: target.trim(), ...(targetBody !== undefined ? { targetBody } : {}) } };
}

/** Human-readable review lines for the approver. */
export function describeApprovalRequest(request: ApprovalRequest): { label: string; value: string }[] {
  const [method, ...path] = request.target.split(' ');
  const lines = [
    { label: 'Action', value: request.action },
    { label: 'Method', value: method },
    { label: 'Path', value: path.join(' ') },
  ];
  // The body is shown in canonical form: exactly the text the backend hashes, so reviewing it is reviewing the binding.
  if (request.targetBody !== undefined) lines.push({ label: 'Body', value: canonicalJson(request.targetBody) });
  return lines;
}
