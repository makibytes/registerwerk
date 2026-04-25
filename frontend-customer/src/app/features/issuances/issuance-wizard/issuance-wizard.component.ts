import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { FormsModule, ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { MatStepperModule } from '@angular/material/stepper';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar } from '@angular/material/snack-bar';
import { IssuanceService } from '../../../core/api/issuance.service';
import { Chain, Network, OnchainLevel, TokenStandard } from '../../../core/models';

@Component({
  selector: 'app-issuance-wizard',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    FormsModule,
    ReactiveFormsModule,
    MatStepperModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatDividerModule,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>New Issuance</h1>
        <a mat-button routerLink="/issuances">
          <mat-icon>arrow_back</mat-icon>
          Back to List
        </a>
      </div>

      <mat-card>
        <mat-card-content>
          <mat-stepper [linear]="true" #stepper>

            <!-- ── Step 1: Details ──────────────────────────────────────────── -->
            <mat-step [stepControl]="detailsForm" label="Details">
              <form [formGroup]="detailsForm" class="step-form">
                <mat-form-field appearance="outline" class="full-width">
                  <mat-label>Issuance Name *</mat-label>
                  <input matInput formControlName="name" placeholder="e.g. Green Bond 2025" />
                  @if (detailsForm.get('name')?.hasError('required') && detailsForm.get('name')?.touched) {
                    <mat-error>Name is required</mat-error>
                  }
                </mat-form-field>

                <mat-form-field appearance="outline" class="full-width">
                  <mat-label>ISIN (optional)</mat-label>
                  <input matInput formControlName="isin" placeholder="DE000XXXXXX0" />
                  <mat-hint>12-character International Securities Identification Number</mat-hint>
                  @if (detailsForm.get('isin')?.hasError('pattern')) {
                    <mat-error>ISIN must be 12 alphanumeric characters</mat-error>
                  }
                </mat-form-field>

                <mat-form-field appearance="outline" class="full-width">
                  <mat-label>Onchain Level *</mat-label>
                  <mat-select formControlName="onchainLevel">
                    @for (lvl of onchainLevels; track lvl.value) {
                      <mat-option [value]="lvl.value">{{ lvl.label }}</mat-option>
                    }
                  </mat-select>
                  <mat-hint>Defines the degree of on-chain representation</mat-hint>
                </mat-form-field>

                <div class="step-actions">
                  <button mat-raised-button color="primary" matStepperNext [disabled]="detailsForm.invalid">
                    Next
                    <mat-icon>arrow_forward</mat-icon>
                  </button>
                </div>
              </form>
            </mat-step>

            <!-- ── Step 2: Chain & Standard ────────────────────────────────── -->
            <mat-step [stepControl]="chainForm" label="Chain &amp; Standard">
              <form [formGroup]="chainForm" class="step-form">
                <mat-form-field appearance="outline" class="full-width">
                  <mat-label>Blockchain *</mat-label>
                  <mat-select formControlName="chain" (valueChange)="onChainChange($event)">
                    @for (c of chains; track c.value) {
                      <mat-option [value]="c.value">{{ c.label }}</mat-option>
                    }
                  </mat-select>
                </mat-form-field>

                <mat-form-field appearance="outline" class="full-width">
                  <mat-label>Network *</mat-label>
                  <mat-select formControlName="network">
                    <mat-option value="MAINNET">Mainnet</mat-option>
                    <mat-option value="TESTNET">Testnet</mat-option>
                  </mat-select>
                </mat-form-field>

                <mat-form-field appearance="outline" class="full-width">
                  <mat-label>Token Standard *</mat-label>
                  <mat-select formControlName="tokenStandard">
                    @for (std of filteredStandards; track std.value) {
                      <mat-option [value]="std.value">{{ std.label }}</mat-option>
                    }
                  </mat-select>
                </mat-form-field>

                <div class="step-actions">
                  <button mat-button matStepperPrevious>Back</button>
                  <button mat-raised-button color="primary" matStepperNext [disabled]="chainForm.invalid">
                    Next
                    <mat-icon>arrow_forward</mat-icon>
                  </button>
                </div>
              </form>
            </mat-step>

            <!-- ── Step 3: Review & Submit ─────────────────────────────────── -->
            <mat-step label="Review &amp; Submit">
              <div class="step-form">
                <h3>Review Your Issuance</h3>

                <div class="review-grid">
                  <div class="review-item">
                    <span class="review-label">Name</span>
                    <span class="review-value">{{ detailsForm.get('name')?.value }}</span>
                  </div>
                  <div class="review-item">
                    <span class="review-label">ISIN</span>
                    <span class="review-value">{{ detailsForm.get('isin')?.value || '—' }}</span>
                  </div>
                  <div class="review-item">
                    <span class="review-label">Onchain Level</span>
                    <span class="review-value">{{ detailsForm.get('onchainLevel')?.value }}</span>
                  </div>
                  <div class="review-item">
                    <span class="review-label">Chain</span>
                    <span class="review-value">{{ chainForm.get('chain')?.value }}</span>
                  </div>
                  <div class="review-item">
                    <span class="review-label">Network</span>
                    <span class="review-value">{{ chainForm.get('network')?.value }}</span>
                  </div>
                  <div class="review-item">
                    <span class="review-label">Token Standard</span>
                    <span class="review-value">
                      <mat-chip>{{ chainForm.get('tokenStandard')?.value }}</mat-chip>
                    </span>
                  </div>
                </div>

                <mat-divider style="margin: 24px 0"></mat-divider>

                @if (submitError) {
                  <p class="error-message">{{ submitError }}</p>
                }

                <div class="step-actions">
                  <button mat-button matStepperPrevious>Back</button>
                  <button
                    mat-raised-button
                    color="primary"
                    [disabled]="submitting"
                    (click)="submit()"
                  >
                    @if (submitting) {
                      <mat-spinner diameter="18"></mat-spinner>
                    } @else {
                      <ng-container>
                        <mat-icon>send</mat-icon>
                        Create Issuance
                      </ng-container>
                    }
                  </button>
                </div>
              </div>
            </mat-step>

          </mat-stepper>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .step-form { padding: 24px 0; display: flex; flex-direction: column; gap: 4px; }
    .full-width { width: 100%; }
    .step-actions { display: flex; gap: 12px; align-items: center; margin-top: 24px; }
    .review-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .review-item { display: flex; flex-direction: column; }
    .review-label { font-size: 12px; color: #78909c; text-transform: uppercase; letter-spacing: 0.5px; }
    .review-value { font-size: 15px; font-weight: 500; color: #37474f; margin-top: 4px; }
    .error-message { color: #c62828; font-size: 13px; }
  `]
})
export class IssuanceWizardComponent {
  private readonly fb = inject(FormBuilder);
  private readonly issuanceService = inject(IssuanceService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);

  submitting = false;
  submitError = '';

  // ── Form groups ────────────────────────────────────────────────────────────

  readonly detailsForm = this.fb.group({
    name:         ['', Validators.required],
    isin:         ['', [Validators.pattern(/^[A-Z0-9]{12}$/)]],
    onchainLevel: ['SIMPLE' as OnchainLevel, Validators.required],
  });

  readonly chainForm = this.fb.group({
    chain:         ['ETHEREUM' as Chain, Validators.required],
    network:       ['TESTNET' as Network, Validators.required],
    tokenStandard: ['ERC20' as TokenStandard, Validators.required],
  });

  // ── Select options ─────────────────────────────────────────────────────────

  readonly onchainLevels: { value: OnchainLevel; label: string }[] = [
    { value: 'NONE', label: 'None — Off-chain record only' },
    { value: 'SIMPLE', label: 'Simple — Tokenized primary-market issuance' },
    { value: 'CONTROL', label: 'Control — On-chain control and approvals' },
  ];

  readonly chains: { value: Chain; label: string }[] = [
    { value: 'ETHEREUM', label: 'Ethereum' },
    { value: 'POLYGON',  label: 'Polygon' },
    { value: 'BASE',     label: 'Base' },
    { value: 'SOLANA',   label: 'Solana' },
  ];

  private readonly standardsByChain: Record<Chain, { value: TokenStandard; label: string }[]> = {
    ETHEREUM: [
      { value: 'ERC20',        label: 'ERC-20' },
      { value: 'ERC3643',      label: 'ERC-3643 (T-REX)' },
      { value: 'CONF_ERC20',   label: 'Confidential ERC-20 (ERC-7984, Zama fhEVM)' },
      { value: 'CONF_ERC3643', label: 'Confidential ERC-3643 (Zama fhEVM + T-REX)' },
    ],
    POLYGON: [
      { value: 'ERC20',        label: 'ERC-20' },
      { value: 'ERC3643',      label: 'ERC-3643 (T-REX)' },
      { value: 'CONF_ERC20',   label: 'Confidential ERC-20 (ERC-7984, Zama fhEVM)' },
      { value: 'CONF_ERC3643', label: 'Confidential ERC-3643 (Zama fhEVM + T-REX)' },
    ],
    BASE: [
      { value: 'ERC20',        label: 'ERC-20' },
      { value: 'CONF_ERC20',   label: 'Confidential ERC-20 (ERC-7984, Zama fhEVM)' },
    ],
    SOLANA: [
      { value: 'SPL', label: 'SPL Token' },
    ],
  };

  filteredStandards = this.standardsByChain['ETHEREUM'];

  onChainChange(chain: Chain): void {
    this.filteredStandards = this.standardsByChain[chain] ?? [];
    this.chainForm.patchValue({ tokenStandard: this.filteredStandards[0]?.value ?? null });
  }

  // ── Submit ─────────────────────────────────────────────────────────────────

  submit(): void {
    this.submitting = true;
    this.submitError = '';

    const body = {
      name:          this.detailsForm.value.name!,
      isin:          this.detailsForm.value.isin || null,
      onchainLevel:  this.detailsForm.value.onchainLevel!,
      chain:         this.chainForm.value.chain!,
      network:       this.chainForm.value.network!,
      tokenStandard: this.chainForm.value.tokenStandard!,
    };

    this.issuanceService.createIssuance(body).subscribe({
      next: (asset) => {
        this.submitting = false;
        this.snackBar.open('Issuance created successfully!', 'OK', { duration: 3000 });
        this.router.navigate(['/issuances', asset.id]);
      },
      error: (err) => {
        this.submitting = false;
        this.submitError = err?.error?.message ?? 'Failed to create issuance. Please try again.';
      },
    });
  }
}
