import { ChangeDetectionStrategy, ChangeDetectorRef, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from './auth.service';

/** Why the form cannot be submitted yet, or null. Pure so the rules are unit-testable. */
export function passwordChangeProblem(current: string, next: string, confirm: string): string | null {
  if (!current || !next || !confirm) return null; // nothing typed yet: no message, just a disabled button
  if (next.length < 8) return 'The new password must have at least 8 characters.';
  if (next !== confirm) return 'The two new passwords do not match.';
  if (next === current) return 'The new password must differ from the current one.';
  return null;
}

/** The message for a refused change; the backend answers 400 with a precise reason. */
export function changePasswordErrorMessage(err: Pick<HttpErrorResponse, 'status'> & { error?: { message?: string } | null }): string {
  if (err.status === 401 || err.status === 403) return 'Your session is no longer valid. Please sign in again.';
  if (err.status === 0 || err.status >= 500) return 'The service is unavailable. Please try again shortly.';
  return err.error?.message ?? 'The password could not be changed. Please try again.';
}

/**
 * Forced password change (`must_change_password`). The backend gives such an account a restricted
 * session that may call `POST /auth/change-password` and nothing else; on success it answers with a
 * fresh, unrestricted session and we continue to the dashboard.
 */
@Component({
  selector: 'app-change-password',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatIconModule, MatProgressSpinnerModule],
  styles: [`
    .page {
      display: flex; align-items: center; justify-content: center;
      min-height: 100vh; padding: 24px; background: var(--rw-bg);
    }
    .card {
      width: 100%; max-width: 420px; padding: 32px;
      background: var(--rw-surface); border: 1px solid var(--rw-border);
      border-radius: var(--rw-radius-lg); box-shadow: var(--rw-shadow-md);
    }
    h1 { margin: 0 0 6px; font-size: 20px; color: var(--rw-text-primary); }
    .lead { margin: 0 0 24px; font-size: 13px; line-height: 1.5; color: var(--rw-text-secondary); }
    label { display: block; margin: 14px 0 6px; font-size: 12px; font-weight: 600; color: var(--rw-text-secondary); }
    input {
      width: 100%; box-sizing: border-box; padding: 10px 12px; font: inherit; font-size: 14px;
      color: var(--rw-text-primary); background: var(--rw-input-surface);
      border: 1px solid var(--rw-border); border-radius: var(--rw-radius);
    }
    input:focus { outline: 2px solid var(--rw-accent); outline-offset: 1px; }
    .error {
      display: flex; align-items: center; gap: 8px; margin-top: 16px; padding: 10px 12px;
      font-size: 13px; color: var(--rw-rejected-fg); background: var(--rw-rejected-bg);
      border-radius: var(--rw-radius);
    }
    .actions { display: flex; align-items: center; gap: 12px; margin-top: 24px; }
    .primary {
      flex: 1; display: inline-flex; align-items: center; justify-content: center; gap: 8px;
      padding: 11px 16px; font: inherit; font-weight: 600; cursor: pointer;
      color: var(--rw-accent-contrast); background: var(--rw-accent); border: 0; border-radius: var(--rw-radius);
    }
    .primary:disabled { opacity: 0.5; cursor: not-allowed; }
    .link { padding: 8px; font: inherit; font-size: 13px; cursor: pointer; color: var(--rw-text-secondary); background: none; border: 0; }
  `],
  template: `
    <div class="page">
      <div class="card">
        <h1>Set a new password</h1>
        <p class="lead">
          Your account requires a password change before you can continue. Choose a new password of at
          least 8 characters.
        </p>
        <form (ngSubmit)="submit()" [attr.aria-busy]="loading">
          <label for="current">Current password</label>
          <input id="current" name="current" type="password" autocomplete="current-password" required
                 [(ngModel)]="current" />
          <label for="next">New password</label>
          <input id="next" name="next" type="password" autocomplete="new-password" required
                 [(ngModel)]="next" />
          <label for="confirm">Repeat new password</label>
          <input id="confirm" name="confirm" type="password" autocomplete="new-password" required
                 [(ngModel)]="confirm" />

          @if (message) {
            <div class="error" role="alert"><mat-icon>error_outline</mat-icon>{{ message }}</div>
          }

          <div class="actions">
            <button class="primary" type="submit" [disabled]="loading || !ready">
              @if (loading) { <mat-spinner diameter="18" /> Saving… } @else { Change password }
            </button>
            <button class="link" type="button" (click)="signOut()" [disabled]="loading">Sign out</button>
          </div>
        </form>
      </div>
    </div>
  `,
})
export class ChangePasswordComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);

  current = '';
  next = '';
  confirm = '';
  loading = false;
  serverError = '';

  /** Client-side rule violations win over a stale server message while the user keeps typing. */
  get message(): string {
    return passwordChangeProblem(this.current, this.next, this.confirm) ?? this.serverError;
  }

  get ready(): boolean {
    return !!this.current && !!this.next && !!this.confirm
      && passwordChangeProblem(this.current, this.next, this.confirm) === null;
  }

  submit(): void {
    if (this.loading || !this.ready) return;
    this.loading = true;
    this.serverError = '';
    this.auth.changePassword(this.current, this.next).subscribe({
      next: () => {
        this.loading = false;
        this.current = this.next = this.confirm = '';
        // Admins without a company context land on the picker, like after a normal sign-in.
        const picker = (this.auth.hasRole('REGISTRY_ADMIN') || this.auth.hasRole('SUPPORT_AGENT')) && !this.auth.getEntityId();
        void this.router.navigate([picker ? '/select-company' : '/dashboard']);
        this.cdr.markForCheck();
      },
      error: (err: HttpErrorResponse) => {
        this.loading = false;
        this.current = '';
        this.serverError = changePasswordErrorMessage(err);
        this.cdr.markForCheck();
      },
    });
  }

  signOut(): void {
    this.auth.logout();
  }
}
