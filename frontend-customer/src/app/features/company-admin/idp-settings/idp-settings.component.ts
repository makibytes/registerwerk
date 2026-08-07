import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
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
        <a mat-tab-link routerLink="/company-admin/org-identity" routerLinkActive #rla4="routerLinkActive" [active]="rla4.isActive">
          <mat-icon>fingerprint</mat-icon>&nbsp;Organization
        </a>
        <a mat-tab-link routerLink="/company-admin/beneficial-owners" routerLinkActive #rlaBo="routerLinkActive" [active]="rlaBo.isActive">
          <mat-icon>diversity_3</mat-icon>&nbsp;Beneficial Owners
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

            @if (lifecycleManagedExternally) {
              <div class="managed-banner">
                User lifecycle actions are managed in your identity provider while Entra mode is enabled.
              </div>
            }

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

            <!-- Federation is established tenant-to-tenant in your identity provider, so
                 Registerwerk never needs a client secret from you. These two read-only rows
                 show what the registry operator has configured for your organisation. -->
            <div class="readonly-row">
              <span class="readonly-label">Identity model</span>
              <span class="readonly-value">{{ identityModelLabel }}</span>
            </div>
            <div class="readonly-row">
              <span class="readonly-label">Inbound MFA trust</span>
              <span class="readonly-value">
                {{ idpMfaTrusted ? 'Configured' : 'Not configured' }}
              </span>
            </div>
            <p class="readonly-note">
              These are set by the registry operator. Contact them to change how your users sign
              in or whether multi-factor authentication performed in your own tenant is accepted.
            </p>

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
    .info-text { font-size: 14px; color: var(--rw-text-secondary); margin: 0 0 24px; }
    .full-width { width: 100%; margin-bottom: 16px; }

    .readonly-row {
      display: flex;
      justify-content: space-between;
      gap: 16px;
      padding: 12px 0;
      border-bottom: 1px solid var(--rw-border, #E5E7EB);
      font-size: 14px;
    }

    .readonly-label { color: var(--rw-text-muted, #6B7280); }
    .readonly-value { font-weight: 600; text-align: right; }

    .readonly-note {
      margin: 14px 0 8px;
      font-size: 12px;
      line-height: 1.6;
      color: var(--rw-text-muted, #6B7280);
    }
    .error-message { color: var(--rw-text-danger); font-size: 13px; }
    .managed-banner {
      margin: 0 0 16px;
      padding: 12px 14px;
      border-radius: 12px;
      background: rgba(13, 148, 136, 0.08);
      border: 1px solid rgba(13, 148, 136, 0.18);
      color: var(--rw-text-primary);
      font-size: 13px;
    }
  `]
})
export class IdpSettingsComponent implements OnInit {
  private readonly companyService = inject(CompanyService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  issuerUrl = '';
  clientId  = '';
  lifecycleManagedExternally = false;
  identityModel = 'WORKFORCE_GUEST';
  idpMfaTrusted = false;

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
        this.identityModel = s.identityModel;
        this.idpMfaTrusted = s.idpMfaTrusted;
        this.lifecycleManagedExternally = s.lifecycleManagedExternally;
        this.loadingSettings = false;
        this.cdr.markForCheck();
      },
      error: () => { this.loadingSettings = false; this.cdr.markForCheck(); },
    });
  }

  save(): void {
    this.saving = true;
    this.saveError = '';

    this.companyService
      .saveIdpSettings({
        issuerUrl: this.issuerUrl,
        clientId:  this.clientId,
      })
      .subscribe({
        next: () => {
          this.saving = false;
          this.cdr.markForCheck();
          this.snackBar.open('IdP settings saved.', 'OK', { duration: 3000 });
        },
        error: (err) => {
          this.saving = false;
          this.saveError = err?.error?.message ?? 'Failed to save settings.';
          this.cdr.markForCheck();
        },
      });
  }

  reset(): void {
    this.issuerUrl = this.origIssuerUrl;
    this.clientId  = this.origClientId;
    this.saveError = '';
  }

  get identityModelLabel(): string {
    switch (this.identityModel) {
      case 'FEDERATED':
        return 'Federated — your own Microsoft Entra tenant';
      case 'WORKFORCE_GUEST':
        return 'Guest accounts in the registry operator’s tenant';
      case 'WORKFORCE_MEMBER':
        return 'Member accounts in the registry operator’s tenant';
      default:
        return 'Local accounts';
    }
  }
}
