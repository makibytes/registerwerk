import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

@Component({
  selector: 'app-onboarding-entry',
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
  ],
  template: `
    <div class="onboarding-page">
      <mat-card class="onboarding-card">
        <mat-card-header>
          <div class="header-icon-wrap">
            <mat-icon class="header-icon">how_to_reg</mat-icon>
          </div>
          <mat-card-title>Account Setup</mat-card-title>
          <mat-card-subtitle>Registerwerk Customer Portal</mat-card-subtitle>
        </mat-card-header>

        <mat-card-content>
          <p class="intro">
            Your registry operator has sent you an onboarding token.
            Enter it below to activate your company account.
          </p>

          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Onboarding Token</mat-label>
            <input
              matInput
              [(ngModel)]="token"
              placeholder="Paste your token here…"
              autocomplete="off"
              spellcheck="false"
            />
            <mat-icon matSuffix>vpn_key</mat-icon>
          </mat-form-field>

          @if (error) {
            <p class="error-message">{{ error }}</p>
          }
        </mat-card-content>

        <mat-card-actions align="end">
          <a mat-button routerLink="/login">Back to Login</a>
          <button
            mat-raised-button
            color="primary"
            [disabled]="!token.trim()"
            (click)="proceed()"
          >
            <mat-icon>arrow_forward</mat-icon> Continue
          </button>
        </mat-card-actions>
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
    .onboarding-card {
      width: 480px;
      padding: 8px;
      background: color-mix(in srgb, var(--rw-surface) 92%, transparent);
      border: 1px solid color-mix(in srgb, var(--rw-border) 88%, white 12%);
      box-shadow: 0 24px 60px rgba(2, 6, 23, 0.28);
      backdrop-filter: blur(18px);
    }
    mat-card-header {
      flex-direction: column;
      align-items: center;
      padding-bottom: 8px;
    }
    .header-icon-wrap {
      width: 56px;
      height: 56px;
      border-radius: 50%;
      background: #00695c;
      display: flex;
      align-items: center;
      justify-content: center;
      margin-bottom: 12px;
    }
    .header-icon { font-size: 30px; width: 30px; height: 30px; color: white; }
    .intro {
      color: var(--rw-text-secondary);
      font-size: 14px;
      line-height: 1.5;
      margin: 12px 0 20px;
    }
    .full-width { width: 100%; }
    .error-message { color: var(--rw-text-danger); font-size: 13px; margin: 4px 0; }
  `],
})
export class OnboardingEntryComponent {
  private readonly router = inject(Router);

  token = '';
  error = '';

  proceed(): void {
    const t = this.token.trim();
    if (!t) return;
    this.router.navigate(['/onboarding/redeem', t]);
  }
}
