import { ChangeDetectorRef, Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { CompanyService } from '../../../core/api/company.service';

@Component({
  selector: 'app-setup-idp',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>Identity Provider Settings</h1>
        <p class="subtitle">Configure SSO for your organisation (optional)</p>
      </div>

      <mat-card>
        <mat-card-content>
          <p class="info-text">
            Connect your OIDC-compliant Identity Provider to enable Single Sign-On for your users.
            You can skip this step and configure it later in Company Admin &gt; IdP Settings.
          </p>

          <mat-form-field appearance="outline" class="full-width">
            <mat-label>OIDC Issuer URL</mat-label>
            <input
              matInput
              [(ngModel)]="issuerUrl"
              placeholder="https://your-idp.example.com/realms/your-realm"
            />
            <mat-icon matSuffix>link</mat-icon>
            <mat-hint>The issuer URL from your IdP's OIDC discovery endpoint</mat-hint>
          </mat-form-field>

          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Client ID</mat-label>
            <input matInput [(ngModel)]="clientId" placeholder="registerwerk-client" />
            <mat-icon matSuffix>vpn_key</mat-icon>
          </mat-form-field>

          <!-- No client secret: federation is established tenant-to-tenant in your identity
               provider, so Registerwerk never runs an authorization-code flow against it. -->

          @if (saveError) {
            <p class="error-message">{{ saveError }}</p>
          }
        </mat-card-content>

        <mat-card-actions align="end">
          <button mat-button (click)="skip()">Skip</button>
          <button
            mat-raised-button
            color="primary"
            [disabled]="saving || !issuerUrl || !clientId"
            (click)="save()"
          >
            @if (saving) { <mat-spinner diameter="18"></mat-spinner> }
            @else { Save &amp; Finish }
          </button>
        </mat-card-actions>
      </mat-card>
    </div>
  `,
  styles: [`
    .page-container { padding: 32px; max-width: 640px; margin: 0 auto; }
    .page-header h1 { margin: 0; }
    .subtitle { color: var(--rw-text-secondary); margin: 4px 0 24px; }
    .info-text { color: var(--rw-text-secondary); font-size: 14px; margin-bottom: 24px; }
    .full-width { width: 100%; margin-bottom: 16px; }
    .error-message { color: var(--rw-text-danger); font-size: 13px; }
  `]
})
export class SetupIdpComponent {
  private readonly company = inject(CompanyService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  issuerUrl = '';
  clientId = '';
  saving = false;
  saveError = '';

  save(): void {
    this.saving = true;
    this.saveError = '';

    this.company
      .saveIdpSettings({ issuerUrl: this.issuerUrl, clientId: this.clientId })
      .subscribe({
        next: () => {
          this.saving = false;
          this.snackBar.open('IdP settings saved successfully.', 'OK', { duration: 3000 });
          this.router.navigate(['/dashboard']);
        },
        error: (err) => {
          this.saving = false;
          this.saveError = err?.error?.message ?? 'Failed to save IdP settings.';
          this.cdr.markForCheck();
        },
      });
  }

  skip(): void {
    this.router.navigate(['/dashboard']);
  }
}
