import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, TemplateRef, ViewChild, inject
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe, DecimalPipe } from '@angular/common';
import { AssetService } from '../../../../core/api/asset.service';
import { InvestorLimitService } from '../../../../core/api/investor-limit.service';
import { Asset, InvestorLimit } from '../../../../core/models';

/**
 * Per-investor eligibility limits (F-BLOCKER-12) — previously the only limits were ERC-3643
 * on-chain compliance modules, token-wide and read-only. Sets the asset's own min-investment/
 * max-holding defaults plus per-investor overrides (cornerstone exceptions, lockups).
 */
@Component({
  selector: 'app-investor-limits',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatIconModule, MatDialogModule, MatFormFieldModule,
            MatInputModule, MatTooltipModule, DatePipe, DecimalPipe],
  template: `
    <div class="il-shell">
      <div class="il-header">
        <h3 class="il-title">Asset defaults</h3>
      </div>
      @if (asset) {
        <div class="defaults-row">
          <mat-form-field appearance="outline">
            <mat-label>Minimum investment</mat-label>
            <input matInput type="number" min="0" [(ngModel)]="defaultsForm.minInvestmentAmount">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Maximum holding</mat-label>
            <input matInput type="number" min="0" [(ngModel)]="defaultsForm.maxHoldingAmount">
          </mat-form-field>
          <button mat-raised-button color="primary" [disabled]="savingDefaults" (click)="saveDefaults()">
            {{ savingDefaults ? 'Saving…' : 'Save defaults' }}
          </button>
        </div>
        <p class="dimmed small">
          Applies to every investor unless overridden below. Leave blank for unrestricted.
        </p>
      }

      <div class="il-header" style="margin-top:2rem">
        <h3 class="il-title">Per-investor overrides</h3>
        <button mat-stroked-button (click)="openLimitDialog()">
          <mat-icon>add</mat-icon> Add override
        </button>
      </div>

      @if (loading) {
        <p class="dimmed" style="text-align:center;padding:24px">Loading…</p>
      } @else if (limits.length === 0) {
        <div class="empty-state">
          <mat-icon class="empty-icon">rule</mat-icon>
          <p>No per-investor overrides — every investor uses the asset defaults.</p>
        </div>
      } @else {
        <div class="il-table">
          <div class="il-row header">
            <span>Investor entity</span>
            <span>Min investment</span>
            <span>Max holding</span>
            <span>Lockup until</span>
            <span></span>
          </div>
          @for (l of limits; track l.id) {
            <div class="il-row">
              <span class="dimmed small mono">{{ l.investorEntityId }}</span>
              <span>{{ l.minInvestmentOverride !== null ? (l.minInvestmentOverride | number) : '—' }}</span>
              <span>{{ l.maxHoldingOverride !== null ? (l.maxHoldingOverride | number) : '—' }}</span>
              <span>{{ l.lockupUntil ? (l.lockupUntil | date:'dd MMM yyyy') : '—' }}</span>
              <div class="row-actions">
                <button mat-icon-button matTooltip="Edit" (click)="openLimitDialog(l)">
                  <mat-icon>edit</mat-icon>
                </button>
                <button mat-icon-button color="warn" matTooltip="Remove" (click)="removeLimit(l)">
                  <mat-icon>delete</mat-icon>
                </button>
              </div>
            </div>
          }
        </div>
      }
    </div>

    <ng-template #limitDialogTpl>
      <h2 mat-dialog-title>{{ editingLimit ? 'Edit' : 'Add' }} Investor Override</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:400px">
        <mat-form-field appearance="outline">
          <mat-label>Investor entity ID</mat-label>
          <input matInput [(ngModel)]="limitForm.investorEntityId" [disabled]="!!editingLimit">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Minimum investment override</mat-label>
          <input matInput type="number" min="0" [(ngModel)]="limitForm.minInvestmentOverride">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Maximum holding override</mat-label>
          <input matInput type="number" min="0" [(ngModel)]="limitForm.maxHoldingOverride">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Lockup until</mat-label>
          <input matInput type="date" [(ngModel)]="limitForm.lockupUntil">
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary" [disabled]="!limitForm.investorEntityId.trim()" (click)="submitLimit()">
          Save
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    :host { display: block; }
    .il-shell { padding: 1.5rem 0; }
    .il-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 1.25rem; }
    .il-title { font-size: 1rem; font-weight: 700; margin: 0; }
    .dimmed { color: var(--rw-text-secondary); }
    .small { font-size: .75rem; }
    .mono { font-family: 'IBM Plex Mono', 'Courier New', monospace; }
    .defaults-row { display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .empty-state { display: flex; flex-direction: column; align-items: center; padding: 3rem 0; color: var(--rw-text-secondary); }
    .empty-icon { font-size: 2.5rem; height: 2.5rem; width: 2.5rem; margin-bottom: .75rem; opacity: .6; }

    .il-table { display: flex; flex-direction: column; }
    .il-row {
      display: grid;
      grid-template-columns: 1.6fr 1fr 1fr 130px 90px;
      gap: .5rem;
      align-items: center;
      padding: .625rem .5rem;
      border-bottom: 1px solid var(--rw-border);
      font-size: .8125rem;
    }
    .il-row.header {
      font-size: .6875rem;
      letter-spacing: .06em;
      text-transform: uppercase;
      color: var(--rw-text-muted);
    }
    .row-actions { display: flex; justify-content: flex-end; gap: 4px; }
  `],
})
export class InvestorLimitsComponent implements OnInit {
  @Input() assetId!: string;
  @ViewChild('limitDialogTpl') limitDialogTpl!: TemplateRef<unknown>;

