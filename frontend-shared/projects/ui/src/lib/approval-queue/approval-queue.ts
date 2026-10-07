import type { HttpClient } from '@angular/common/http';
import { EMPTY, Observable, Subscription, concat, of, throwError, timer } from 'rxjs';
import { catchError, exhaustMap, map, switchMap, takeWhile, tap } from 'rxjs/operators';

/*
 * In-app approval queue (T8-02) - the framework-light half shared by the operator and the customer portal:
 * the API client, the "request -> wait -> claim" flow and a small session object a component can hold.
 * Deliberately no Angular DI in here (apps wrap the client in a one-line @Injectable), so the code cannot
 * depend on which copy of @angular/core resolves the shared path.
 *
 * Flow: the initiator files ONE concrete request (action + the exact method/path/query/body of the call that
 * needs a second approver), an approver other than the initiator approves it with their own fresh TOTP code,
 * and the initiator then CLAIMS a single-use approver token bound to exactly that request and sends it in
 * `X-Dual-Control-Token` on the real call. The claim is made as late as possible (when the initiator confirms),
 * because the token is only valid for the dual-control window (300 s) from the claim.
 */

export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED' | 'CLAIMED';

export interface ApprovalRequestView {
  id: string;
  requesterUserId: string;
  requesterEmail: string | null;
  action: string;
  method: string;
  path: string;
  query: string | null;
  /** `''` = the request has no body, `null` = the body is not part of this approval. */
  canonicalBody: string | null;
  targetDigest: string;
  status: ApprovalStatus;
  approverUserId: string | null;
  approverEmail: string | null;
  createdAt: string;
  expiresAt: string;
  decidedAt: string | null;
  decisionNote: string | null;
  claimedAt: string | null;
}

export interface ApprovalPage<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface CreateApprovalRequest {
  /** The target endpoint's `@RequiresStepUp` reason. */
  action: string;
  method: string;
  /** Exactly as sent: `/api/v1/...`, no host, no query. */
  path: string;
  /** Raw query string without `?`, exactly as sent. */
  query?: string;
  /** JSON body exactly as sent; omit when the call has none. */
  body?: unknown;
}

export interface ApprovalClaim {
  requestId: string;
  approvalToken: string;
  expiresAt: string;
  action: string;
  /** `"METHOD /path[?query]"`. */
  target: string;
  /** `X-Dual-Control-Token`. */
  headerName: string;
}

/** The three calls the flow needs; the HTTP client below implements them, tests fake them. */
export interface ApprovalQueueApi {
  create(request: CreateApprovalRequest): Observable<ApprovalRequestView>;
  get(id: string): Observable<ApprovalRequestView>;
  claim(id: string): Observable<ApprovalClaim>;
  cancel(id: string): Observable<ApprovalRequestView>;
}

/** `/api/v1/approvals` over an app's own HttpClient (its interceptors attach the session). */
export class ApprovalQueueClient implements ApprovalQueueApi {
  constructor(private readonly http: HttpClient, private readonly baseUrl: string) {}

