import { ChangeDetectorRef, Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CompanyService } from '../api/company.service';

@Component({
  selector: 'app-reset-password',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, MatIconModule, MatProgressSpinnerModule],
  template: `
    <div class="page">
      <div class="card">
        <div class="eyebrow">Password reset</div>
        <h1>Create a new password</h1>
        <p class="intro">Choose a new password for your Registerwerk account.</p>

        @if (loading) {
          <div class="state-box"><mat-spinner diameter="30"></mat-spinner></div>
        } @else if (error) {
          <div class="state-box error-box">
            <mat-icon>error_outline</mat-icon>
            <span>{{ error }}</span>
          </div>
        } @else if (success) {
          <div class="state-box success-box">
            <mat-icon>check_circle</mat-icon>
            <span>Your password was updated. Redirecting to sign-in…</span>
          </div>
        } @else {
          <div class="account-chip">
            <mat-icon>mail</mat-icon>
            <span>{{ email }}</span>
          </div>

          <div class="field-group">
            <label for="password">New password</label>
            <input id="password" [type]="hidePassword ? 'password' : 'text'" [(ngModel)]="password" minlength="8" maxlength="200" autocomplete="new-password" />
            <span class="field-hint">At least 8 characters.</span>
          </div>

          <button class="ghost-link" type="button" (click)="hidePassword = !hidePassword">
            {{ hidePassword ? 'Show password' : 'Hide password' }}
          </button>

          @if (submitError) {
            <div class="state-box error-box compact" role="alert">
              <mat-icon>error_outline</mat-icon>
              <span>{{ submitError }}</span>
            </div>
          }

          <button class="primary-btn" type="button" [disabled]="submitting || password.length < 8" (click)="complete()">
            @if (submitting) {
              <mat-spinner diameter="18"></mat-spinner>
              Saving…
            } @else {
              Save new password
            }
          </button>
        }

        <p class="footer-copy">Remembered it already? <a routerLink="/login">Back to sign in</a></p>
      </div>
    </div>
  `,
  styles: [`
    .page {
      min-height: 100vh;
      display: grid;
      place-items: center;
      padding: 24px;
      background:
        radial-gradient(circle at top right, rgba(45, 212, 191, 0.14), transparent 26%),
        radial-gradient(circle at bottom left, rgba(13, 148, 136, 0.18), transparent 32%),
        #0f172a;
    }

    .card {
      width: min(460px, 100%);
      padding: 32px;
      border-radius: 24px;
      background: rgba(15, 23, 42, 0.9);
      border: 1px solid rgba(148, 163, 184, 0.2);
      box-shadow: 0 24px 70px rgba(15, 23, 42, 0.5);
      color: #e2e8f0;
    }

    .eyebrow {
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.18em;
      text-transform: uppercase;
      color: #5eead4;
      margin-bottom: 12px;
    }

    h1 {
      margin: 0;
      font-size: 30px;
      line-height: 1.05;
      letter-spacing: -0.05em;
    }

    .intro, .footer-copy {
      color: #94a3b8;
      font-size: 14px;
    }

    .field-group {
      display: grid;
      gap: 8px;
      margin-top: 18px;
    }

    .field-group label {
      font-size: 12px;
      font-weight: 700;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: #cbd5e1;
    }

    .field-group input {
      width: 100%;
      border: 1px solid rgba(148, 163, 184, 0.2);
      border-radius: 14px;
      padding: 12px 14px;
      background: rgba(30, 41, 59, 0.95);
      color: #f8fafc;
      font: inherit;
      outline: none;
    }

    .field-group input:focus {
      border-color: rgba(45, 212, 191, 0.55);
      box-shadow: 0 0 0 3px rgba(45, 212, 191, 0.14);
    }
    .field-hint { color: #94a3b8; font-size: 12px; }

    .account-chip, .state-box {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 14px 16px;
      border-radius: 16px;
      margin-top: 20px;
    }

    .account-chip {
      background: rgba(20, 184, 166, 0.08);
      border: 1px solid rgba(45, 212, 191, 0.16);
      color: #ccfbf1;
    }

    .state-box {
      justify-content: center;
      background: rgba(30, 41, 59, 0.9);
      border: 1px solid rgba(148, 163, 184, 0.18);
      color: #e2e8f0;
    }

    .state-box.compact { justify-content: flex-start; }
    .error-box { color: #fecaca; border-color: rgba(248, 113, 113, 0.28); background: rgba(127, 29, 29, 0.18); }
    .success-box { color: #bbf7d0; border-color: rgba(74, 222, 128, 0.24); background: rgba(20, 83, 45, 0.24); }

    .ghost-link {
      margin-top: 10px;
      border: none;
      background: none;
      padding: 0;
      color: #5eead4;
      font: inherit;
      cursor: pointer;
    }

    .primary-btn {
      width: 100%;
      margin-top: 22px;
      border: none;
      border-radius: 16px;
      padding: 14px 18px;
      background: linear-gradient(135deg, #2dd4bf 0%, #0f766e 100%);
      color: white;
      font: inherit;
      font-weight: 800;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      cursor: pointer;
    }

    .primary-btn:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }

    .footer-copy { margin-top: 18px; }
    .footer-copy a { color: #5eead4; text-decoration: none; }
  `]
})
export class ResetPasswordComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly companyService = inject(CompanyService);
  private readonly cdr = inject(ChangeDetectorRef);

  token = '';
  email = '';
  password = '';
  hidePassword = true;
  loading = true;
  submitting = false;
  success = false;
  error = '';
  submitError = '';
  private redirectTimer: ReturnType<typeof setTimeout> | null = null;

  ngOnInit(): void {
    this.token = this.route.snapshot.paramMap.get('token') ?? '';
    if (!this.token) {
      this.loading = false;
      this.error = 'This password reset link is invalid or has expired.';
      return;
    }
    this.companyService.getPasswordResetInfo(this.token).subscribe({
      next: (info) => {
        this.email = info.email;
        this.loading = false;
        this.cdr.detectChanges();
      },
      error: () => {
        this.loading = false;
        this.error = 'This password reset link is invalid or has expired.';
        this.cdr.detectChanges();
      }
    });
  }

  complete(): void {
    if (this.submitting || this.password.length < 8) return;
    this.submitting = true;
    this.submitError = '';
    this.companyService.completePasswordReset(this.token, this.password).subscribe({
      next: () => {
        this.submitting = false;
        this.success = true;
        this.cdr.detectChanges();
        this.redirectTimer = setTimeout(() => this.router.navigate(['/login']), 2000);
      },
      error: (err) => {
        this.submitting = false;
        this.submitError = err?.error?.message ?? 'Password reset could not be completed.';
        this.cdr.detectChanges();
      }
    });
  }

  ngOnDestroy(): void {
    if (this.redirectTimer) clearTimeout(this.redirectTimer);
  }
}
