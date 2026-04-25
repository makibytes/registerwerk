import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
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
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <div class="login-page">
      <mat-card class="login-card">
        <mat-card-header>
          <div class="login-logo">
            <mat-icon class="logo-icon">account_balance</mat-icon>
          </div>
          <mat-card-title>Registerwerk</mat-card-title>
          <mat-card-subtitle>Customer Portal</mat-card-subtitle>
        </mat-card-header>

        <mat-card-content>
          <form (ngSubmit)="onSubmit()" #loginForm="ngForm">
            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Email</mat-label>
              <input
                matInput
                type="email"
                name="email"
                [(ngModel)]="email"
                required
                autocomplete="email"
                placeholder="your@company.com"
              />
              <mat-icon matSuffix>email</mat-icon>
            </mat-form-field>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Password</mat-label>
              <input
                matInput
                [type]="hidePassword ? 'password' : 'text'"
                name="password"
                [(ngModel)]="password"
                required
                autocomplete="current-password"
              />
              <button
                mat-icon-button
                matSuffix
                type="button"
                (click)="hidePassword = !hidePassword"
                [attr.aria-label]="'Toggle password visibility'"
              >
                <mat-icon>{{ hidePassword ? 'visibility_off' : 'visibility' }}</mat-icon>
              </button>
            </mat-form-field>

            @if (errorMessage) {
              <p class="error-message">{{ errorMessage }}</p>
            }

            <button
              mat-raised-button
              color="primary"
              type="submit"
              class="full-width login-btn"
              [disabled]="loading || loginForm.invalid"
            >
              @if (loading) {
                <mat-spinner diameter="20"></mat-spinner>
              } @else {
                Sign In
              }
            </button>
          </form>
        </mat-card-content>

        <mat-card-actions>
          <p class="hint-text">
            New to the platform?
            <a routerLink="/onboarding">Set up your account</a>
          </p>
        </mat-card-actions>
      </mat-card>
    </div>
  `,
  styles: [`
    .login-page {
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      background: linear-gradient(135deg, #1a237e 0%, #00695c 100%);
    }
    .login-card {
      width: 400px;
      padding: 16px;
    }
    mat-card-header {
      flex-direction: column;
      align-items: center;
      padding-bottom: 16px;
    }
    .login-logo {
      width: 64px;
      height: 64px;
      border-radius: 50%;
      background: #00695c;
      display: flex;
      align-items: center;
      justify-content: center;
      margin-bottom: 12px;
    }
    .logo-icon {
      font-size: 36px;
      width: 36px;
      height: 36px;
      color: white;
    }
    mat-card-title {
      font-size: 22px !important;
    }
    .full-width { width: 100%; }
    .login-btn {
      margin-top: 8px;
      height: 44px;
    }
    .error-message {
      color: #c62828;
      font-size: 13px;
      margin: 4px 0 8px;
    }
    .hint-text {
      text-align: center;
      font-size: 13px;
      color: #757575;
      width: 100%;
      margin: 8px 0 0;
    }
  `]
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
          this.router.navigate(['/dashboard']);
        },
        error: () => {
          this.loading = false;
          this.errorMessage = 'Invalid credentials. Please try again.';
        },
      });
  }
}
