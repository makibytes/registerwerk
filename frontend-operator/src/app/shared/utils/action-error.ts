import { MatSnackBar } from '@angular/material/snack-bar';

/** Server message of an HTTP error (`ErrorResponse.message`), else `fallback`. */
export function errorMessage(error: unknown, fallback: string): string {
  const message = (error as { error?: { message?: unknown } } | null)?.error?.message;
  return typeof message === 'string' && message.trim() ? message : fallback;
}

/**
 * Shows the server's error message (or `fallback`) in a snackbar. `suffix` is appended for flows where the
 * failure also burned something the user must redo, e.g. a single-use approver token.
 */
export function showActionError(snackBar: MatSnackBar, fallback: string, error: unknown, suffix = ''): void {
  snackBar.open(`${errorMessage(error, fallback)}${suffix ? ' ' + suffix : ''}`, 'Dismiss', { duration: 8000 });
}

/** Appended to a failed dual-control call: the approver token is spent whether or not the call succeeds. */
export const APPROVER_TOKEN_CONSUMED = 'The approver token was consumed - obtain a new one before retrying.';
