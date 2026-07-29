import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Clipboard } from '@angular/cdk/clipboard';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatStepperModule } from '@angular/material/stepper';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';

import { MarketplaceService } from '../../../core/api/marketplace.service';
import {
  DappListingView,
  DappRequiredPermissionView,
  DappVersionView,
  PaymentMethodView,
  PaymentRailView,
} from '../../../core/models';

/**
 * Guided publication flow: paste + validate the manifest, review the declared
 * permissions, sign the manifest hash with a bound org wallet, submit for review.
 */
@Component({
  selector: 'app-publish-wizard',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatStepperModule,
    MatTableModule,
    MatTooltipModule,
  ],
  styles: [`
    .wizard-card { margin-top: 16px; }

    .manifest-input {
      width: 100%;
      font-family: 'IBM Plex Mono', monospace;
      font-size: 12px;
      min-height: 280px;
    }

    .validation-errors {
      background: var(--rw-rejected-bg);
      color: var(--rw-rejected-fg);
      border-radius: 8px;
      padding: 12px 16px;
      font-size: 13px;
      margin-top: 12px;

      ul { margin: 4px 0 0; padding-left: 18px; }
    }

    .hash-box {
      background: var(--rw-code-bg);
      color: var(--rw-code-fg);
      font-family: 'IBM Plex Mono', monospace;
      font-size: 12px;
      padding: 12px;
      border-radius: 8px;
      word-break: break-all;
      margin-top: 12px;
    }

    .step-actions { display: flex; gap: 8px; margin-top: 20px; }
    .error-message { color: var(--rw-text-danger); font-size: 13px; margin: 10px 0 0; }
    .hint { font-size: 13px; color: var(--rw-text-secondary); margin: 0 0 12px; }
    .success-note { display: flex; align-items: center; gap: 8px; color: var(--rw-text-success); font-size: 13px; margin-top: 12px; }

    .rail-reference {
      display: flex;
      flex-direction: column;
      gap: 6px;
      margin-top: 16px;
      padding: 12px 14px;
      background: var(--rw-surface-soft);
      border: 1px solid var(--rw-border-subtle);
      border-radius: var(--rw-radius);
    }

    .rail-reference-title {
      font-size: 11px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.6px;
      color: var(--rw-text-muted);
      margin: 0 0 4px;
    }

    .rail-reference-row {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 12px;

      .rail-code {
        font-family: 'IBM Plex Mono', monospace;
        background: var(--rw-code-bg);
        color: var(--rw-code-fg);
        padding: 2px 6px;
        border-radius: 4px;
        cursor: pointer;
      }

      .rail-name { color: var(--rw-text-secondary); }
    }
  `],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>Publish {{ listing?.name ?? 'dApp' }}</h1>
        <button mat-stroked-button routerLink="/publisher">
          <mat-icon>arrow_back</mat-icon>
          My dApps
        </button>
      </div>

      @if (loading) {
        <mat-spinner diameter="36" style="margin:60px auto"></mat-spinner>
      } @else if (listing && version) {
        <mat-card class="wizard-card">
          <mat-card-content>
            <mat-stepper [linear]="false" orientation="vertical">

              <mat-step label="Manifest" [completed]="manifestValid">
                <p class="hint">
                  Paste your dApp manifest (JSON). It describes contracts, required
                  permissions/claims and your container images pinned by digest.
                  The exact bytes you validate here are what you will sign.
                </p>
                <textarea class="manifest-input" [(ngModel)]="manifestRaw"
                          [disabled]="version.status !== 'DRAFT'"
                          placeholder='{ "slug": "{{ listing.slug }}", "name": "…", "version": "1.0.0", … }'>
                </textarea>
                @if (validationErrors.length > 0) {
                  <div class="validation-errors">
                    <strong>Validation failed:</strong>
                    <ul>
                      @for (error of validationErrors; track error) {
                        <li>{{ error }}</li>
                      }
                    </ul>
                  </div>
                }
                @if (manifestValid && manifestHash) {
                  <div class="success-note">
                    <mat-icon>check_circle</mat-icon>
                    Manifest valid — keccak256:
                  </div>
                  <div class="hash-box">{{ manifestHash }}</div>
                }
                <div class="step-actions">
                  <button mat-raised-button color="primary"
                          [disabled]="!manifestRaw.trim() || validating || version.status !== 'DRAFT'"
                          (click)="validateManifest()">
                    <mat-icon>rule</mat-icon>
                    Validate manifest
                  </button>
                </div>
              </mat-step>

              <mat-step label="Permissions & claims" [completed]="manifestValid">
                <p class="hint">
                  These permissions were parsed from your manifest. Consumers see them (with your
                  rationale) before requesting grants from the registry operator.
                </p>
                @if (permissions.length === 0) {
                  <p class="hint">No gated permissions declared.</p>
                } @else {
                  <table mat-table [dataSource]="permissions" style="width:100%">
                    <ng-container matColumnDef="permissionCode">
                      <th mat-header-cell *matHeaderCellDef>Permission</th>
                      <td mat-cell *matCellDef="let p" style="font-family:'IBM Plex Mono',monospace;font-size:12px">
                        {{ p.permissionCode }}
                      </td>
                    </ng-container>
                    <ng-container matColumnDef="rationale">
                      <th mat-header-cell *matHeaderCellDef>Rationale</th>
                      <td mat-cell *matCellDef="let p" style="font-size:12px">{{ p.rationale ?? '—' }}</td>
                    </ng-container>
                    <ng-container matColumnDef="claimTopics">
                      <th mat-header-cell *matHeaderCellDef>Claims</th>
                      <td mat-cell *matCellDef="let p" style="font-size:12px">{{ p.claimTopics.join(', ') || '—' }}</td>
                    </ng-container>
                    <tr mat-header-row *matHeaderRowDef="permissionColumns"></tr>
                    <tr mat-row *matRowDef="let row; columns: permissionColumns"></tr>
                  </table>
                }
              </mat-step>

              <mat-step label="Payment methods">
                <p class="hint">
                  Declare how your dApp handles the cash leg by adding a <code>paymentMethods</code>
                  array to the manifest. Reference an operator-provided rail by code, or describe a
                  custom method you implement yourself — the operator sees both at review time.
                </p>
                @if (paymentMethods.length === 0) {
                  <p class="hint">No payment methods declared in the current manifest.</p>
                } @else {
                  <table mat-table [dataSource]="paymentMethods" style="width:100%">
                    <ng-container matColumnDef="method">
                      <th mat-header-cell *matHeaderCellDef>Method</th>
                      <td mat-cell *matCellDef="let m" style="font-size:12px">
                        {{ m.methodType === 'RAIL' ? m.displayName : m.customName }}
                      </td>
                    </ng-container>
                    <ng-container matColumnDef="type">
                      <th mat-header-cell *matHeaderCellDef>Type</th>
                      <td mat-cell *matCellDef="let m" style="font-size:12px">
                        {{ m.methodType === 'RAIL' ? (m.railType ?? '—') : 'Custom' }}
                      </td>
                    </ng-container>
                    <ng-container matColumnDef="currency">
                      <th mat-header-cell *matHeaderCellDef>Currency</th>
                      <td mat-cell *matCellDef="let m" style="font-size:12px">{{ m.currency ?? '—' }}</td>
                    </ng-container>
                    <tr mat-header-row *matHeaderRowDef="paymentColumns"></tr>
                    <tr mat-row *matRowDef="let row; columns: paymentColumns"></tr>
                  </table>
                }

                <div class="rail-reference">
                  <p class="rail-reference-title">Rails provided by the registry — copy a code into your manifest</p>
                  @for (rail of availableRails; track rail.code) {
                    <div class="rail-reference-row">
                      <span class="rail-code" (click)="copyRailCode(rail.code)" matTooltip="Click to copy">{{ rail.code }}</span>
                      <span class="rail-name">{{ rail.displayName }} · {{ rail.railType }} · {{ rail.currency }}</span>
                    </div>
                  }
                  @if (availableRails.length === 0) {
                    <p class="rail-name">No enabled payment rails yet — ask the registry operator to configure one, or use a custom method.</p>
                  }
                </div>
              </mat-step>

              <mat-step label="Sign" [completed]="version.signed">
                <p class="hint">
                  Sign the manifest hash with a wallet that is bound to your organization
                  (Company Admin → Organization). This proves authorship; anyone can verify
                  the signature against the onchain anchor.
                </p>
                <mat-form-field appearance="outline" style="width:100%">
                  <mat-label>Signer wallet (bound org wallet)</mat-label>
                  <input matInput [(ngModel)]="signerWallet" placeholder="0x…" />
                </mat-form-field>
                @if (hasBrowserWallet) {
                  <button mat-stroked-button color="primary"
                          [disabled]="!manifestValid || !isValidAddress(signerWallet) || signing"
                          (click)="signWithBrowserWallet()">
                    <mat-icon>account_balance_wallet</mat-icon>
                    Sign with browser wallet
                  </button>
                }
                <mat-form-field appearance="outline" style="width:100%;margin-top:12px">
                  <mat-label>Signature (0x…, paste if signing externally)</mat-label>
                  <textarea matInput rows="2" [(ngModel)]="signature"></textarea>
                </mat-form-field>
                <div class="step-actions">
                  <button mat-raised-button color="primary"
                          [disabled]="!manifestValid || !signature.trim() || !isValidAddress(signerWallet) || signing"
                          (click)="submitSignature()">
                    <mat-icon>draw</mat-icon>
                    Attach signature
                  </button>
                </div>
                @if (version.signed) {
                  <div class="success-note">
                    <mat-icon>check_circle</mat-icon>
                    Signed by {{ version.signerWallet }}
                  </div>
                }
              </mat-step>

              <mat-step label="Submit for review" [completed]="version.status !== 'DRAFT'">
                <p class="hint">
                  Submission hands the manifest to the registry operator for review (4-eyes).
                  On approval it is anchored onchain and the listing goes live in the catalog.
                </p>
                <div class="step-actions">
                  <button mat-raised-button color="primary"
                          [disabled]="!manifestValid || !version.signed || version.status !== 'DRAFT' || submitting"
                          (click)="submit()">
                    <mat-icon>send</mat-icon>
                    Submit for review
                  </button>
                </div>
                @if (version.status !== 'DRAFT') {
                  <div class="success-note">
                    <mat-icon>hourglass_top</mat-icon>
                    Version {{ version.version }} is {{ version.status }} — you'll be notified
                    once the operator completes the review.
                  </div>
                }
              </mat-step>
            </mat-stepper>

            @if (error) {
              <p class="error-message">{{ error }}</p>
            }
          </mat-card-content>
        </mat-card>
      } @else {
        <p class="error-message">{{ error || 'Listing not found.' }}</p>
      }
    </div>
  `,
})
export class PublishWizardComponent implements OnInit {
  private readonly marketplaceService = inject(MarketplaceService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly clipboard = inject(Clipboard);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  readonly permissionColumns = ['permissionCode', 'rationale', 'claimTopics'];
  readonly paymentColumns = ['method', 'type', 'currency'];

  listing: DappListingView | null = null;
  version: DappVersionView | null = null;
  permissions: DappRequiredPermissionView[] = [];
  paymentMethods: PaymentMethodView[] = [];
  availableRails: PaymentRailView[] = [];

  loading = true;
  error = '';
  manifestRaw = '';
  manifestValid = false;
  manifestHash: string | null = null;
  validationErrors: string[] = [];
  validating = false;

  signerWallet = '';
  signature = '';
  signing = false;
  submitting = false;

  get hasBrowserWallet(): boolean {
    return typeof (window as any).ethereum !== 'undefined';
  }

  ngOnInit(): void {
    this.marketplaceService.paymentRails().subscribe({
      next: (rails) => {
        this.availableRails = rails;
        this.cdr.markForCheck();
      },
    });

    const listingId = this.route.snapshot.paramMap.get('listingId')!;
    this.marketplaceService.getListing(listingId).subscribe({
      next: (listing) => {
        this.listing = listing;
        this.loadOrCreateDraftVersion();
      },
      error: (err) => {
        this.error = err?.error?.message ?? 'Failed to load listing.';
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  private loadOrCreateDraftVersion(): void {
    const listing = this.listing!;
    this.marketplaceService.versions(listing.id).subscribe({
      next: (versions) => {
        const open = versions.find((v) =>
          ['DRAFT', 'SUBMITTED', 'IN_REVIEW', 'APPROVED'].includes(v.status),
        );
        if (open) {
          this.version = open;
          this.manifestValid = open.manifestHash != null;
          this.manifestHash = open.manifestHash;
          this.loadPermissions();
          this.loadPaymentMethods();
          this.loading = false;
          this.cdr.markForCheck();
        } else {
          this.marketplaceService.createVersion(listing.id).subscribe({
            next: (version) => {
              this.version = version;
              this.loading = false;
              this.cdr.markForCheck();
            },
            error: (err) => {
              this.error = err?.error?.message ?? 'Failed to create a draft version.';
              this.loading = false;
              this.cdr.markForCheck();
            },
          });
        }
      },
      error: (err) => {
        this.error = err?.error?.message ?? 'Failed to load versions.';
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  private loadPermissions(): void {
    if (!this.listing || !this.version) return;
    this.marketplaceService.versionPermissions(this.listing.id, this.version.id).subscribe({
      next: (permissions) => {
        this.permissions = permissions;
        this.cdr.markForCheck();
      },
    });
  }

  private loadPaymentMethods(): void {
    if (!this.listing || !this.version) return;
    this.marketplaceService.versionPaymentMethods(this.listing.id, this.version.id).subscribe({
      next: (methods) => {
        this.paymentMethods = methods;
        this.cdr.markForCheck();
      },
    });
  }

  copyRailCode(code: string): void {
    this.clipboard.copy(code);
    this.snackBar.open(`Copied "${code}" — paste it into a paymentMethods entry as { "rail": "${code}" }.`,
      'Dismiss', { duration: 4000 });
  }

  isValidAddress(address: string): boolean {
    return /^0x[0-9a-fA-F]{40}$/.test(address.trim());
  }

  validateManifest(): void {
    if (!this.listing || !this.version) return;
    this.validating = true;
    this.error = '';

    this.marketplaceService.putManifest(this.listing.id, this.version.id, this.manifestRaw).subscribe({
      next: (result) => {
        this.validating = false;
        this.manifestValid = result.valid;
        this.manifestHash = result.manifestHash;
        this.validationErrors = result.errors;
        if (result.valid) {
          this.version = { ...this.version!, signed: false, signerWallet: null, manifestHash: result.manifestHash };
          this.loadPermissions();
          this.loadPaymentMethods();
        }
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.validating = false;
        this.error = err?.error?.message ?? 'Manifest validation failed.';
        this.cdr.markForCheck();
      },
    });
  }

  async signWithBrowserWallet(): Promise<void> {
    if (!this.manifestHash) return;
    this.error = '';
    try {
      const ethereum = (window as any).ethereum;
      const accounts: string[] = await ethereum.request({ method: 'eth_requestAccounts' });
      const wallet = this.signerWallet.trim().toLowerCase();
      const account = accounts.find((a) => a.toLowerCase() === wallet);
      if (!account) {
        this.error = `Browser wallet has no account ${wallet}. Switch accounts and retry.`;
        this.cdr.markForCheck();
        return;
      }
      this.signature = await ethereum.request({
        method: 'personal_sign',
        params: [this.manifestHash, account],
      });
      this.cdr.markForCheck();
      this.submitSignature();
    } catch (err: any) {
      this.error = err?.message ?? 'Browser wallet signing failed.';
      this.cdr.markForCheck();
    }
  }

  submitSignature(): void {
    if (!this.listing || !this.version) return;
    this.signing = true;
    this.error = '';

    this.marketplaceService
      .sign(this.listing.id, this.version.id, this.signature.trim(), this.signerWallet.trim())
      .subscribe({
        next: (version) => {
          this.signing = false;
          this.version = version;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.signing = false;
          this.error = err?.error?.message ?? 'Failed to attach signature.';
          this.cdr.markForCheck();
        },
      });
  }

  submit(): void {
    if (!this.listing || !this.version) return;
    this.submitting = true;
    this.error = '';

    this.marketplaceService.submit(this.listing.id, this.version.id).subscribe({
      next: (version) => {
        this.submitting = false;
        this.version = version;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.submitting = false;
        this.error = err?.error?.message ?? 'Failed to submit for review.';
        this.cdr.markForCheck();
      },
    });
  }
}
