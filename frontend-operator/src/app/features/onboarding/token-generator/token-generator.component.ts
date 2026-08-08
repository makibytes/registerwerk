import { ChangeDetectorRef, Component, OnDestroy, OnInit, Input, inject } from '@angular/core';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { DatePipe } from '@angular/common';
import { EntityService } from '../../../core/api/entity.service';
import { OnboardingService } from '../../../core/api/onboarding.service';
import { LegalEntity, OnboardingToken } from '../../../core/models';
import { StatusBadgeComponent } from '@registerwerk/ui';

@Component({
  selector: 'app-token-generator',
  standalone: true,
  imports: [
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    MatSnackBarModule,
    StatusBadgeComponent,
    DatePipe,
  ],
  styles: [`
    .back-row { margin-bottom: 12px; }

    .page-card { max-width: 700px; }

    .entity-summary {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 8px 24px;
      margin-bottom: 20px;

      .field-label {
        font-size: 11px;
        color: var(--rw-text-muted);
        text-transform: uppercase;
        letter-spacing: 0.5px;
        font-weight: 600;
        margin-bottom: 2px;
      }

      .field-value {
        font-size: 14px;
        color: var(--rw-text-primary);
      }
    }

    .generate-section {
      margin-top: 8px;
    }

    .token-area {
      margin-top: 16px;

      .token-meta {
        font-size: 12px;
        color: var(--rw-text-muted);
        margin-top: 8px;
      }

      .token-actions {
        display: flex;
        gap: 12px;
        margin-top: 16px;
      }
    }

    .spinner-wrap {
      display: flex;
      justify-content: center;
      padding: 40px;
    }

    .copied-hint {
      font-size: 13px;
      color: var(--rw-approved-fg);
      display: flex;
      align-items: center;
      gap: 4px;
    }

    .generate-copy {
      color: var(--rw-text-secondary);
      font-size: 14px;
      margin-bottom: 16px;
    }

    .request-error {
      display: grid;
      justify-items: center;
      gap: 12px;
      padding: 40px 20px;
      color: var(--rw-text-danger);
      text-align: center;
    }

    @media (max-width: 560px) {
      .entity-summary { grid-template-columns: 1fr; }
      .token-actions { align-items: flex-start; flex-direction: column; }
      .token-display { overflow-wrap: anywhere; }
    }
  `],
  template: `
    <div class="back-row">
      <button mat-button (click)="goBack()">
        <mat-icon>arrow_back</mat-icon>
        Back to Customers
      </button>
    </div>

    @if (loading) {
      <div class="spinner-wrap"><mat-spinner diameter="40" /></div>
    } @else if (loadError) {
      <div class="request-error" role="alert">
        <mat-icon>cloud_off</mat-icon>
        <span>The entity could not be loaded.</span>
        <button mat-stroked-button type="button" (click)="loadEntity()">Retry</button>
      </div>
    } @else if (entity) {
      <mat-card class="page-card">
        <mat-card-header>
          <mat-icon mat-card-avatar>key</mat-icon>
          <mat-card-title>Onboarding Token</mat-card-title>
          <mat-card-subtitle>Generate a one-time token for entity self-onboarding</mat-card-subtitle>
        </mat-card-header>

        <mat-card-content>
          <mat-divider style="margin: 16px 0;" />

          <div class="entity-summary">
            <div>
              <div class="field-label">Entity</div>
              <div class="field-value">{{ entity.currentName }}</div>
            </div>
            <div>
              <div class="field-label">Number</div>
              <div class="field-value"><code>{{ entity.entityNumber }}</code></div>
            </div>
            <div>
              <div class="field-label">Type</div>
              <div class="field-value">{{ entity.type }}</div>
            </div>
            <div>
              <div class="field-label">Status</div>
              <div class="field-value">
                <app-status-badge [status]="entity.status" />
              </div>
            </div>
          </div>

          <mat-divider />

            <div class="generate-section">
              @if (!token) {
               <p class="generate-copy">
                 Click the button below to generate a secure, single-use onboarding token
                 for this entity. The token expires in 24 hours.
               </p>
              <button
                mat-raised-button
                color="primary"
                (click)="generate()"
                [disabled]="generating"
              >
                @if (generating) {
                  <mat-spinner diameter="18" style="display:inline-block;vertical-align:middle;margin-right:6px" />
                }
                <mat-icon>generating_tokens</mat-icon>
                Generate Onboarding Token
              </button>
            } @else {
              <div class="token-area">
                <div class="warning-banner">
                  <mat-icon>warning</mat-icon>
                  <strong>This token will only be shown once.</strong>
                  Copy it now and deliver it securely to the entity.
                </div>

                <div class="token-display">{{ token.token }}</div>

                <div class="token-meta">
                  Expires: {{ token.expiresAt | date:'medium' }}
                </div>

                <div class="token-actions">
                  <button mat-raised-button color="accent" (click)="copyToken()">
                    <mat-icon>content_copy</mat-icon>
                    Copy Token
                  </button>
                  @if (copied) {
                    <span class="copied-hint">
                      <mat-icon style="font-size:18px;width:18px;height:18px">check_circle</mat-icon>
                      Copied to clipboard
                    </span>
                  }
                </div>
              </div>
            }
          </div>
        </mat-card-content>
      </mat-card>
    }
  `,
})
export class TokenGeneratorComponent implements OnInit, OnDestroy {
  @Input() entityId!: string;

  private readonly entityService = inject(EntityService);
  private readonly onboardingService = inject(OnboardingService);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly snackBar = inject(MatSnackBar);
  private copiedTimer?: ReturnType<typeof setTimeout>;

  loading = true;
  generating = false;
  copied = false;
  loadError = false;

  entity: LegalEntity | null = null;
  token: OnboardingToken | null = null;

  ngOnInit(): void {
    this.loadEntity();
  }

  ngOnDestroy(): void {
    if (this.copiedTimer) clearTimeout(this.copiedTimer);
  }

  loadEntity(): void {
    if (!this.entityId) {
      this.loading = false;
      this.loadError = true;
      return;
    }
    this.loading = true;
    this.loadError = false;
    this.entityService.getEntity(this.entityId).subscribe({
      next: (entity) => {
        this.entity = entity;
        this.loading = false;
        this.loadError = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.loadError = true;
        this.cdr.markForCheck();
      },
    });
  }

  generate(): void {
    this.generating = true;
    this.onboardingService.generateToken(this.entityId).subscribe({
      next: (token) => {
        this.token = token;
        this.generating = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.generating = false;
        this.snackBar.open('Failed to generate the onboarding token.', 'Dismiss', { duration: 5000 });
        this.cdr.markForCheck();
      },
    });
  }

  copyToken(): void {
    if (!this.token) return;
    navigator.clipboard.writeText(this.token.token).then(() => {
      this.copied = true;
      this.cdr.markForCheck();
      if (this.copiedTimer) clearTimeout(this.copiedTimer);
      this.copiedTimer = setTimeout(() => {
        this.copied = false;
        this.cdr.markForCheck();
      }, 3000);
    }).catch(() => {
      this.snackBar.open('Could not copy the token. Select and copy it manually.', 'Dismiss', { duration: 5000 });
    });
  }

  goBack(): void {
    this.router.navigate(['/customers', this.entityId]);
  }
}