  create(request: CreateApprovalRequest): Observable<ApprovalRequestView> {
    return this.http.post<ApprovalRequestView>(this.baseUrl, request);
  }
  get(id: string): Observable<ApprovalRequestView> {
    return this.http.get<ApprovalRequestView>(`${this.baseUrl}/${id}`);
  }
  /** Approver inbox: other people's open requests, oldest first. */
  pending(page = 0, size = 20): Observable<ApprovalPage<ApprovalRequestView>> {
    return this.http.get<ApprovalPage<ApprovalRequestView>>(`${this.baseUrl}/pending`, { params: { page, size } });
  }
  pendingCount(): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${this.baseUrl}/pending/count`);
  }
  /** The caller's own requests, newest first, every status. */
  mine(page = 0, size = 20): Observable<ApprovalPage<ApprovalRequestView>> {
    return this.http.get<ApprovalPage<ApprovalRequestView>>(`${this.baseUrl}/mine`, { params: { page, size } });
  }
  approve(id: string, code: string, note?: string): Observable<ApprovalRequestView> {
    return this.http.post<ApprovalRequestView>(`${this.baseUrl}/${id}/approve`, { code, note: note || undefined });
  }
  reject(id: string, note?: string): Observable<ApprovalRequestView> {
    return this.http.post<ApprovalRequestView>(`${this.baseUrl}/${id}/reject`, { note: note || undefined });
  }
  cancel(id: string): Observable<ApprovalRequestView> {
    return this.http.post<ApprovalRequestView>(`${this.baseUrl}/${id}/cancel`, {});
  }
  claim(id: string): Observable<ApprovalClaim> {
    return this.http.post<ApprovalClaim>(`${this.baseUrl}/${id}/claim`, {});
  }
}

// ── Flow ─────────────────────────────────────────────────────────────────────────────────────────────────

export type ApprovalFailureCode = 'REJECTED' | 'EXPIRED' | 'CANCELLED' | 'ALREADY_CLAIMED' | 'TIMEOUT' | 'CLAIM_REFUSED';

/** A request that ended without an approver token (as opposed to an HTTP error, which is passed through). */
export class ApprovalFlowError extends Error {
  constructor(readonly code: ApprovalFailureCode, message: string, readonly view?: ApprovalRequestView) {
    super(message);
    this.name = 'ApprovalFlowError';
  }
}

export interface ApprovalWaitOptions {
  /** How often `GET /{id}` is polled. Default 4 s. */
  pollIntervalMs?: number;
  /** Give up waiting after this long (the server's own request ttl is 15 min). Default 15 min. */
  timeoutMs?: number;
}

export const APPROVAL_POLL_INTERVAL_MS = 4_000;
export const APPROVAL_WAIT_TIMEOUT_MS = 15 * 60_000;
/** Consecutive transient poll failures (network / 5xx) tolerated before the wait fails. */
const MAX_TRANSIENT_POLL_FAILURES = 3;

export type ApprovalFlowEvent =
  | { kind: 'created'; view: ApprovalRequestView }
  | { kind: 'waiting'; view: ApprovalRequestView }
  | { kind: 'approved'; view: ApprovalRequestView };

function httpStatus(err: unknown): number | undefined {
  return (err as { status?: number } | null)?.status;
}

function httpMessage(err: unknown): string | undefined {
  const message = (err as { error?: { message?: unknown } } | null)?.error?.message;
  return typeof message === 'string' && message ? message : undefined;
}

function whoDecided(view: ApprovalRequestView): string {
  return view.approverEmail ? ` by ${view.approverEmail}` : '';
}

function failureFor(view: ApprovalRequestView): ApprovalFlowError {
  switch (view.status) {
    case 'REJECTED':
      return new ApprovalFlowError('REJECTED',
        `The request was rejected${whoDecided(view)}${view.decisionNote ? `: ${view.decisionNote}` : '.'}`, view);
    case 'EXPIRED':
      return new ApprovalFlowError('EXPIRED', 'The request expired before it was approved and used. Request approval again.', view);
    case 'CANCELLED':
      return new ApprovalFlowError('CANCELLED', 'The request was cancelled.', view);
    default:
      return new ApprovalFlowError('ALREADY_CLAIMED', 'This approval has already been used. Request approval again.', view);
  }
}

/**
 * Files the request and then polls it until a decision: emits `created`, `waiting` per poll and a final
 * `approved` (then completes). REJECTED / EXPIRED / CANCELLED / already-claimed and a timeout surface as an
 * {@link ApprovalFlowError}; HTTP errors from create / poll pass through (a few transient poll failures are
 * ridden out). Nothing is claimed here - see {@link claimApproval}.
 */
export function awaitApproval(
  api: ApprovalQueueApi, request: CreateApprovalRequest, options: ApprovalWaitOptions = {},
): Observable<ApprovalFlowEvent> {
  const interval = options.pollIntervalMs ?? APPROVAL_POLL_INTERVAL_MS;
  const timeoutMs = options.timeoutMs ?? APPROVAL_WAIT_TIMEOUT_MS;
  return api.create(request).pipe(
    switchMap(created => {
      const deadline = Date.now() + timeoutMs;
      let transientFailures = 0;
      const poll$ = timer(interval, interval).pipe(
        exhaustMap(() => api.get(created.id).pipe(
          tap(() => { transientFailures = 0; }),
          catchError(err => {
            const status = httpStatus(err);
            if ((status === 0 || (status !== undefined && status >= 500)) && ++transientFailures <= MAX_TRANSIENT_POLL_FAILURES) {
              return EMPTY;
            }
            return throwError(() => err);
          }),
        )),
        map((view): ApprovalFlowEvent => {
          if (view.status === 'APPROVED') return { kind: 'approved', view };
          if (view.status !== 'PENDING') throw failureFor(view);
          if (Date.now() > deadline) {
            throw new ApprovalFlowError('TIMEOUT',
              'No decision yet. The request stays open until it expires; you can find it under Approvals, My requests, or request approval again.', view);
          }
          return { kind: 'waiting', view };
        }),
        takeWhile(event => event.kind !== 'approved', true),
      );
      return concat(of<ApprovalFlowEvent>({ kind: 'created', view: created }), poll$);
    }),
  );
}

/**
 * Claims the approver token of an APPROVED request. It is single use: a second claim (or a claim after
 * expiry / cancellation / the approver losing eligibility) is refused by the server with 409 / 403, which
 * surfaces here as a {@link ApprovalFlowError} carrying the server's message.
 */
export function claimApproval(api: ApprovalQueueApi, id: string): Observable<ApprovalClaim> {
  return api.claim(id).pipe(
    catchError(err => {
      const status = httpStatus(err);
      if (status === 409) {
        return throwError(() => new ApprovalFlowError('ALREADY_CLAIMED',
          httpMessage(err) ?? 'This approval can no longer be used (already claimed, expired or cancelled). Request approval again.'));
      }
      if (status === 403) {
        return throwError(() => new ApprovalFlowError('CLAIM_REFUSED',
          httpMessage(err) ?? 'The approval cannot be claimed: the approver is no longer eligible. Request approval again.'));
      }
      return throwError(() => err);
    }),
  );
}

/** Message for any error of the flow, for inline display / snackbars. */
export function approvalErrorMessage(err: unknown, fallback = 'The approval request failed.'): string {
  if (err instanceof ApprovalFlowError) return err.message;
  const status = httpStatus(err);
  const message = httpMessage(err);
  if (message) return message;
  if (status === 0) return 'The server could not be reached.';
  if (status === 403) return 'You are not allowed to do this (the approval queue is not available while impersonating).';
  if (status === 409) return 'Too many open approval requests, or the request is no longer open.';
  return fallback;
}

// ── Session: what a component holds ───────────────────────────────────────────────────────────────────────

export type ApprovalPhase = 'idle' | 'waiting' | 'approved' | 'failed';

/**
 * UI state machine for one approval request: idle -> waiting -> approved | failed. A component holds one,
 * passes its `markForCheck` as `onChange` (zoneless) and calls `destroy()` from ngOnDestroy. The request
 * body is whatever the caller passed to {@link start}; the caller compares its form to `filedFor` to notice
 * an edit after filing (the approval is bound to the filed request, so an edit invalidates it).
 */
export class ApprovalRequestSession {
  phase: ApprovalPhase = 'idle';
  view: ApprovalRequestView | null = null;
  error: string | null = null;
  failureCode: ApprovalFailureCode | 'ERROR' | null = null;
  /** The request as filed (to detect a later edit of the form). */
  filedFor: CreateApprovalRequest | null = null;
  /** Seconds until the open request expires (0 when unknown); refreshed once per second while open. */
  remainingSeconds = 0;

  private watch?: Subscription;
  private ticker?: Subscription;
  private readonly listeners = new Set<() => void>();

  constructor(
    private readonly api: ApprovalQueueApi,
    private readonly onChange: () => void,
    private readonly options: ApprovalWaitOptions = {},
  ) {}

  start(request: CreateApprovalRequest): void {
    this.stop();
    this.phase = 'waiting';
    this.view = null;
    this.error = null;
    this.failureCode = null;
    this.filedFor = request;
    this.remainingSeconds = 0;
    this.notify();
    this.ticker = timer(1000, 1000).subscribe(() => {
      this.remainingSeconds = this.view ? Math.max(0, Math.floor((Date.parse(this.view.expiresAt) - Date.now()) / 1000)) : 0;
      this.notify();
    });
    this.watch = awaitApproval(this.api, request, this.options).subscribe({
      next: event => {
        this.view = event.view;
        this.remainingSeconds = Math.max(0, Math.floor((Date.parse(event.view.expiresAt) - Date.now()) / 1000));
        if (event.kind === 'approved') {
          this.phase = 'approved';
          this.ticker?.unsubscribe();
        }
        this.notify();
      },
      error: err => this.fail(err),
    });
  }

  /** Claims the approver token of the approved request; a refusal moves the session to `failed`. */
  claim(): Observable<ApprovalClaim> {
    const id = this.view?.id;
    if (this.phase !== 'approved' || !id) {
      return throwError(() => new ApprovalFlowError('CLAIM_REFUSED', 'There is no approved request to use.'));
    }
    return claimApproval(this.api, id).pipe(tap({ error: err => this.fail(err) }));
  }

  /** Withdraws the open request (best effort) and returns to idle. */
  cancel(): void {
    const id = this.view?.id;
    const open = this.phase === 'waiting' || this.phase === 'approved';
    this.reset();
    if (id && open) this.api.cancel(id).subscribe({ error: () => undefined });
  }

  reset(): void {
    this.stop();
    this.phase = 'idle';
    this.view = null;
    this.error = null;
    this.failureCode = null;
    this.filedFor = null;
    this.remainingSeconds = 0;
    this.notify();
  }

  /** Extra change listener (an OnPush child that renders this session marks itself dirty here). Returns the unsubscribe. */
  listen(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  destroy(): void {
    this.stop();
    this.listeners.clear();
  }

  private notify(): void {
    this.onChange();
    this.listeners.forEach(listener => listener());
  }

  private fail(err: unknown): void {
    this.stop();
    this.phase = 'failed';
    this.failureCode = err instanceof ApprovalFlowError ? err.code : 'ERROR';
    this.error = approvalErrorMessage(err);
    this.notify();
  }

  private stop(): void {
    this.watch?.unsubscribe();
    this.ticker?.unsubscribe();
    this.watch = undefined;
    this.ticker = undefined;
  }
}

// ── Helpers ──────────────────────────────────────────────────────────────────────────────────────────────

const TARGET_PATTERN = /^(GET|POST|PUT|PATCH|DELETE) (\/[^?\s]*)(?:\?(\S*))?$/;

/** Splits `"METHOD /api/v1/path[?query]"` (the step-up dialog's `target`) into the create-request parts. */
export function parseApprovalTarget(target: string): { method: string; path: string; query?: string } | null {
  const match = TARGET_PATTERN.exec(target.trim());
  if (!match) return null;
  return { method: match[1], path: match[2], ...(match[3] ? { query: match[3] } : {}) };
}

/** `mm:ss` for a remaining time in seconds. */
export function formatCountdown(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/**
 * Indents the canonical body for display WITHOUT parsing it: a parse would round numbers the browser cannot
 * hold exactly, and the approver must review the very text the server binds. Whitespace outside strings is
 * dropped (canonical text has none), so the output is the same document, laid out.
 */
export function prettyCanonicalBody(text: string | null | undefined): string {
  if (!text) return '';
  let out = '';
  let depth = 0;
  let inString = false;
  let escaped = false;
  const newline = () => '\n' + '  '.repeat(depth);
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inString) {
      out += ch;
      if (escaped) escaped = false;
      else if (ch === '\\') escaped = true;
      else if (ch === '"') inString = false;
      continue;
    }
    switch (ch) {
      case '"': inString = true; out += ch; break;
      case '{':
      case '[': {
        const close = ch === '{' ? '}' : ']';
        if (text[i + 1] === close) { out += ch + close; i++; break; }
        depth++;
        out += ch + newline();
        break;
      }
      case '}':
      case ']': depth = Math.max(0, depth - 1); out += newline() + ch; break;
      case ',': out += ',' + newline(); break;
      case ':': out += ': '; break;
      case ' ': case '\n': case '\r': case '\t': break;
      default: out += ch;
    }
  }
  return out;
}
