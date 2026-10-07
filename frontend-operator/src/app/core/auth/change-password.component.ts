import { ChangeDetectionStrategy, ChangeDetectorRef, Component, inject } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { AuthService } from './auth.service';

/** The new password must be typed twice, and must differ from the current one. */
export function passwordChangeValidator(group: AbstractControl): ValidationErrors | null {
  const current = group.get('currentPassword')?.value as string;
  const next = group.get('newPassword')?.value as string;
  const confirm = group.get('confirmPassword')?.value as string;
  const errors: ValidationErrors = {};
  if (next && confirm && next !== confirm) errors['mismatch'] = true;
  if (next && current && next === current) errors['unchanged'] = true;
  return Object.keys(errors).length ? errors : null;
}

/**
 * Forced password change (`must_change_password`). The backend hands such an account a restricted
 * session that may call `POST /auth/change-password` and nothing else; on success it answers with a
 * fresh, unrestricted session and we continue to the dashboard.
 */
@Component({
  selector: 'app-change-password',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatProgressSpinnerModule, MatSnackBarModule],
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
    p.lead { margin: 0 0 24px; font-size: 13px; line-height: 1.5; color: var(--rw-text-secondary); }
    form { display: flex; flex-direction: column; gap: 8px; }
    mat-form-field { width: 100%; }
    .field-error { margin: -4px 0 8px; font-size: 12px; color: var(--rw-text-danger); }
    .actions { display: flex; gap: 12px; margin-top: 8px; }
    .actions button:first-child { flex: 1; }
  `],
  template: `
    <div class="page">
      <div class="card">
        <h1>Set a new password</h1>
        <p class="lead">
          Your account requires a password change before you can use the portal. Choose a new password
          of at least 8 characters.
        </p>
        <form [formGroup]="form" (ngSubmit)="submit()">
          <mat-form-field appearance="outline">
            <mat-label>Current password</mat-label>
            <input matInput type="password" formControlName="currentPassword" autocomplete="current-password" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>New password</mat-label>
            <input matInput type="password" formControlName="newPassword" autocomplete="new-password" />
            @if (form.controls.newPassword.hasError('minlength')) {
              <mat-error>At least 8 characters</mat-error>
            }
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Repeat new password</mat-label>
            <input matInput type="password" formControlName="confirmPassword" autocomplete="new-password" />
          </mat-form-field>
          @if (form.hasError('mismatch') && form.controls.confirmPassword.dirty) {
            <p class="field-error">The two new passwords do not match.</p>
          }
          @if (form.hasError('unchanged')) {
            <p class="field-error">The new password must differ from the current one.</p>
          }
          <div class="actions">
            <button mat-flat-button type="submit" [disabled]="loading || form.invalid">
              @if (loading) { <mat-spinner diameter="16" /> } @else { Change password }
            </button>
            <button mat-button type="button" (click)="signOut()" [disabled]="loading">Sign out</button>
          </div>
        </form>
      </div>
    </div>
  `,
})
export class ChangePasswordComponent {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  loading = false;

  readonly form = inject(FormBuilder).nonNullable.group(
    {
      currentPassword: ['', Validators.required],
      newPassword: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(200)]],
      confirmPassword: ['', Validators.required],
    },
    { validators: passwordChangeValidator },
  );

  submit(): void {
    if (this.form.invalid || this.loading) return;
    this.loading = true;
    const { currentPassword, newPassword } = this.form.getRawValue();
    this.authService.changePassword(currentPassword, newPassword).subscribe({
      next: () => {
        this.loading = false;
        this.snackBar.open('Password changed.', 'Dismiss', { duration: 4000 });
        void this.router.navigate(['/dashboard']);
      },
      error: (err: { status?: number; error?: { message?: string } }) => {
        this.loading = false;
        this.form.controls.currentPassword.reset('');
        this.snackBar.open(
          err?.status === 401 || err?.status === 403
            ? 'The session is no longer valid. Please sign in again.'
            : err?.error?.message ?? 'The password could not be changed. Please try again.',
          'Dismiss',
          { duration: 6000, panelClass: 'snack-error' },
        );
        this.cdr.markForCheck();
      },
    });
  }

  signOut(): void {
    this.authService.logout();
  }
}
