import { ChangeDetectorRef, Component, OnInit, Input, inject } from '@angular/core';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
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
export class TokenGeneratorComponent implements OnInit {
  @Input() entityId!: string;

  private readonly entityService = inject(EntityService);
  private readonly onboardingService = inject(OnboardingService);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);

  loading = true;
  generating = false;
  copied = false;

  entity: LegalEntity | null = null;
  token: OnboardingToken | null = null;

  ngOnInit(): void {
    this.entityService.getEntity(this.entityId).subscribe({
      next: (entity) => {
        this.entity = entity;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
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
        this.cdr.markForCheck();
      },
    });
  }

  copyToken(): void {
    if (!this.token) return;
    navigator.clipboard.writeText(this.token.token).then(() => {
      this.copied = true;
      this.cdr.markForCheck();
      setTimeout(() => {
        this.copied = false;
        this.cdr.markForCheck();
      }, 3000);
    });
  }

  goBack(): void {
    this.router.navigate(['/customers', this.entityId]);
  }
}
