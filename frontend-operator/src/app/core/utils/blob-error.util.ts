/**
 * Server message of a failed `responseType: 'blob'` request. Angular delivers the error body of such a request as a
 * Blob, so `err.error.message` is undefined and callers showed a generic text; this reads the JSON `ErrorResponse`
 * (e.g. 409 "the register is being reconciled with the chain") and falls back to `fallback` for anything else.
 */
export async function blobErrorMessage(err: unknown, fallback: string): Promise<string> {
  try {
    const body = (err as { error?: unknown } | null)?.error;
    const parsed: unknown = body instanceof Blob ? JSON.parse(await body.text()) : body;
    const message = (parsed as { message?: unknown } | null)?.message;
    return typeof message === 'string' && message.trim() ? message : fallback;
  } catch {
    return fallback;
  }
}