  private readonly assetService = inject(AssetService);
  private readonly investorLimitService = inject(InvestorLimitService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  asset: Asset | null = null;
  limits: InvestorLimit[] = [];
  loading = false;
  savingDefaults = false;

  defaultsForm: { minInvestmentAmount: number | null; maxHoldingAmount: number | null } =
    { minInvestmentAmount: null, maxHoldingAmount: null };

  editingLimit: InvestorLimit | null = null;
  limitForm: { investorEntityId: string; minInvestmentOverride: number | null; maxHoldingOverride: number | null; lockupUntil: string } =
    { investorEntityId: '', minInvestmentOverride: null, maxHoldingOverride: null, lockupUntil: '' };

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.assetService.getAsset(this.assetId).subscribe({
      next: (asset) => {
        this.asset = asset;
        this.defaultsForm = {
          minInvestmentAmount: asset.minInvestmentAmount ?? null,
          maxHoldingAmount: asset.maxHoldingAmount ?? null,
        };
        this.cdr.markForCheck();
      },
      error: () => this.cdr.markForCheck(),
    });
    this.investorLimitService.listForAsset(this.assetId).subscribe({
      next: (limits) => {
        this.limits = limits;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => { this.loading = false; this.cdr.markForCheck(); },
    });
  }

  saveDefaults(): void {
    this.savingDefaults = true;
    this.assetService.updateAsset(this.assetId, {
      minInvestmentAmount: this.defaultsForm.minInvestmentAmount,
      maxHoldingAmount: this.defaultsForm.maxHoldingAmount,
    }).subscribe({
      next: (asset) => {
        this.asset = asset;
        this.savingDefaults = false;
        this.snackBar.open('Asset defaults saved.', 'Dismiss', { duration: 4000 });
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.savingDefaults = false;
        this.snackBar.open(err?.error?.message ?? 'Failed to save defaults.', 'Dismiss', { duration: 5000 });
        this.cdr.markForCheck();
      },
    });
  }

  openLimitDialog(limit?: InvestorLimit): void {
    this.editingLimit = limit ?? null;
    this.limitForm = limit
      ? {
          investorEntityId: limit.investorEntityId,
          minInvestmentOverride: limit.minInvestmentOverride,
          maxHoldingOverride: limit.maxHoldingOverride,
          lockupUntil: limit.lockupUntil ? limit.lockupUntil.substring(0, 10) : '',
        }
      : { investorEntityId: '', minInvestmentOverride: null, maxHoldingOverride: null, lockupUntil: '' };
    this.dialog.open(this.limitDialogTpl, { width: '460px' });
  }

  submitLimit(): void {
    const investorEntityId = this.limitForm.investorEntityId.trim();
    if (!investorEntityId) return;
    this.dialog.closeAll();
    this.investorLimitService.setLimit(this.assetId, investorEntityId, {
      minInvestmentOverride: this.limitForm.minInvestmentOverride,
      maxHoldingOverride: this.limitForm.maxHoldingOverride,
      lockupUntil: this.limitForm.lockupUntil || null,
    }).subscribe({
      next: () => {
        this.snackBar.open('Investor limit saved.', 'Dismiss', { duration: 4000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to save limit.', 'Dismiss', { duration: 5000 }),
    });
  }

  removeLimit(limit: InvestorLimit): void {
    this.investorLimitService.deleteLimit(this.assetId, limit.investorEntityId).subscribe({
      next: () => {
        this.snackBar.open('Investor limit removed.', 'Dismiss', { duration: 4000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to remove limit.', 'Dismiss', { duration: 5000 }),
    });
  }
}
