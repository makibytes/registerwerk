import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, inject
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormBuilder, FormGroup, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatStepperModule } from '@angular/material/stepper';
import { MatSnackBar } from '@angular/material/snack-bar';
import { CommonModule } from '@angular/common';
import { BondService } from '../../../../core/api/bond.service';
import { AssetService } from '../../../../core/api/asset.service';
import {
  TokenStandard
} from '../../../../core/models';

@Component({
  selector: 'app-bond-issuance-wizard',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule, ReactiveFormsModule, FormsModule,
    MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatCheckboxModule, MatIconModule, MatStepperModule,
  ],
  template: `
    <div class="wizard-shell">
      <header class="wizard-header">
        <span class="wizard-badge">BOND ISSUANCE</span>
        <h1 class="wizard-title">New Bond Instrument</h1>
        <p class="wizard-sub">Configure terms and deploy on-chain</p>
      </header>

      <mat-stepper linear class="wizard-stepper" [selectedIndex]="step" (selectionChange)="step = $event.selectedIndex">

        <!-- Step 1: Choose Standard -->
        <mat-step label="Standard">
          <div class="step-body">
            <h2 class="step-heading">Select token standard</h2>
            <div class="standard-grid">
              @for (std of bondStandards; track std) {
                <button
                  class="std-card"
                  [class.selected]="selectedStandard === std"
                  (click)="selectedStandard = std">
                  <span class="std-tag">{{ stdLabel(std) }}</span>
                  <span class="std-desc">{{ stdDesc(std) }}</span>
                </button>
              }
            </div>
            <div class="step-actions">
              <button mat-flat-button class="btn-primary" [disabled]="!selectedStandard" matStepperNext>
                Next <mat-icon>arrow_forward</mat-icon>
              </button>
            </div>
          </div>
        </mat-step>

        <!-- Step 2: Bond Terms -->
        <mat-step label="Terms">
          <div class="step-body">
            <h2 class="step-heading">Bond terms</h2>
            <form [formGroup]="termsForm" class="form-grid">
              <mat-form-field appearance="outline" class="field-full">
                <mat-label>Asset name</mat-label>
                <input matInput formControlName="assetName" placeholder="Registerwerk Bond 2030">
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>Face value</mat-label>
                <input matInput type="number" formControlName="faceValue" placeholder="1000">
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>Currency</mat-label>
                <mat-select formControlName="currencyIso">
                  @for (c of currencies; track c) {
                    <mat-option [value]="c">{{ c }}</mat-option>
                  }
                </mat-select>
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>Issue date</mat-label>
                <input matInput type="date" formControlName="issueDate">
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>Maturity date</mat-label>
                <input matInput type="date" formControlName="maturityDate">
              </mat-form-field>

              @if (isFixed) {
                <mat-form-field appearance="outline">
                  <mat-label>Coupon rate (%)</mat-label>
                  <input matInput type="number" step="0.01" formControlName="couponRate" placeholder="5.00">
                </mat-form-field>
              }

              @if (isFloating) {
                <mat-form-field appearance="outline">
                  <mat-label>Reference rate</mat-label>
                  <mat-select formControlName="referenceRate">
                    <mat-option value="EURIBOR_3M">EURIBOR 3M</mat-option>
                    <mat-option value="EURIBOR_6M">EURIBOR 6M</mat-option>
                    <mat-option value="SONIA">SONIA</mat-option>
                    <mat-option value="SOFR">SOFR</mat-option>
                  </mat-select>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Spread (%)</mat-label>
                  <input matInput type="number" step="0.01" formControlName="spread" placeholder="1.50">
                </mat-form-field>
              }

              <mat-form-field appearance="outline">
                <mat-label>Day count</mat-label>
                <mat-select formControlName="dayCount">
                  <mat-option value="ACT_360">ACT/360</mat-option>
                  <mat-option value="ACT_365">ACT/365</mat-option>
                  <mat-option value="ACT_ACT_ICMA">ACT/ACT (ICMA)</mat-option>
                  <mat-option value="THIRTY_360">30/360</mat-option>
                </mat-select>
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>Payment frequency</mat-label>
                <mat-select formControlName="paymentFrequency">
                  <mat-option value="ANNUAL">Annual</mat-option>
                  <mat-option value="SEMI_ANNUAL">Semi-annual</mat-option>
                  <mat-option value="QUARTERLY">Quarterly</mat-option>
                  <mat-option value="ZERO">Zero-coupon</mat-option>
                </mat-select>
              </mat-form-field>

              <div class="checkbox-row">
                <mat-checkbox formControlName="callable" color="primary">Callable before maturity</mat-checkbox>
              </div>
            </form>
            <div class="step-actions">
              <button mat-stroked-button matStepperPrevious class="btn-back">Back</button>
              <button mat-flat-button class="btn-primary" [disabled]="termsForm.invalid" matStepperNext>
                Next <mat-icon>arrow_forward</mat-icon>
              </button>
            </div>
          </div>
        </mat-step>

        <!-- Step 3: Call Schedule (only for callable) -->
        <mat-step label="Call Schedule">
          <div class="step-body">
            <h2 class="step-heading">Call schedule
              <span class="heading-note">Optional — only for callable bonds</span>
            </h2>
            @if (termsForm.get('callable')?.value) {
              <div class="call-table">
                <div class="call-row header">
                  <span>Call date</span>
                  <span>Call price (%)</span>
                  <span></span>
                </div>
                @for (entry of callSchedule; track $index) {
                  <div class="call-row">
                    <input type="date" [(ngModel)]="entry.callDate" class="call-input">
                    <input type="number" [(ngModel)]="entry.callPrice" step="0.01" placeholder="101.00" class="call-input">
                    <button mat-icon-button (click)="removeCallEntry($index)" class="call-remove">
                      <mat-icon>remove_circle_outline</mat-icon>
                    </button>
                  </div>
                }
                <button mat-stroked-button (click)="addCallEntry()" class="btn-add-row">
                  <mat-icon>add</mat-icon> Add call date
                </button>
              </div>
            } @else {
              <p class="empty-note">No call schedule required — this bond is not callable.</p>
            }
            <div class="step-actions">
              <button mat-stroked-button matStepperPrevious class="btn-back">Back</button>
              <button mat-flat-button class="btn-primary" matStepperNext>
                Next <mat-icon>arrow_forward</mat-icon>
              </button>
            </div>
          </div>
        </mat-step>

        <!-- Step 4: Slot/Tranche (only for ERC-3525) -->
        <mat-step label="Tranches">
          <div class="step-body">
            <h2 class="step-heading">Bond tranches
              <span class="heading-note">ERC-3525 slot configuration</span>
            </h2>
            @if (isSft) {
              <div class="slot-form form-grid">
                <mat-form-field appearance="outline">
                  <mat-label>Slot ID (series identifier)</mat-label>
                  <input matInput [(ngModel)]="slotId" placeholder="1">
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Tranche name</mat-label>
                  <input matInput [(ngModel)]="slotName" placeholder="Series A — 5% 2030">
                </mat-form-field>
                <mat-form-field appearance="outline" class="field-full">
                  <mat-label>Supply cap (0 = unlimited)</mat-label>
                  <input matInput type="number" [(ngModel)]="slotSupplyCap" placeholder="100000000">
                </mat-form-field>
              </div>
            } @else {
              <p class="empty-note">Slot/tranche configuration is only needed for ERC-3525 semi-fungible tokens.</p>
            }
            <div class="step-actions">
              <button mat-stroked-button matStepperPrevious class="btn-back">Back</button>
              <button mat-flat-button class="btn-primary" matStepperNext>
                Review <mat-icon>arrow_forward</mat-icon>
              </button>
            </div>
          </div>
        </mat-step>

        <!-- Step 5: Review & Deploy -->
        <mat-step label="Deploy">
          <div class="step-body">
            <h2 class="step-heading">Review &amp; deploy</h2>
            <div class="review-card">
              <div class="review-row">
                <span class="review-label">Standard</span>
                <span class="review-value mono">{{ selectedStandard }}</span>
              </div>
              <div class="review-row">
                <span class="review-label">Asset</span>
                <span class="review-value">{{ termsForm.get('assetName')?.value }}</span>
              </div>
              <div class="review-row">
                <span class="review-label">Face value</span>
                <span class="review-value mono">{{ termsForm.get('faceValue')?.value | number }} {{ termsForm.get('currencyIso')?.value }}</span>
              </div>
              <div class="review-row">
                <span class="review-label">Maturity</span>
                <span class="review-value mono">{{ termsForm.get('maturityDate')?.value }}</span>
              </div>
              @if (isFixed) {
                <div class="review-row">
                  <span class="review-label">Coupon</span>
                  <span class="review-value mono">{{ termsForm.get('couponRate')?.value }}% {{ termsForm.get('paymentFrequency')?.value }}</span>
                </div>
              }
              @if (isFloating) {
                <div class="review-row">
                  <span class="review-label">Rate</span>
                  <span class="review-value mono">{{ termsForm.get('referenceRate')?.value }} + {{ termsForm.get('spread')?.value }}%</span>
                </div>
              }
              <div class="review-row">
                <span class="review-label">Day count</span>
                <span class="review-value mono">{{ termsForm.get('dayCount')?.value }}</span>
              </div>
            </div>

            <div class="deploy-warning">
              <mat-icon class="warn-icon">warning_amber</mat-icon>
              <span>Deploying a bond instrument is irreversible. Verify all terms before proceeding.</span>
            </div>

            <div class="step-actions">
              <button mat-stroked-button matStepperPrevious class="btn-back">Back</button>
              <button mat-flat-button class="btn-deploy" [disabled]="deploying" (click)="deploy()">
                @if (deploying) {
                  <span class="spinner"></span> Deploying…
                } @else {
                  <ng-container><mat-icon>rocket_launch</mat-icon> Deploy bond</ng-container>
                }
              </button>
            </div>
          </div>
        </mat-step>

      </mat-stepper>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      --accent: var(--rw-accent, #F59E0B);
      --bg-deep: var(--rw-sidebar-bg, #07091A);
      --surface: #0e1124;
      --border: rgba(245,158,11,.18);
    }

    .wizard-shell {
      max-width: 800px;
      margin: 0 auto;
      padding: 2rem 1.5rem 4rem;
    }

    .wizard-header {
      margin-bottom: 2.5rem;
    }

    .wizard-badge {
      font-family: 'IBM Plex Mono', monospace;
      font-size: .625rem;
      letter-spacing: .2em;
      color: var(--accent);
      background: rgba(245,158,11,.1);
      border: 1px solid var(--border);
      border-radius: 2px;
      padding: .25rem .75rem;
    }

    .wizard-title {
      font-family: 'Manrope Variable', sans-serif;
      font-size: 2rem;
      font-weight: 800;
      color: #f0f4ff;
      margin: .75rem 0 .25rem;
    }

    .wizard-sub {
      color: #7b8aac;
      font-size: .875rem;
      margin: 0;
    }

    .wizard-stepper {
      background: transparent;
    }

    ::ng-deep .mat-step-header .mat-step-icon-selected {
      background-color: var(--accent) !important;
    }

    ::ng-deep .mat-step-header .mat-step-icon {
      background-color: #1a1f3c;
    }

    .step-body {
      padding: 1.5rem 0;
    }

    .step-heading {
      font-family: 'Manrope Variable', sans-serif;
      font-size: 1.125rem;
      font-weight: 700;
      color: #e2e8f8;
      margin: 0 0 1.25rem;
      display: flex;
      align-items: baseline;
      gap: .75rem;
    }

    .heading-note {
      font-size: .75rem;
      font-weight: 400;
      color: #7b8aac;
    }

    .standard-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
      gap: .75rem;
      margin-bottom: 1.5rem;
    }

    .std-card {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: .375rem;
      padding: 1rem 1.25rem;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 6px;
      cursor: pointer;
      transition: border-color .15s, background .15s;
      text-align: left;
    }

    .std-card:hover { border-color: rgba(245,158,11,.4); }

    .std-card.selected {
      border-color: var(--accent);
      background: rgba(245,158,11,.06);
    }

    .std-tag {
      font-family: 'IBM Plex Mono', monospace;
      font-size: .75rem;
      color: var(--accent);
      font-weight: 600;
    }

    .std-desc {
      font-size: .75rem;
      color: #7b8aac;
      line-height: 1.4;
    }

    .form-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0 1rem;
    }

    .field-full { grid-column: 1 / -1; }

    .checkbox-row {
      grid-column: 1 / -1;
      padding: .25rem 0 .5rem;
    }

    .call-table {
      display: flex;
      flex-direction: column;
      gap: .5rem;
      margin-bottom: 1rem;
    }

    .call-row {
      display: grid;
      grid-template-columns: 1fr 1fr 40px;
      gap: .75rem;
      align-items: center;
    }

    .call-row.header {
      font-size: .75rem;
      color: #7b8aac;
      font-family: 'IBM Plex Mono', monospace;
      letter-spacing: .05em;
    }

    .call-input {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 4px;
      color: #e2e8f8;
      padding: .5rem .75rem;
      font-family: 'IBM Plex Mono', monospace;
      font-size: .875rem;
    }

    .call-input:focus { outline: 1px solid var(--accent); border-color: var(--accent); }

    .call-remove { color: #ef4444; }

    .btn-add-row {
      align-self: flex-start;
      color: var(--accent);
      border-color: var(--border);
      font-size: .8125rem;
    }

    .review-card {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 1.25rem;
      margin-bottom: 1.5rem;
    }

    .review-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: .625rem 0;
      border-bottom: 1px solid rgba(255,255,255,.04);
    }

    .review-row:last-child { border-bottom: none; }

    .review-label {
      font-size: .8125rem;
      color: #7b8aac;
    }

    .review-value {
      font-size: .875rem;
      color: #e2e8f8;
      font-weight: 500;
    }

    .review-value.mono { font-family: 'IBM Plex Mono', monospace; }

    .deploy-warning {
      display: flex;
      align-items: center;
      gap: .75rem;
      padding: .875rem 1rem;
      background: rgba(245,158,11,.06);
      border: 1px solid rgba(245,158,11,.3);
      border-radius: 6px;
      color: #f0c040;
      font-size: .8125rem;
      margin-bottom: 1.5rem;
    }

    .warn-icon { font-size: 1.25rem; height: 1.25rem; width: 1.25rem; }

    .step-actions {
      display: flex;
      justify-content: flex-end;
      gap: .75rem;
      margin-top: 1.5rem;
    }

    .btn-primary {
      background: var(--accent) !important;
      color: #07091A !important;
      font-weight: 700;
    }

    .btn-back {
      color: #a0aec0;
      border-color: var(--border);
    }

    .btn-deploy {
      background: #dc2626 !important;
      color: #fff !important;
      font-weight: 700;
      display: flex;
      align-items: center;
      gap: .5rem;
    }

    .btn-deploy:disabled { opacity: .5; }

    .spinner {
      width: 1rem; height: 1rem;
      border: 2px solid rgba(255,255,255,.3);
      border-top-color: #fff;
      border-radius: 50%;
      animation: spin .6s linear infinite;
      display: inline-block;
    }

    @keyframes spin { to { transform: rotate(360deg); } }

    .empty-note {
      color: #7b8aac;
      font-size: .875rem;
      padding: 1rem 0;
    }

    .slot-form { margin-bottom: 1rem; }

    ::ng-deep .mat-mdc-form-field .mdc-notched-outline__leading,
    ::ng-deep .mat-mdc-form-field .mdc-notched-outline__notch,
    ::ng-deep .mat-mdc-form-field .mdc-notched-outline__trailing {
      border-color: var(--border) !important;
    }

    ::ng-deep .mat-mdc-form-field.mat-focused .mdc-notched-outline__leading,
    ::ng-deep .mat-mdc-form-field.mat-focused .mdc-notched-outline__notch,
    ::ng-deep .mat-mdc-form-field.mat-focused .mdc-notched-outline__trailing {
      border-color: var(--accent) !important;
    }

    ::ng-deep .mat-mdc-form-field input,
    ::ng-deep .mat-mdc-form-field .mat-mdc-select-value-text {
      color: #e2e8f8 !important;
    }

    ::ng-deep .mat-mdc-form-field .mat-mdc-floating-label { color: #7b8aac !important; }
    ::ng-deep .mat-mdc-form-field.mat-focused .mat-mdc-floating-label { color: var(--accent) !important; }
  `]
})
export class BondIssuanceWizardComponent {
  private readonly bondService = inject(BondService);
  private readonly assetService = inject(AssetService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly fb = inject(FormBuilder);

  step = 0;
  deploying = false;

  readonly bondStandards: TokenStandard[] = [
    'ERC3525', 'DAML_BOND_FIXED', 'DAML_BOND_FLOATING', 'DAML_BOND_ZERO',
    'SPL_2022_BOND', 'STARKNET_ERC3525',
  ];

  readonly currencies = ['EUR', 'USD', 'GBP', 'CHF'];

  selectedStandard: TokenStandard | null = null;
  callSchedule: { callDate: string; callPrice: number }[] = [];
  slotId = '1';
  slotName = '';
  slotSupplyCap = 0;

  readonly termsForm: FormGroup = this.fb.group({
    assetName: ['', Validators.required],
    faceValue: [1000, [Validators.required, Validators.min(1)]],
    currencyIso: ['EUR', Validators.required],
    issueDate: ['', Validators.required],
    maturityDate: ['', Validators.required],
    couponRate: [null],
    referenceRate: [null],
    spread: [null],
    dayCount: ['ACT_360', Validators.required],
    paymentFrequency: ['SEMI_ANNUAL', Validators.required],
    callable: [false],
  });

  get isFixed(): boolean { return this.selectedStandard === 'DAML_BOND_FIXED' || this.selectedStandard === 'ERC3525' || this.selectedStandard === 'SPL_2022_BOND' || this.selectedStandard === 'STARKNET_ERC3525'; }
  get isFloating(): boolean { return this.selectedStandard === 'DAML_BOND_FLOATING'; }
  get isZero(): boolean { return this.selectedStandard === 'DAML_BOND_ZERO'; }
  get isSft(): boolean { return this.selectedStandard === 'ERC3525' || this.selectedStandard === 'STARKNET_ERC3525'; }

  stdLabel(std: TokenStandard): string {
    const map: Record<string, string> = {
      ERC3525: 'ERC-3525', DAML_BOND_FIXED: 'DAML Fixed', DAML_BOND_FLOATING: 'DAML Floating',
      DAML_BOND_ZERO: 'DAML Zero', SPL_2022_BOND: 'SPL-2022 Bond', STARKNET_ERC3525: 'Cairo SFT',
    };
    return map[std] ?? std;
  }

  stdDesc(std: TokenStandard): string {
    const map: Record<string, string> = {
      ERC3525: 'Semi-fungible on EVM — slot+value, ideal for bond tranches',
      DAML_BOND_FIXED: 'Fixed-rate on Canton via DAML Finance',
      DAML_BOND_FLOATING: 'Floating-rate on Canton, FRN with rate-fixing',
      DAML_BOND_ZERO: 'Zero-coupon on Canton, single maturity payment',
      SPL_2022_BOND: 'Interest-bearing on Solana with regulatory controls',
      STARKNET_ERC3525: 'Semi-fungible on Starknet, Cairo ERC-3525',
    };
    return map[std] ?? '';
  }

  addCallEntry(): void {
    this.callSchedule.push({ callDate: '', callPrice: 100 });
  }

  removeCallEntry(index: number): void {
    this.callSchedule.splice(index, 1);
  }

  deploy(): void {
    if (!this.selectedStandard) return;
    const assetId = this.route.snapshot.paramMap.get('id') ?? '';
    this.deploying = true;
    this.cdr.markForCheck();

    const termsPayload = {
      ...this.termsForm.value,
      callSchedule: this.callSchedule,
    };

    this.bondService.saveBondTerms(assetId, termsPayload).subscribe({
      next: () => {
        this.assetService.deployAsset(assetId, {
          chain: 'ETHEREUM', network: 'TESTNET', tokenStandard: this.selectedStandard!,
        }).subscribe({
          next: () => {
            this.snackBar.open('Bond deployment initiated', 'Dismiss', { duration: 4000 });
            this.router.navigate(['..'], { relativeTo: this.route });
          },
          error: (err) => {
            this.snackBar.open(err?.error?.message ?? 'Deployment failed', 'Dismiss', { duration: 6000 });
            this.deploying = false;
            this.cdr.markForCheck();
          },
        });
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Failed to save bond terms', 'Dismiss', { duration: 6000 });
        this.deploying = false;
        this.cdr.markForCheck();
      },
    });
  }
}
