import { ChangeDetectorRef, Component, inject, OnInit } from '@angular/core';
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
import { IssuanceService, IssuanceCreateRequest } from '../../../core/api/issuance.service';
import { KycService } from '../../../core/api/kyc.service';
import { Chain, Network, OnchainLevel, TokenStandard, Jurisdiction, JurisdictionRequirement } from '../../../core/models';

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
                  <mat-label>Jurisdiction *</mat-label>
                  <mat-select formControlName="jurisdiction">
                    @for (j of jurisdictions; track j.value) {
                      <mat-option [value]="j.value">{{ j.label }}</mat-option>
                    }
                  </mat-select>
                  <mat-hint>Regulatory jurisdiction for this security issuance</mat-hint>
                </mat-form-field>
                @if (jurisdictionProfilesError) {
                  <p class="error-message" role="alert">{{ jurisdictionProfilesError }}</p>
                }

                @if (selectedJurisdictionProfile) {
                  <div class="jurisdiction-requirements">
                    <div class="jur-req-header">
                      <mat-icon style="font-size:16px">policy</mat-icon>
                      Required KYC documents for {{ selectedJurisdictionProfile.displayName }}
                    </div>
                    @for (req of selectedJurisdictionProfile.requirements; track req.documentType) {
                      @if (req.mandatory) {
                        <div class="jur-req-item">
                          <mat-icon style="font-size:13px;color:#6366f1">chevron_right</mat-icon>
                          {{ req.localName }}
                          @if (req.maxAgeDays) { <span>(≤ {{ req.maxAgeDays }} days old)</span> }
                        </div>
                      }
                    }
                  </div>
                }

                <mat-form-field appearance="outline" class="full-width">
                  <mat-label>Onchain Level *</mat-label>
                  <mat-select formControlName="onchainLevel">
                    @for (lvl of onchainLevels; track lvl.value) {
                      <mat-option [value]="lvl.value">{{ lvl.label }}</mat-option>
                    }
                  </mat-select>
                  <mat-hint>Defines the degree of on-chain representation</mat-hint>
                </mat-form-field>

                <mat-divider style="margin: 16px 0"></mat-divider>
                <h3 class="section-heading">Economic Terms</h3>
                <p class="section-hint">
                  Optional at this stage, but statements, valuations, and tax reporting have
                  nothing to work from until these are set — either here or later on the asset.
                </p>

                <div class="form-row">
                  <mat-form-field appearance="outline">
                    <mat-label>Currency</mat-label>
                    <input matInput formControlName="currency" placeholder="EUR" maxlength="3"
                      style="text-transform:uppercase" (input)="onCurrencyInput($event)" />
                    <mat-hint>ISO-4217 code</mat-hint>
                    @if (detailsForm.get('currency')?.hasError('pattern')) {
                      <mat-error>3-letter ISO code, e.g. EUR</mat-error>
                    }
                  </mat-form-field>

                  <mat-form-field appearance="outline">
                    <mat-label>Issue Size</mat-label>
                    <input matInput type="number" formControlName="issueSize" placeholder="50000000" />
                    <mat-hint>Total nominal amount</mat-hint>
                  </mat-form-field>

                  <mat-form-field appearance="outline">
                    <mat-label>Denomination</mat-label>
                    <input matInput type="number" formControlName="denomination" placeholder="1000" />
                    <mat-hint>Minimum tradable unit</mat-hint>
                  </mat-form-field>
                </div>

                <div class="form-row">
                  <mat-form-field appearance="outline">
                    <mat-label>Issue Date</mat-label>
                    <input matInput type="date" formControlName="issueDate" />
                  </mat-form-field>

                  <mat-form-field appearance="outline">
                    <mat-label>Maturity Date</mat-label>
                    <input matInput type="date" formControlName="maturityDate" />
                    <mat-hint>Leave blank for perpetual/equity-like instruments</mat-hint>
                  </mat-form-field>
                </div>

                <!-- Optional term sheet upload -->
                <div class="termsheet-upload-section">
                  <div class="termsheet-label">
                    <mat-icon style="font-size:18px;width:18px;height:18px;color:var(--rw-text-muted)">description</mat-icon>
                    <span>Term Sheet <span style="color:var(--rw-text-muted);font-size:12px">(optional — required by eWpG)</span></span>
                  </div>
                  @if (selectedTermSheetFile) {
                    <div class="termsheet-file-selected">
                      <mat-icon style="color:#10b981">check_circle</mat-icon>
                      <span>{{ selectedTermSheetFile.name }}</span>
                      <button mat-icon-button type="button" (click)="clearTermSheet()">
                        <mat-icon>close</mat-icon>
                      </button>
                    </div>
                  } @else {
                    <label>
                      <input #tsInput type="file"
                        accept=".pdf,.html,.htm,.txt,.md,.json,.xml,.docx"
                        style="display:none"
                        (change)="onTermSheetSelected($event)" />
                      <button mat-stroked-button type="button" (click)="tsInput.click()">
                        <mat-icon>upload_file</mat-icon>
                        Choose file
                      </button>
                    </label>
                    <span style="font-size:11px;color:var(--rw-text-muted)">PDF, HTML, TXT, Markdown, JSON, XML, DOCX</span>
                  }
                </div>

                <div class="step-actions">
                  <button mat-raised-button color="primary" type="button" matStepperNext [disabled]="detailsForm.invalid">
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
                      <mat-option [value]="c.value" [disabled]="c.comingSoon === true">
                        {{ c.label }}@if (c.comingSoon) { <span class="coming-soon-badge"> — Coming soon</span>}
                      </mat-option>
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
                      <mat-option [value]="std.value" [disabled]="std.comingSoon === true">
                        {{ std.label }}@if (std.comingSoon) { <span class="coming-soon-badge"> — Coming soon</span>}
                      </mat-option>
                    }
                  </mat-select>
                </mat-form-field>

                <div class="step-actions">
                  <button mat-button type="button" matStepperPrevious>Back</button>
                  <button mat-raised-button color="primary" type="button" matStepperNext [disabled]="chainForm.invalid">
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
                    <span class="review-label">Currency</span>
                    <span class="review-value">{{ detailsForm.get('currency')?.value || '—' }}</span>
                  </div>
                  <div class="review-item">
                    <span class="review-label">Issue Size</span>
                    <span class="review-value">{{ detailsForm.get('issueSize')?.value || '—' }}</span>
                  </div>
                  <div class="review-item">
                    <span class="review-label">Denomination</span>
                    <span class="review-value">{{ detailsForm.get('denomination')?.value || '—' }}</span>
                  </div>
                  <div class="review-item">
                    <span class="review-label">Issue Date</span>
                    <span class="review-value">{{ detailsForm.get('issueDate')?.value || '—' }}</span>
                  </div>
                  <div class="review-item">
                    <span class="review-label">Maturity Date</span>
                    <span class="review-value">{{ detailsForm.get('maturityDate')?.value || '—' }}</span>
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
                  <button mat-button type="button" matStepperPrevious>Back</button>
                  <button
                    mat-raised-button
                    color="primary"
                    type="button"
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
    .section-heading { font-size: 14px; font-weight: 600; margin: 0 0 2px; color: var(--rw-text-primary); }
    .section-hint { font-size: 12px; color: var(--rw-text-muted); margin: 0 0 12px; }
    .form-row { display: flex; gap: 16px; }
    .form-row mat-form-field { flex: 1; }
    .coming-soon-badge { font-size: 0.8em; opacity: 0.6; font-style: italic; }
    .full-width { width: 100%; }
    .step-actions { display: flex; gap: 12px; align-items: center; margin-top: 24px; }
    .review-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .review-item { display: flex; flex-direction: column; }
    .review-label { font-size: 12px; color: var(--rw-text-muted); text-transform: uppercase; letter-spacing: 0.5px; }
    .review-value { font-size: 15px; font-weight: 500; color: var(--rw-text-primary); margin-top: 4px; }
    .error-message { color: var(--rw-text-danger); font-size: 13px; }
    .jurisdiction-requirements {
      border: 1px solid rgba(99,102,241,0.25);
      border-radius: 8px;
      padding: 12px 14px;
      background: rgba(99,102,241,0.04);
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .jur-req-header {
      display: flex; align-items: center; gap: 6px;
      font-size: 12px; font-weight: 600; color: #6366f1; margin-bottom: 6px;
    }
    .jur-req-item {
      display: flex; align-items: center; gap: 4px;
      font-size: 12px; color: var(--rw-text-secondary);
      span { color: var(--rw-text-muted); margin-left: 4px; }
    }
    .termsheet-upload-section {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 14px 16px;
      border-radius: 8px;
      border: 1px dashed var(--rw-border);
      background: var(--rw-surface-soft);
      margin-top: 4px;
    }
    .termsheet-label {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 13px;
      font-weight: 500;
    }
    .termsheet-file-selected {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
      color: var(--rw-text-success);
    }
  `]
})
export class IssuanceWizardComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly issuanceService = inject(IssuanceService);
  private readonly kycService = inject(KycService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  submitting = false;
  submitError = '';
  selectedTermSheetFile: File | null = null;
  jurisdictionProfiles: JurisdictionRequirement[] = [];
  jurisdictionProfilesError = '';

  readonly jurisdictions: { value: Jurisdiction; label: string }[] = [
    { value: 'DE_EWPG', label: 'Germany — eWpG / BaFin' },
    { value: 'LU_CSSF', label: 'Luxembourg — CSSF' },
    { value: 'FR_AMF',  label: 'France — AMF' },
    { value: 'LI_TVTG', label: 'Liechtenstein — TVTG / FMA' },
  ];

  get selectedJurisdictionProfile(): JurisdictionRequirement | null {
    const jur = this.detailsForm.get('jurisdiction')?.value as Jurisdiction | null;
    return this.jurisdictionProfiles.find(p => p.jurisdiction === jur) ?? null;
  }

  ngOnInit(): void {
    this.kycService.getJurisdictionRequirements().subscribe({
      next: (profiles) => { this.jurisdictionProfiles = profiles; this.cdr.markForCheck(); },
      error: () => {
        this.jurisdictionProfilesError = 'Jurisdiction-specific document requirements could not be loaded.';
        this.cdr.markForCheck();
      },
    });
  }

  // ── Form groups ────────────────────────────────────────────────────────────

  readonly detailsForm = this.fb.group({
    name:         ['', [Validators.required, Validators.maxLength(200)]],
    isin:         ['', [Validators.pattern(/^[A-Z0-9]{12}$/)]],
    jurisdiction: [null as Jurisdiction | null, Validators.required],
    onchainLevel: ['SIMPLE' as OnchainLevel, Validators.required],
    currency:     ['', [Validators.pattern(/^[A-Z]{3}$/)]],
    issueSize:    [null as number | null, Validators.min(0.00000001)],
    denomination: [null as number | null, Validators.min(0.00000001)],
    issueDate:    [''],
    maturityDate: [''],
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

  readonly chains: { value: Chain; label: string; comingSoon?: true }[] = [
    { value: 'ETHEREUM',  label: 'Ethereum' },
    { value: 'POLYGON',   label: 'Polygon' },
    { value: 'BASE',      label: 'Base' },
    { value: 'FHENIX',    label: 'Fhenix' },
    { value: 'INCO',      label: 'Inco' },
    { value: 'SOLANA',    label: 'Solana' },
    { value: 'ARBITRUM',  label: 'Arbitrum One' },
    { value: 'AVALANCHE', label: 'Avalanche C-Chain' },
    { value: 'OPTIMISM',  label: 'Optimism' },
    { value: 'STARKNET',  label: 'Starknet' },
    { value: 'STELLAR',   label: 'Stellar' },
    { value: 'CANTON',    label: 'Canton' },
  ];

  private readonly standardsByChain: Record<Chain, { value: TokenStandard; label: string; comingSoon?: true }[]> = {
    ETHEREUM: [
      { value: 'ERC20',        label: 'ERC-20' },
      { value: 'ERC3643',      label: 'ERC-3643 (T-REX)' },
    ],
    POLYGON: [
      { value: 'ERC20',        label: 'ERC-20' },
      { value: 'ERC3643',      label: 'ERC-3643 (T-REX)' },
    ],
    BASE: [
      { value: 'ERC20',        label: 'ERC-20' },
    ],
    FHENIX: [
      { value: 'CONF_ERC20',   label: 'Confidential ERC-20 (ERC-7984, Zama fhEVM)' },
      { value: 'CONF_ERC3643', label: 'Confidential ERC-3643 (Zama fhEVM + T-REX)' },
    ],
    INCO: [
      { value: 'CONF_ERC20',   label: 'Confidential ERC-20 (ERC-7984, Zama fhEVM)' },
      { value: 'CONF_ERC3643', label: 'Confidential ERC-3643 (Zama fhEVM + T-REX)' },
    ],
    SOLANA: [
      { value: 'SPL',      label: 'SPL Token' },
      { value: 'SPL_2022', label: 'SPL Token-2022 (Token Extensions)' },
    ],
    ARBITRUM: [
      { value: 'ERC20',        label: 'ERC-20' },
      { value: 'ERC3643',      label: 'ERC-3643 (T-REX)' },
    ],
    AVALANCHE: [
      { value: 'ERC20',        label: 'ERC-20' },
      { value: 'ERC3643',      label: 'ERC-3643 (T-REX)' },
    ],
    OPTIMISM: [
      { value: 'ERC20',        label: 'ERC-20' },
      { value: 'ERC3643',      label: 'ERC-3643 (T-REX)' },
    ],
    STARKNET: [
      { value: 'STARKNET_ERC20', label: 'Cairo ERC-20 (via UDC)' },
    ],
    STELLAR: [
      { value: 'STELLAR_ASSET', label: 'Stellar Asset (classic trustline)' },
    ],
    CANTON: [
      { value: 'CANTON_TOKEN', label: 'Canton / Daml Token Standard (CIP-0056)' },
    ],
  };

  filteredStandards = this.standardsByChain['ETHEREUM'];

  onCurrencyInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value.toUpperCase();
    this.detailsForm.get('currency')?.setValue(value, { emitEvent: false });
  }

  onChainChange(chain: Chain): void {
    this.filteredStandards = this.standardsByChain[chain] ?? [];
    this.chainForm.patchValue({ tokenStandard: this.filteredStandards[0]?.value ?? null });
  }

  // ── Submit ─────────────────────────────────────────────────────────────────

  submit(): void {
    if (this.submitting) return;
    if (this.detailsForm.invalid || this.chainForm.invalid) {
      this.detailsForm.markAllAsTouched();
      this.chainForm.markAllAsTouched();
      this.submitError = 'Complete all required fields before creating the issuance.';
      return;
    }
    const issueDate = this.detailsForm.value.issueDate;
    const maturityDate = this.detailsForm.value.maturityDate;
    if (issueDate && maturityDate && maturityDate < issueDate) {
      this.submitError = 'Maturity date cannot be before the issue date.';
      return;
    }
    this.submitting = true;
    this.submitError = '';

    const body: IssuanceCreateRequest = {
      name:          this.detailsForm.value.name!.trim(),
      isin:          this.detailsForm.value.isin?.trim().toUpperCase() || null,
      jurisdiction:  this.detailsForm.value.jurisdiction || null,
      onchainLevel:  this.detailsForm.value.onchainLevel!,
      chain:         this.chainForm.value.chain!,
      network:       this.chainForm.value.network!,
      tokenStandard: this.chainForm.value.tokenStandard!,
      currency:      this.detailsForm.value.currency || null,
      issueSize:     this.detailsForm.value.issueSize ?? null,
      denomination:  this.detailsForm.value.denomination ?? null,
      issueDate:     this.detailsForm.value.issueDate || null,
      maturityDate:  this.detailsForm.value.maturityDate || null,
    };

    this.issuanceService.createIssuance(body).subscribe({
      next: (asset) => {
        if (this.selectedTermSheetFile) {
          this.issuanceService.uploadDocument(asset.id, this.selectedTermSheetFile).subscribe({
            next: () => this.finishCreation(asset.id, 'Issuance and term sheet created successfully.'),
            error: () => {
              this.snackBar.open('Issuance created, but term sheet upload failed.', 'Close', { duration: 6000 });
              this.finishCreation(asset.id);
            },
          });
        } else {
          this.finishCreation(asset.id, 'Issuance created successfully!');
        }
      },
      error: (err) => {
        this.submitting = false;
        this.submitError = err?.error?.message ?? 'Failed to create issuance. Please try again.';
        this.cdr.markForCheck();
      },
    });
  }

  onTermSheetSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    input.value = '';
    if (file && file.size > 20 * 1024 * 1024) {
      this.selectedTermSheetFile = null;
      this.snackBar.open('The term sheet must not exceed 20 MB.', 'Close', { duration: 5000 });
      return;
    }
    this.selectedTermSheetFile = file;
  }

  clearTermSheet(): void {
    this.selectedTermSheetFile = null;
  }

  private finishCreation(assetId: string, message?: string): void {
    this.submitting = false;
    this.cdr.markForCheck();
    if (message) this.snackBar.open(message, 'OK', { duration: 3000 });
    this.router.navigate(['/issuances', assetId]);
  }
}
