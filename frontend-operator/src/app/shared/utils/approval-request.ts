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

/** Serialises the request as the JSON block the initiator hands to the approver. */
export function buildApprovalRequestBlock(request: ApprovalRequest): string {
  const block: ApprovalRequest = { action: request.action, target: request.target };
  if (request.targetBody !== undefined) block.targetBody = request.targetBody;
  return JSON.stringify(block, null, 2);
}

export type ParsedApprovalRequest = { request: ApprovalRequest; error?: undefined } | { request?: undefined; error: string };

/** Parses and validates a pasted approval request block. */
export function parseApprovalRequestBlock(text: string): ParsedApprovalRequest {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    return { error: 'This is not a valid approval request block (JSON expected).' };
  }
  if (!raw || typeof raw !== 'object') return { error: 'This is not a valid approval request block.' };
  const { action, target, targetBody } = raw as Record<string, unknown>;
  if (typeof action !== 'string' || !action.trim()) return { error: 'The block has no action.' };
  if (typeof target !== 'string' || !TARGET_PATTERN.test(target.trim())) {
    return { error: 'The block target must look like "POST /api/v1/path".' };
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
  if (request.targetBody !== undefined) lines.push({ label: 'Body', value: JSON.stringify(request.targetBody, null, 2) });
  return lines;
}
