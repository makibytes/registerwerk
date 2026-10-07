import { HttpErrorResponse, HttpEvent, HttpInterceptorFn } from '@angular/common/http';
import { tap } from 'rxjs';

/**
 * P4B-7: money-/state-moving admin and issuer endpoints require an `Idempotency-Key` header (the backend
 * answers 400 IDEMPOTENCY_KEY_REQUIRED without it). Mirror of the backend's `@RequiresIdempotencyKey`
 * handlers; keep in sync with `docs/platform/api.md (Idempotency-Key and amounts)`.
 */
export const IDEMPOTENT_ENDPOINTS: readonly RegExp[] = [
  /\/assets\/[^/]+\/deployments\/[^/]+\/(admin|issuer|solana-admin)\//,
  /\/deployments\/[^/]+\/(slots|tokens|holders)(\/|$)/,
  /\/deployments\/[^/]+\/(nav-strike|dealing-cutoff|vault-requests\/[^/]+\/(fulfill|force-cancel))$/,
  /\/payment-rails(\/|$)/,
  /\/lending\/markets(\/reverify|\/[^/]+\/(reconcile-collateral|borrow-paused))?$/,
  /\/assets\/[^/]+\/erc3643\/[^/]+\/(forced-transfer|forced-approve|freeze|unfreeze|freeze-partial|unfreeze-partial|pause|unpause|force-burn|batch-forced-transfer|batch-mint|batch-burn)$/,
  /\/admin\/wallets\/import-(raw|keystore)$/,
  /\/register-transfers\/[^/]+\/(onchain-handover|complete)$/,
  /\/assets\/[^/]+\/redeem$/,
];

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/**
 * One key per USER ACTION, reused when the same request is retried: the key is remembered per request
 * fingerprint (method + url + body) and forgotten only once the server gave a definitive answer (2xx, or a
 * 4xx that means "not executed"). A timeout / network error / 5xx keeps it, so the user's next click - or an
 * automatic retry - resends the SAME key and the backend can never execute the action twice.
 */
const pending = new Map<string, string>();

function fingerprint(method: string, url: string, body: unknown): string {
  let b = '';
  if (body instanceof FormData) {
    b = Array.from(body.entries())
      .map(([k, v]) => `${k}=${typeof v === 'string' ? v : `${v.name}:${v.size}`}`).join('&');
  } else if (body != null) {
    b = JSON.stringify(body);
  }
  return `${method} ${url} ${b}`;
}

function newKey(): string {
  return globalThis.crypto?.randomUUID?.()
    ?? `${Date.now().toString(16)}-${Math.random().toString(16).slice(2)}-${Math.random().toString(16).slice(2)}`;
}

export const idempotencyInterceptor: HttpInterceptorFn = (req, next) => {
  if (!MUTATING.has(req.method) || req.headers.has('Idempotency-Key')
      || !IDEMPOTENT_ENDPOINTS.some(p => p.test(req.url.split('?')[0]))) {
    return next(req);
  }
  const fp = fingerprint(req.method, req.url, req.body);
  let key = pending.get(fp);
  if (!key) {
    key = newKey();
    pending.set(fp, key);
  }
  return next(req.clone({ setHeaders: { 'Idempotency-Key': key } })).pipe(
    tap({
      next: (event: HttpEvent<unknown>) => {
        if ('status' in event && event.status >= 200 && event.status < 300) {
          pending.delete(fp);
        }
      },
      error: (err: unknown) => {
        // 4xx (except 408/409/429 = "maybe still running / try again") means nothing was executed.
        // Keeping the key on those is safe: the backend releases its record for every 4xx/5xx from the
        // handler (IdempotencyService.complete), so the identical retry re-executes; only the filter's own
        // "already in progress" 409 makes the same key wait.
        if (err instanceof HttpErrorResponse && err.status >= 400 && err.status < 500
            && ![408, 409, 429].includes(err.status)) {
          pending.delete(fp);
        }
      },
    }),
  );
};

/** Test hook. */
export function resetIdempotencyKeys(): void {
  pending.clear();
}
