import { ChangeDetectorRef, Component, inject } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { AuthService } from './auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatIconModule,
    MatInputModule,
    MatFormFieldModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
  ],
  styles: [`
    .page {
      display: flex;
      min-height: 100vh;
      background: #07091A;
    }

    /* Left panel — decorative */
    .panel-left {
      display: none;
      flex: 1;
      background: linear-gradient(160deg, #0F1535 0%, #07091A 60%);
      padding: 48px;
      flex-direction: column;
      justify-content: space-between;
      border-right: 1px solid rgba(255,255,255,0.05);
      position: relative;
      overflow: hidden;

      @media (min-width: 900px) { display: flex; }
    }

    .panel-grid {
      position: absolute;
      inset: 0;
      background-image:
        linear-gradient(rgba(252,211,77,0.03) 1px, transparent 1px),
        linear-gradient(90deg, rgba(252,211,77,0.03) 1px, transparent 1px);
      background-size: 40px 40px;
    }

    .panel-accent {
      position: absolute;
      width: 400px;
      height: 400px;
      border-radius: 50%;
      background: radial-gradient(circle, rgba(245,158,11,0.08) 0%, transparent 70%);
      top: -100px;
      right: -100px;
    }

    .panel-brand {
      display: flex;
      align-items: center;
      gap: 10px;
      position: relative;
      z-index: 1;
    }

    .panel-brand-icon {
      width: 36px;
      height: 36px;
      border-radius: 9px;
      background: linear-gradient(135deg, #F59E0B 0%, #D97706 100%);
      display: flex;
      align-items: center;
      justify-content: center;

      mat-icon {
        font-size: 20px;
        width: 20px;
        height: 20px;
        color: #07091A;
      }
    }

    .panel-brand-text {
      font-size: 15px;
      font-weight: 700;
      color: #FFFFFF;
      letter-spacing: -0.2px;
    }

    .panel-tagline {
      position: relative;
      z-index: 1;
    }

    .panel-headline {
      font-size: 32px;
      font-weight: 800;
      color: #FFFFFF;
      line-height: 1.2;
      letter-spacing: -0.8px;
      margin: 0 0 12px;
    }

    .panel-subtext {
      font-size: 14px;
      color: rgba(255,255,255,0.4);
      line-height: 1.6;
      margin: 0;
    }

    .panel-footer {
      font-size: 11px;
      color: rgba(255,255,255,0.2);
      position: relative;
      z-index: 1;
      letter-spacing: 0.3px;
    }

    /* Right panel — form */
    .panel-right {
      width: 100%;
      max-width: 460px;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 40px 32px;
      background: #0D1020;

      @media (min-width: 900px) { padding: 48px 52px; }
    }

    .form-wrap {
      width: 100%;
      max-width: 360px;
    }

    .form-header {
      margin-bottom: 32px;
    }

    .form-title {
      font-size: 22px;
      font-weight: 800;
      color: #FFFFFF;
      margin: 0 0 6px;
      letter-spacing: -0.4px;
    }

    .form-subtitle {
      font-size: 13px;
      color: rgba(255,255,255,0.4);
      margin: 0;
    }

    .microsoft-btn {
      width: 100%;
      height: 44px;
      border: 1px solid rgba(255,255,255,0.12) !important;
      color: rgba(255,255,255,0.75) !important;
      background: rgba(255,255,255,0.04) !important;
      border-radius: 8px !important;
      font-size: 14px !important;
      font-weight: 500 !important;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      cursor: pointer;
      transition: background 0.15s ease, border-color 0.15s ease;

      &:hover {
        background: rgba(255,255,255,0.07) !important;
        border-color: rgba(255,255,255,0.2) !important;
      }

      .ms-logo {
        width: 18px;
        height: 18px;
        flex-shrink: 0;
      }
    }

    /* Form fields dark styling */
    ::ng-deep .login-field {
      width: 100%;
      margin-bottom: 4px;

      .mdc-text-field {
        background: rgba(255,255,255,0.04) !important;
        border-radius: 8px !important;
      }

      .mdc-text-field--outlined:not(.mdc-text-field--disabled) .mdc-notched-outline__leading,
      .mdc-text-field--outlined:not(.mdc-text-field--disabled) .mdc-notched-outline__notch,
      .mdc-text-field--outlined:not(.mdc-text-field--disabled) .mdc-notched-outline__trailing {
        border-color: rgba(255,255,255,0.12) !important;
      }

      .mdc-text-field--outlined:not(.mdc-text-field--disabled):hover .mdc-notched-outline__leading,
      .mdc-text-field--outlined:not(.mdc-text-field--disabled):hover .mdc-notched-outline__notch,
      .mdc-text-field--outlined:not(.mdc-text-field--disabled):hover .mdc-notched-outline__trailing {
        border-color: rgba(255,255,255,0.25) !important;
      }

      .mat-mdc-form-field-infix input {
        color: #FFFFFF !important;
        caret-color: #FCD34D !important;
      }

      .mat-mdc-floating-label { color: rgba(255,255,255,0.4) !important; }
    }

    .fields-stack {
      display: flex;
      flex-direction: column;
      gap: 8px;
      margin-bottom: 20px;
    }

    .submit-btn {
      width: 100%;
      height: 44px;
      background: linear-gradient(135deg, #F59E0B 0%, #D97706 100%) !important;
      color: #07091A !important;
      border-radius: 8px !important;
      font-size: 14px !important;
      font-weight: 700 !important;
      letter-spacing: 0.1px !important;
      box-shadow: 0 2px 8px rgba(245,158,11,0.3) !important;
      transition: opacity 0.15s ease, box-shadow 0.15s ease !important;

      &:hover { box-shadow: 0 4px 14px rgba(245,158,11,0.4) !important; }
      &:disabled { opacity: 0.5 !important; cursor: not-allowed !important; }
    }

    .spinner-row {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      color: #07091A;
    }

    .form-footer {
      margin-top: 28px;
      text-align: center;
      font-size: 11px;
      color: rgba(255,255,255,0.2);
      letter-spacing: 0.3px;
    }
  `],
  template: `
    <div class="page">
      <div class="panel-left">
        <div class="panel-grid"></div>
        <div class="panel-accent"></div>

        <div class="panel-brand">
          <div class="panel-brand-icon">
            <mat-icon>account_balance</mat-icon>
          </div>
          <span class="panel-brand-text">Registerwerk</span>
        </div>

        <div class="panel-tagline">
          <h1 class="panel-headline">Institutional<br>asset registry<br>for the digital age.</h1>
          <p class="panel-subtext">
            Manage tokenized securities, onboard clients,<br>
            and maintain a complete audit trail.
          </p>
        </div>

        <p class="panel-footer">Restricted to authorized registry operators.</p>
      </div>

      <div class="panel-right">
        <div class="form-wrap">
          <div class="form-header">
            <h1 class="form-title">Operator sign in</h1>
            <p class="form-subtitle">Access the registry administration portal</p>
          </div>

          <form [formGroup]="form" (ngSubmit)="submit()">
            <div class="fields-stack">
              <mat-form-field class="login-field" appearance="outline" subscriptSizing="dynamic">
                <mat-label>Email address</mat-label>
                <input matInput type="email" formControlName="email" autocomplete="email" />
              </mat-form-field>

              <mat-form-field class="login-field" appearance="outline" subscriptSizing="dynamic">
                <mat-label>Password</mat-label>
                <input matInput type="password" formControlName="password" autocomplete="current-password" />
              </mat-form-field>
            </div>

            <button class="submit-btn" mat-flat-button type="submit" [disabled]="loading">
              @if (loading) {
                <span class="spinner-row">
                  <mat-spinner diameter="16" color="accent" />
                  Signing in…
                </span>
              } @else {
                Sign in
              }
            </button>
          </form>

          <p class="form-footer">Registerwerk v1.0 — Authorized access only</p>
        </div>
      </div>
    </div>
  `,
})
export class LoginComponent {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  readonly form: FormGroup;
  loading = false;

  constructor() {
    const fb = inject(FormBuilder);
    this.form = fb.group({
      email: ['', [Validators.required, Validators.email]],
      password: ['', Validators.required],
    });
  }

  submit(): void {
    if (this.form.invalid) return;
    this.loading = true;
    const { email, password } = this.form.value as { email: string; password: string };
    this.authService.loginWithCredentials(email, password).subscribe({
      next: () => {
        this.loading = false;
        this.router.navigate(['/dashboard']);
      },
      error: (err: { error?: { message?: string } }) => {
        this.loading = false;
        this.cdr.markForCheck();
        this.snackBar.open(err?.error?.message ?? 'Login failed. Please try again.', 'Dismiss', { duration: 5000 });
      },
    });
  }
}
