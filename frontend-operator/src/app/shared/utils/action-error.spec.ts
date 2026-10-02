import { describe, expect, it, vi } from 'vitest';
import { APPROVER_TOKEN_CONSUMED, errorMessage, showActionError } from './action-error';

describe('showActionError', () => {
  it('shows the server message of a 409/400 and appends the token hint', () => {
    const snackBar = { open: vi.fn() };
    showActionError(snackBar as never, 'Failed.', { status: 409, error: { message: 'Account is frozen' } }, APPROVER_TOKEN_CONSUMED);
    expect(snackBar.open.mock.calls[0][0]).toBe(`Account is frozen ${APPROVER_TOKEN_CONSUMED}`);
  });

  it('falls back when the body has no message', () => {
    expect(errorMessage({ error: null }, 'Failed.')).toBe('Failed.');
    expect(errorMessage(undefined, 'Failed.')).toBe('Failed.');
    expect(errorMessage({ error: { message: '  ' } }, 'Failed.')).toBe('Failed.');
  });
});
