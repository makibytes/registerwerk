import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';

import { ChainService } from '../../../core/api/chain.service';
import { ChainHealth, PaymentRailRequest, PaymentRailType, PaymentRailView } from '../../../core/models';

export interface RailFormDialogData {
  rail: PaymentRailView | null;
}

interface ChainAddressRow {
  chainConfigId: string | null;
  tokenAddress: string;
}

@Component({
  selector: 'app-rail-form-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatTooltipModule,
  ],
  styles: [`
    .form-grid { display: flex; flex-direction: column; gap: 12px; padding-top: 8px; min-width: 520px; }
    .row-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .hint-text { margin: 0; font-size: 12px; color: var(--rw-text-secondary); }
    .section-label {
      margin: 8px 0 -4px;
      font-size: 11px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.6px;
      color: var(--rw-text-muted);
    }
    .chain-row { display: grid; grid-template-columns: 1fr 2fr auto; gap: 8px; align-items: center; }
    .load-error { color: var(--rw-text-danger); font-size: 12px; }
    @media (max-width: 620px) {
      .form-grid { min-width: 0; }
      .row-2, .chain-row { grid-template-columns: 1fr; }
    }
  `],
  template: `
    <h2 mat-dialog-title>{{ isEdit ? 'Edit payment rail' : 'Add payment rail' }}</h2>
    <mat-dialog-content class="form-grid">
      <div class="row-2">
        <mat-form-field appearance="outline">
          <mat-label>Code</mat-label>
          <input matInput [(ngModel)]="code" [disabled]="isEdit" placeholder="e.g. aueur" />
          <mat-hint>Manifest-facing, immutable after creation</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Rail type</mat-label>
          <mat-select [(ngModel)]="railType">
            <mat-option value="STABLECOIN">Stablecoin (MiCAR EMT)</mat-option>
            <mat-option value="PONTES_API">Pontes instant-payment API</mat-option>
            <mat-option value="ERC7573_DVP">ERC-7573 DvP settlement</mat-option>
            <mat-option value="OFFCHAIN_SEPA">Off-chain SEPA</mat-option>
          </mat-select>
        </mat-form-field>
      </div>

      <mat-form-field appearance="outline">
        <mat-label>Display name</mat-label>
        <input matInput [(ngModel)]="displayName" placeholder="e.g. AllUnity Euro (AUEUR)" />
      </mat-form-field>

      <div class="row-2">
        <mat-form-field appearance="outline">
          <mat-label>Currency (ISO-4217)</mat-label>
          <input matInput [(ngModel)]="currency" placeholder="EUR" maxlength="3" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Decimals</mat-label>
          <input matInput type="number" [(ngModel)]="decimals" placeholder="6" />
        </mat-form-field>
      </div>

      <mat-form-field appearance="outline">
        <mat-label>Description</mat-label>
        <textarea matInput rows="2" [(ngModel)]="description"></textarea>
      </mat-form-field>

      @if (railType === 'STABLECOIN') {
        <p class="section-label">MiCAR issuer information</p>
        <div class="row-2">
          <mat-form-field appearance="outline">
            <mat-label>Issuer name</mat-label>
            <input matInput [(ngModel)]="issuerName" placeholder="e.g. AllUnity GmbH" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Issuer LEI</mat-label>
            <input matInput [(ngModel)]="issuerLei" />
          </mat-form-field>
        </div>
        <mat-form-field appearance="outline">
          <mat-label>MiCAR authorization reference</mat-label>
          <input matInput [(ngModel)]="micarAuthorization"
                 placeholder="e.g. BaFin e-money institution licence — MiCAR Title IV EMT" />
        </mat-form-field>
        <mat-slide-toggle [(ngModel)]="emtFlag">Qualifies as an e-money token (EMT) under MiCAR Title IV</mat-slide-toggle>
        <p class="section-label">Holder-facing disclosure (MiCAR Title IV)</p>
        <mat-form-field appearance="outline">
          <mat-label>White paper URL</mat-label>
          <input matInput [(ngModel)]="whitePaperUrl" placeholder="https://…/whitepaper.pdf" />
          <mat-hint>Art. 51 — the issuer's crypto-asset white paper investors are entitled to see</mat-hint>
        </mat-form-field>
        <mat-slide-toggle [(ngModel)]="redemptionAtPar">Issuer guarantees redemption at par at any time (Art. 49)</mat-slide-toggle>
      }

      <p class="section-label">Onchain deployment</p>
      @for (row of chainRows; track $index) {
        <div class="chain-row">
          <mat-form-field appearance="outline">
            <mat-label>Chain</mat-label>
            <mat-select [(ngModel)]="row.chainConfigId">
              @for (chain of chains(); track chain.id) {
                <mat-option [value]="chain.id">{{ chain.displayName }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Contract address</mat-label>
            <input matInput [(ngModel)]="row.tokenAddress" placeholder="0x…" />
          </mat-form-field>
          <button type="button" mat-icon-button (click)="removeChainRow($index)" matTooltip="Remove">
            <mat-icon>delete</mat-icon>
          </button>
        </div>
      }
      <button type="button" mat-stroked-button (click)="addChainRow()" style="align-self:flex-start">
        <mat-icon>add</mat-icon>
        Add chain address
      </button>
      @if (chainsLoadError()) {
        <div class="load-error" role="alert">
          Chains could not be loaded.
          <button mat-button type="button" (click)="loadChains()">Retry</button>
        </div>
      }
      <p class="hint-text">Off-chain rails (Pontes API, SEPA) need no chain address.</p>
    </mat-dialog-content>
    <mat-dialog-actions style="justify-content:flex-end;gap:8px">
      <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
      <button type="button" mat-raised-button color="primary" [disabled]="!isValid()" (click)="submit()">
        <mat-icon>save</mat-icon>
        {{ isEdit ? 'Save changes' : 'Create rail' }}
      </button>
    </mat-dialog-actions>
  `,
})
export class RailFormDialogComponent implements OnInit {
  private readonly chainService = inject(ChainService);
  private readonly dialogRef = inject(MatDialogRef<RailFormDialogComponent, PaymentRailRequest | undefined>);

