import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { environment } from '../../../../environments/environment';
import { OnboardingTokenInfo } from '../../../core/models';

@Component({
  selector: 'app-redeem-token',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatDividerModule,
  ],
  template: `
    <div class="onboarding-page">
      <mat-card class="onboarding-card">
        <mat-card-header>
          <mat-icon mat-card-avatar class="header-icon">how_to_reg</mat-icon>
          <mat-card-title>Account Setup</mat-card-title>
          <mat-card-subtitle>Registerwerk Onboarding</mat-card-subtitle>
        </mat-card-header>

        <mat-card-content>
          @if (loading) {
            <div class="loading-overlay">
              <mat-spinner diameter="40"></mat-spinner>
            </div>
          } @else if (tokenError) {
            <div class="error-state">
              <mat-icon class="error-icon">error_outline</mat-icon>
              <h3>Invalid or Expired Token</h3>
              <p>{{ tokenError }}</p>
              <a mat-raised-button routerLink="/login">Go to Login</a>
            </div>
          } @else if (success) {
            <div class="success-state">
              <mat-icon class="success-icon">check_circle</mat-icon>
              <h3>Setup Complete!</h3>
              <p>Your account has been successfully activated. You can now sign in.</p>
              <a mat-raised-button color="primary" routerLink="/login">Sign In</a>
            </div>
          } @else if (tokenInfo) {
            <!-- Entity info -->
            <div class="entity-info">
              <h3>{{ tokenInfo.entityName }}</h3>
              <p class="reg-number">Registration: {{ tokenInfo.entityRegistrationNumber }}</p>
              <p class="jurisdiction">Country: {{ tokenInfo.registrationCountry }}</p>
            </div>

            <mat-divider class="divider"></mat-divider>

            <!-- Admin account form -->
            <h4>Create your administrator account</h4>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Admin Email</mat-label>
              <input matInput type="email" [(ngModel)]="adminEmail" required placeholder="admin@company.com" />
              <mat-icon matSuffix>email</mat-icon>
            </mat-form-field>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Full Name</mat-label>
              <input matInput [(ngModel)]="adminName" required placeholder="Jane Smith" />
              <mat-icon matSuffix>person</mat-icon>
            </mat-form-field>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Password</mat-label>
              <input matInput [type]="hidePassword ? 'password' : 'text'" [(ngModel)]="password" required />
              <button mat-icon-button matSuffix type="button" (click)="hidePassword = !hidePassword">
                <mat-icon>{{ hidePassword ? 'visibility_off' : 'visibility' }}</mat-icon>
              </button>
            </mat-form-field>

            @if (submitError) {
              <p class="error-message">{{ submitError }}</p>
            }
          }
        </mat-card-content>

        @if (!loading && !tokenError && !success && tokenInfo) {
          <mat-card-actions align="end">
            <button mat-button routerLink="/login">Cancel</button>
            <button
              mat-raised-button
              color="primary"
              [disabled]="submitting || !adminEmail || !adminName || !password"
              (click)="complete()"
            >
              @if (submitting) {
                <mat-spinner diameter="18"></mat-spinner>
              } @else {
                Accept &amp; Set Up
              }
            </button>
          </mat-card-actions>
        }
      </mat-card>
    </div>
  `,
  styles: [`
    .onboarding-page {
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      background: linear-gradient(135deg, #1a237e 0%, #00695c 100%);
      padding: 24px;
    }
    .onboarding-card { width: 480px; }
    .header-icon {
      background: #00695c;
      color: white;
      border-radius: 50%;
      padding: 8px;
      font-size: 28px;
      width: 44px;
      height: 44px;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .entity-info {
      background: #e0f2f1;
      border-radius: 8px;
      padding: 16px;
      margin: 16px 0;
    }
    .entity-info h3 { margin: 0 0 4px; font-size: 18px; }
    .reg-number, .jurisdiction { margin: 2px 0; font-size: 13px; color: var(--rw-text-secondary); }
    .divider { margin: 16px 0; }
    h4 { margin: 0 0 16px; }
    .full-width { width: 100%; margin-bottom: 8px; }
    .error-message { color: var(--rw-text-danger); font-size: 13px; }
    .error-state, .success-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 32px;
      text-align: center;
    }
    .error-icon { font-size: 56px; width: 56px; height: 56px; color: #c62828; }
    .success-icon { font-size: 56px; width: 56px; height: 56px; color: #2e7d32; }
    .loading-overlay { display: flex; justify-content: center; padding: 48px; }
  `]
})
export class RedeemTokenComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly http = inject(HttpClient);

  token = '';
  tokenInfo: OnboardingTokenInfo | null = null;
  tokenError = '';
  loading = true;

  adminEmail = '';
  adminName = '';
  password = '';
  hidePassword = true;
  submitting = false;
  submitError = '';
  success = false;

  ngOnInit(): void {
    this.token = this.route.snapshot.paramMap.get('token') ?? '';
    if (!this.token) {
      this.loading = false;
      this.tokenError = 'No token provided.';
      return;
    }

    this.http
      .get<OnboardingTokenInfo>(`${environment.apiUrl}/onboarding/token-info/${this.token}`)
      .subscribe({
        next: (info) => {
          this.loading = false;
          if (info.alreadyUsed) {
            this.tokenError = 'This onboarding token has already been used.';
          } else {
            this.tokenInfo = info;
          }
          this.cdr.detectChanges();
        },
        error: () => {
          this.loading = false;
          this.tokenError = 'Token is invalid or has expired. Please contact your registry administrator.';
          this.cdr.detectChanges();
        },
      });
  }

  complete(): void {
    this.submitting = true;
    this.submitError = '';

    this.http
      .post(`${environment.apiUrl}/onboarding/complete`, {
        token: this.token,
        adminEmail: this.adminEmail,
        adminName: this.adminName,
        password: this.password,
      })
      .subscribe({
        next: () => {
          this.submitting = false;
          this.success = true;
          this.cdr.detectChanges();
          setTimeout(() => this.router.navigate(['/login']), 3000);
        },
        error: (err) => {
          this.submitting = false;
          this.submitError =
            err?.error?.message ?? 'Setup failed. Please check your details and try again.';
          this.cdr.detectChanges();
        },
      });
  }
}
