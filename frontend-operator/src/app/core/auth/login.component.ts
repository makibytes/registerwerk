import { Component } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { AuthService } from './auth.service';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
  ],
  styles: [`
    .login-container {
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      background: linear-gradient(135deg, #3f51b5 0%, #1a237e 100%);
    }

    mat-card {
      width: 400px;
      padding: 40px 32px;
      text-align: center;
    }

    .logo-area {
      margin-bottom: 32px;

      mat-icon {
        font-size: 48px;
        width: 48px;
        height: 48px;
        color: #3f51b5;
        margin-bottom: 16px;
      }

      h1 {
        font-size: 22px;
        font-weight: 500;
        margin: 0 0 4px;
        color: rgba(0, 0, 0, 0.87);
      }

      p {
        margin: 0;
        color: rgba(0, 0, 0, 0.54);
        font-size: 14px;
      }
    }

    .login-btn {
      width: 100%;
      padding: 12px;
      font-size: 15px;
      margin-top: 8px;
    }

    .ms-icon {
      width: 20px;
      height: 20px;
      margin-right: 8px;
      vertical-align: middle;
    }

    .form-field {
      width: 100%;
      margin-bottom: 8px;
    }

    .spinner-wrapper {
      display: inline-flex;
      align-items: center;
      gap: 8px;
    }
  `],
  template: `
    <div class="login-container">
      <mat-card>
        <div class="logo-area">
          <mat-icon>account_balance</mat-icon>
          <h1>Registerwerk</h1>
          <p>Operator Administration Portal</p>
        </div>

        <mat-card-content>
          @if (entraEnabled) {
            <button
              mat-raised-button
              color="primary"
              class="login-btn"
              (click)="loginWithMicrosoft()"
            >
              <svg class="ms-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 23 23">
                <path fill="#f3f3f3" d="M0 0h23v23H0z"/>
                <path fill="#f35325" d="M1 1h10v10H1z"/>
                <path fill="#81bc06" d="M12 1h10v10H12z"/>
                <path fill="#05a6f0" d="M1 12h10v10H1z"/>
                <path fill="#ffba08" d="M12 12h10v10H12z"/>
              </svg>
              Login with Microsoft
            </button>
          } @else {
            <form [formGroup]="loginForm" (ngSubmit)="loginWithCredentials()">
              <mat-form-field class="form-field" appearance="outline">
                <mat-label>Email</mat-label>
                <input matInput type="email" formControlName="email" autocomplete="email" />
                @if (loginForm.get('email')?.hasError('required')) {
                  <mat-error>Email is required</mat-error>
                } @else if (loginForm.get('email')?.hasError('email')) {
                  <mat-error>Enter a valid email address</mat-error>
                }
              </mat-form-field>

              <mat-form-field class="form-field" appearance="outline">
                <mat-label>Password</mat-label>
                <input matInput type="password" formControlName="password" autocomplete="current-password" />
                @if (loginForm.get('password')?.hasError('required')) {
                  <mat-error>Password is required</mat-error>
                }
              </mat-form-field>

              <button
                mat-raised-button
                color="primary"
                class="login-btn"
                type="submit"
                [disabled]="loading"
              >
                @if (loading) {
                  <span class="spinner-wrapper">
                    <mat-spinner diameter="20" />
                    Signing in…
                  </span>
                } @else {
                  Sign in
                }
              </button>
            </form>
          }
        </mat-card-content>

        <mat-card-footer style="padding: 16px 0 0; color: rgba(0,0,0,0.38); font-size: 12px;">
          Restricted access — authorized personnel only
        </mat-card-footer>
      </mat-card>
    </div>
  `,
})
export class LoginComponent {
  readonly entraEnabled = environment.entraEnabled;
  readonly loginForm: FormGroup;
  loading = false;

  constructor(
    private readonly authService: AuthService,
    private readonly router: Router,
    private readonly snackBar: MatSnackBar,
    fb: FormBuilder,
  ) {
    this.loginForm = fb.group({
      email: ['', [Validators.required, Validators.email]],
      password: ['', Validators.required],
    });
  }

  loginWithMicrosoft(): void {
    this.authService.login();
  }

  loginWithCredentials(): void {
    if (this.loginForm.invalid) {
      return;
    }
    this.loading = true;
    const { email, password } = this.loginForm.value as { email: string; password: string };
    this.authService.loginWithCredentials(email, password).subscribe({
      next: () => {
        this.loading = false;
        this.router.navigate(['/dashboard']);
      },
      error: (err: { error?: { message?: string } }) => {
        this.loading = false;
        const message = err?.error?.message ?? 'Login failed. Please try again.';
        this.snackBar.open(message, 'Dismiss', { duration: 5000 });
      },
    });
  }
}
