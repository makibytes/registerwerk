import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatTabsModule } from '@angular/material/tabs';
import { CompanyService } from '../../../core/api/company.service';

@Component({
  selector: 'app-idp-settings',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    RouterLinkActive,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    MatTabsModule,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>Company Admin</h1>
      </div>

      <!-- Sub-tabs -->
      <nav mat-tab-nav-bar [tabPanel]="tabPanel">
        <a mat-tab-link routerLink="/company-admin/users" routerLinkActive #rla1="routerLinkActive" [active]="rla1.isActive">
          <mat-icon>people</mat-icon>&nbsp;Users
        </a>
        <a mat-tab-link routerLink="/company-admin/idp" routerLinkActive #rla2="routerLinkActive" [active]="rla2.isActive">
          <mat-icon>vpn_key</mat-icon>&nbsp;IdP Settings
        </a>
      </nav>
      <mat-tab-nav-panel #tabPanel></mat-tab-nav-panel>

      <mat-card class="settings-card">
        <mat-card-header>
          <mat-icon mat-card-avatar>lock</mat-icon>
          <mat-card-title>Identity Provider (OIDC)</mat-card-title>
          <mat-card-subtitle>
            Configure Single Sign-On for your organisation
          </mat-card-subtitle>
        </mat-card-header>

        <mat-card-content>
          @if (loadingSettings) {
            <div class="loading-overlay"><mat-spinner diameter="36"></mat-spinner></div>
          } @else {
            <p class="info-text">
              Connect an OIDC-compliant Identity Provider. Once configured, users from
              your organisation can sign in using your corporate SSO.
            </p>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>OIDC Issuer URL</mat-label>
              <input
                matInput
                [(ngModel)]="issuerUrl"
                placeholder="https://your-idp.example.com/realms/your-realm"
              />
              <mat-icon matSuffix>link</mat-icon>
              <mat-hint>Discovery document will be loaded from this URL + /.well-known/openid-configuration</mat-hint>
            </mat-form-field>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Client ID</mat-label>
              <input matInput [(ngModel)]="clientId" placeholder="registerwerk-client" />
              <mat-icon matSuffix>fingerprint</mat-icon>
            </mat-form-field>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Client Secret</mat-label>
              <input
                matInput
                [type]="hideSecret ? 'password' : 'text'"
                [(ngModel)]="clientSecret"
                placeholder="{{ hasExistingSecret ? '(unchanged)' : 'Enter secret' }}"
              />
              <button mat-icon-button matSuffix type="button" (click)="hideSecret = !hideSecret">
                <mat-icon>{{ hideSecret ? 'visibility_off' : 'visibility' }}</mat-icon>
              </button>
              @if (hasExistingSecret) {
                <mat-hint>Leave blank to keep the existing secret</mat-hint>
              }
            </mat-form-field>

            @if (saveError) {
              <p class="error-message">{{ saveError }}</p>
            }
          }
        </mat-card-content>

        @if (!loadingSettings) {
          <mat-card-actions align="end">
            <button mat-button (click)="reset()">Reset</button>
            <button
              mat-raised-button
              color="primary"
              [disabled]="saving || !issuerUrl || !clientId"
              (click)="save()"
            >
              @if (saving) { <mat-spinner diameter="18"></mat-spinner> }
              @else {
                <ng-container>
                  <mat-icon>save</mat-icon>
                  Save Settings
                </ng-container>
              }
            </button>
          </mat-card-actions>
        }
      </mat-card>
    </div>
  `,
  styles: [`
    .settings-card { margin-top: 16px; }
    .info-text { font-size: 14px; color: #546e7a; margin: 0 0 24px; }
    .full-width { width: 100%; margin-bottom: 16px; }
    .error-message { color: #c62828; font-size: 13px; }
  `]
})
export class IdpSettingsComponent implements OnInit {
  private readonly companyService = inject(CompanyService);
  private readonly snackBar = inject(MatSnackBar);

  issuerUrl = '';
  clientId  = '';
  clientSecret = '';
  hideSecret = true;
  hasExistingSecret = false;

  loadingSettings = true;
  saving = false;
  saveError = '';

  // Original values for reset
  private origIssuerUrl = '';
  private origClientId  = '';

  ngOnInit(): void {
    this.companyService.getIdpSettings().subscribe({
      next: (s) => {
        this.issuerUrl  = s.issuerUrl;
        this.clientId   = s.clientId;
        this.origIssuerUrl = s.issuerUrl;
        this.origClientId  = s.clientId;
        this.hasExistingSecret = !!s.clientSecret;
        this.loadingSettings = false;
      },
      error: () => (this.loadingSettings = false),
    });
  }

  save(): void {
    this.saving = true;
    this.saveError = '';

    this.companyService
      .saveIdpSettings({
        issuerUrl:    this.issuerUrl,
        clientId:     this.clientId,
        clientSecret: this.clientSecret,
      })
      .subscribe({
        next: () => {
          this.saving = false;
          this.hasExistingSecret = true;
          this.clientSecret = '';
          this.snackBar.open('IdP settings saved.', 'OK', { duration: 3000 });
        },
        error: (err) => {
          this.saving = false;
          this.saveError = err?.error?.message ?? 'Failed to save settings.';
        },
      });
  }

  reset(): void {
    this.issuerUrl    = this.origIssuerUrl;
    this.clientId     = this.origClientId;
    this.clientSecret = '';
    this.saveError    = '';
  }
}