  readonly isEdit: boolean;

  code = '';
  displayName = '';
  railType: PaymentRailType = 'STABLECOIN';
  currency = 'EUR';
  decimals: number | null = 6;
  description = '';
  issuerName = '';
  issuerLei = '';
  micarAuthorization = '';
  emtFlag = false;
  whitePaperUrl = '';
  redemptionAtPar = false;
  chainRows: ChainAddressRow[] = [];
  readonly chains = signal<ChainHealth[]>([]);
  readonly chainsLoadError = signal(false);

  readonly data = inject<RailFormDialogData>(MAT_DIALOG_DATA);

  constructor() {
    const data = this.data;
    this.isEdit = !!data.rail;
    if (data.rail) {
      const r = data.rail;
      this.code = r.code;
      this.displayName = r.displayName;
      this.railType = r.railType;
      this.currency = r.currency;
      this.decimals = r.decimals;
      this.description = r.description ?? '';
      this.issuerName = r.issuerName ?? '';
      this.issuerLei = r.issuerLei ?? '';
      this.micarAuthorization = r.micarAuthorization ?? '';
      this.emtFlag = r.emtFlag;
      this.whitePaperUrl = r.whitePaperUrl ?? '';
      this.redemptionAtPar = r.redemptionAtPar;
      this.chainRows = r.chainAddresses.map((a) => ({
        chainConfigId: a.chainConfigId,
        tokenAddress: a.tokenAddress,
      }));
    }
  }

  ngOnInit(): void {
    this.loadChains();
  }

  loadChains(): void {
    this.chainsLoadError.set(false);
    this.chainService.getHealth().subscribe({
      next: (chains) => this.chains.set(chains.filter((c) => c.chainType === 'EVM' && c.enabled)),
      error: () => this.chainsLoadError.set(true),
    });
  }

  addChainRow(): void {
    this.chainRows.push({ chainConfigId: null, tokenAddress: '' });
  }

  removeChainRow(index: number): void {
    this.chainRows.splice(index, 1);
  }

  isValid(): boolean {
    if (!this.code.trim() || !this.displayName.trim() || !/^[A-Z]{3}$/.test(this.currency.trim())) {
      return false;
    }
    return this.chainRows.every(
      (row) => row.chainConfigId && /^0x[0-9a-fA-F]{40}$/.test(row.tokenAddress.trim()),
    );
  }

  submit(): void {
    const body: PaymentRailRequest = {
      code: this.code.trim().toLowerCase(),
      displayName: this.displayName.trim(),
      railType: this.railType,
      currency: this.currency.trim().toUpperCase(),
      decimals: this.decimals,
      description: this.description.trim() || null,
      issuerName: this.railType === 'STABLECOIN' ? this.issuerName.trim() || null : null,
      issuerLei: this.railType === 'STABLECOIN' ? this.issuerLei.trim() || null : null,
      micarAuthorization: this.railType === 'STABLECOIN' ? this.micarAuthorization.trim() || null : null,
      emtFlag: this.railType === 'STABLECOIN' ? this.emtFlag : false,
      whitePaperUrl: this.railType === 'STABLECOIN' ? this.whitePaperUrl.trim() || null : null,
      redemptionAtPar: this.railType === 'STABLECOIN' ? this.redemptionAtPar : false,
      chainAddresses: this.chainRows
        .filter((row) => row.chainConfigId)
        .map((row) => ({ chainConfigId: row.chainConfigId!, tokenAddress: row.tokenAddress.trim() })),
    };
    this.dialogRef.close(body);
  }
}
