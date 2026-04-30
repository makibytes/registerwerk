import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { HttpClient } from '@angular/common/http';
import { AuthService } from './auth.service';
import { environment } from '../../../environments/environment';

interface LoginResponse {
  token: string;
}

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, MatIconModule, MatProgressSpinnerModule],
  styles: [`
    .page {
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      background: #0F1A2E;
      padding: 24px;
      position: relative;
      overflow: hidden;
    }

    /* Subtle mesh gradient background */
    .page::before {
      content: '';
      position: absolute;
      inset: 0;
      background:
        radial-gradient(ellipse 60% 50% at 15% 80%, rgba(13,148,136,0.12) 0%, transparent 60%),
        radial-gradient(ellipse 50% 60% at 85% 20%, rgba(45,212,191,0.06) 0%, transparent 60%),
        radial-gradient(ellipse 80% 40% at 50% 50%, rgba(15,26,46,0.8) 0%, transparent 100%);
      pointer-events: none;
    }

    /* Dot grid overlay */
    .page::after {
      content: '';
      position: absolute;
      inset: 0;
      background-image: radial-gradient(circle, rgba(255,255,255,0.04) 1px, transparent 1px);
      background-size: 28px 28px;
      pointer-events: none;
    }

    .card {
      position: relative;
      z-index: 1;
      width: 100%;
      max-width: 420px;
      background: rgba(255,255,255,0.03);
      border: 1px solid rgba(255,255,255,0.08);
      border-radius: 18px;
      padding: 40px;
      backdrop-filter: blur(20px);
      box-shadow:
        0 0 0 1px rgba(255,255,255,0.04),
        0 24px 64px rgba(0,0,0,0.5),
        0 4px 16px rgba(0,0,0,0.3);
    }

    .card-header {
      text-align: center;
      margin-bottom: 32px;
    }

    .logo-mark {
      width: 52px;
      height: 52px;
      border-radius: 14px;
      background: linear-gradient(135deg, #2DD4BF 0%, #0D9488 100%);
      display: flex;
      align-items: center;
      justify-content: center;
      margin: 0 auto 16px;
      box-shadow: 0 8px 20px rgba(13,148,136,0.3);

      mat-icon {
        font-size: 26px;
        width: 26px;
        height: 26px;
        color: white;
      }
    }

    .card-title {
      font-size: 22px;
      font-weight: 800;
      color: #FFFFFF;
      margin: 0 0 6px;
      letter-spacing: -0.4px;
    }

    .card-subtitle {
      font-size: 13px;
      color: rgba(255,255,255,0.4);
      margin: 0;
    }

    /* Custom form fields */
    .field-group {
      display: flex;
      flex-direction: column;
      gap: 10px;
      margin-bottom: 20px;
    }

    .field-label {
      font-size: 12px;
      font-weight: 600;
      color: rgba(255,255,255,0.5);
      letter-spacing: 0.4px;
      text-transform: uppercase;
      margin-bottom: 4px;
      display: block;
    }

    .field-input {
      width: 100%;
      background: rgba(255,255,255,0.05);
      border: 1px solid rgba(255,255,255,0.1);
      border-radius: 9px;
      padding: 11px 14px;
      font-family: 'Manrope', sans-serif;
      font-size: 14px;
      font-weight: 500;
      color: #FFFFFF;
      outline: none;
      transition: border-color 0.15s ease, background 0.15s ease;

      &::placeholder { color: rgba(255,255,255,0.2); }

      &:focus {
        border-color: rgba(45,212,191,0.5);
        background: rgba(45,212,191,0.04);
      }
    }

    .password-wrap {
      position: relative;

      .field-input { padding-right: 44px; }

      .toggle-btn {
        position: absolute;
        right: 10px;
        top: 50%;
        transform: translateY(-50%);
        background: none;
        border: none;
        cursor: pointer;
        padding: 4px;
        color: rgba(255,255,255,0.3);
        display: flex;
        align-items: center;

        mat-icon {
          font-size: 18px;
          width: 18px;
          height: 18px;
        }

        &:hover { color: rgba(255,255,255,0.6); }
      }
    }

    .error-msg {
      background: rgba(190,18,60,0.12);
      border: 1px solid rgba(190,18,60,0.2);
      border-radius: 8px;
      padding: 10px 14px;
      font-size: 13px;
      color: #FDA4AF;
      margin-bottom: 16px;
      display: flex;
      align-items: center;
      gap: 8px;

      mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
        flex-shrink: 0;
      }
    }

    .submit-btn {
      width: 100%;
      height: 46px;
      background: linear-gradient(135deg, #2DD4BF 0%, #0D9488 100%);
      color: #FFFFFF;
      border: none;
      border-radius: 9px;
      font-family: 'Manrope', sans-serif;
      font-size: 14px;
      font-weight: 700;
      letter-spacing: 0.1px;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      box-shadow: 0 2px 12px rgba(13,148,136,0.3);
      transition: box-shadow 0.15s ease, opacity 0.15s ease;

      &:hover { box-shadow: 0 4px 18px rgba(13,148,136,0.45); }
      &:disabled {
        opacity: 0.55;
        cursor: not-allowed;
      }
    }

    .card-footer {
      margin-top: 24px;
      text-align: center;
    }

    .onboarding-link {
      font-size: 13px;
      color: rgba(255,255,255,0.35);

      a {
        color: #2DD4BF;
        text-decoration: none;
        font-weight: 600;

        &:hover { text-decoration: underline; }
      }
    }
  `],
  template: `
    <div class="page">
      <div class="card">
        <div class="card-header">
          <div class="logo-mark">
            <mat-icon>account_balance</mat-icon>
          </div>
          <h1 class="card-title">Welcome back</h1>
          <p class="card-subtitle">Sign in to the Registerwerk Customer Portal</p>
        </div>

        <form (ngSubmit)="onSubmit()">
          <div class="field-group">
            <div>
              <label class="field-label" for="email">Email address</label>
              <input
                id="email"
                class="field-input"
                type="email"
                [(ngModel)]="email"
                name="email"
                required
                autocomplete="email"
                placeholder="you@company.com"
              />
            </div>

            <div>
              <label class="field-label" for="password">Password</label>
              <div class="password-wrap">
                <input
                  id="password"
                  class="field-input"
                  [type]="hidePassword ? 'password' : 'text'"
                  [(ngModel)]="password"
                  name="password"
                  required
                  autocomplete="current-password"
                  placeholder="••••••••"
                />
                <button type="button" class="toggle-btn" (click)="hidePassword = !hidePassword" [attr.aria-label]="'Toggle password'">
                  <mat-icon>{{ hidePassword ? 'visibility_off' : 'visibility' }}</mat-icon>
                </button>
              </div>
            </div>
          </div>

          @if (errorMessage) {
            <div class="error-msg">
              <mat-icon>error_outline</mat-icon>
              {{ errorMessage }}
            </div>
          }

          <button class="submit-btn" type="submit" [disabled]="loading || !email || !password">
            @if (loading) {
              <mat-spinner diameter="18" />
              Signing in…
            } @else {
              Sign in
            }
          </button>
        </form>

        <div class="card-footer">
          <p class="onboarding-link">
            New here? <a routerLink="/onboarding">Set up your account</a>
          </p>
        </div>
      </div>
    </div>
  `,
})
export class LoginComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  email = '';
  password = '';
  hidePassword = true;
  loading = false;
  errorMessage = '';

  onSubmit(): void {
    if (!this.email || !this.password) return;
    this.loading = true;
    this.errorMessage = '';

    this.http
      .post<LoginResponse>(`${environment.apiUrl}/public/auth/login`, {
        email: this.email,
        password: this.password,
      })
      .subscribe({
        next: (res) => {
          this.auth.setToken(res.token);
          // REGISTRY_ADMIN with no entity context → company picker
          if (this.auth.hasRole('REGISTRY_ADMIN') && !this.auth.getEntityId()) {
            this.router.navigate(['/select-company']);
          } else {
            this.router.navigate(['/dashboard']);
          }
        },
        error: () => {
          this.loading = false;
          this.errorMessage = 'Invalid credentials. Please check your email and password.';
        },
      });
  }
}
