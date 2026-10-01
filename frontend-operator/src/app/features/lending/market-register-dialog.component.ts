import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { AssetService } from '../../core/api/asset.service';
import { ChainService } from '../../core/api/chain.service';
import { RegisterMarketRequest } from '../../core/api/lending-admin.service';
import { PaymentRailService } from '../../core/api/payment-rail.service';
import { Asset, ChainHealth, PageResponse, PaymentRailView } from '../../core/models';

/**
 * Collects the registration of an already-deployed lending market. Economic parameters are read from the
 * contract by the backend, which also checks that the market was produced by the configured factory, that its
 * collateral token is the asset's confirmed deployment on that chain and that its loan token is the chosen
 * enabled stablecoin rail's address; any mismatch is refused.
 */
@Component({
  selector: 'app-market-register-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    <h2 mat-dialog-title>Register lending market</h2>
    <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:480px;max-width:100%">
      <p style="margin:0;font-size:12px;color:var(--rw-text-secondary)">
        Registering needs step-up and a second approver. The backend verifies the market against the factory, the
        asset's confirmed deployment and the payment rail before accepting it.
      </p>
      <mat-form-field appearance="outline">
        <mat-label>Chain</mat-label>
        <mat-select [(ngModel)]="chainConfigId">
          @for (chain of chains; track chain.id) {
            <mat-option [value]="chain.id">{{ chain.displayName }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Market contract address</mat-label>
        <input matInput [(ngModel)]="marketAddress" placeholder="0x…" style="font-family:'IBM Plex Mono',monospace">
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Vault address (optional)</mat-label>
        <input matInput [(ngModel)]="vaultAddress" placeholder="0x…" style="font-family:'IBM Plex Mono',monospace">
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Collateral asset (issued)</mat-label>
        <mat-select [(ngModel)]="collateralAssetId">
          @for (asset of assets; track asset.id) {
            <mat-option [value]="asset.id">{{ asset.name }} ({{ asset.assetNumber }})</mat-option>
          }
        </mat-select>
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Loan payment rail (enabled stablecoin)</mat-label>
        <mat-select [(ngModel)]="loanRailCode">
          @for (rail of rails; track rail.code) {
            <mat-option [value]="rail.code">{{ rail.displayName }} ({{ rail.code }})</mat-option>
          }
        </mat-select>
      </mat-form-field>
      @if (loadError) { <p role="alert" style="margin:0;color:var(--rw-text-danger);font-size:12px">Some choices could not be loaded.</p> }
    </mat-dialog-content>
    <mat-dialog-actions style="justify-content:flex-end;gap:8px">
      <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
      <button type="button" mat-raised-button color="primary" [disabled]="!isValid()" (click)="submit()">Continue</button>
    </mat-dialog-actions>
  `,
})
export class MarketRegisterDialogComponent implements OnInit {
  private readonly ref = inject(MatDialogRef<MarketRegisterDialogComponent, RegisterMarketRequest>);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly chainService = inject(ChainService);
  private readonly assetService = inject(AssetService);
  private readonly railService = inject(PaymentRailService);

  chains: ChainHealth[] = [];
  assets: Asset[] = [];
  rails: PaymentRailView[] = [];
  loadError = false;

  chainConfigId = '';
  marketAddress = '';
  vaultAddress = '';
  collateralAssetId = '';
  loanRailCode = '';

  ngOnInit(): void {
    const markFailed = (): void => { this.loadError = true; };
    const emptyPage: PageResponse<Asset> = { content: [], totalElements: 0, totalPages: 0, page: 0, size: 0 };
    forkJoin({
      chains: this.chainService.getHealth().pipe(catchError(() => { markFailed(); return of<ChainHealth[]>([]); })),
      assets: this.assetService.getAssets({ status: 'ISSUED', size: 200 }).pipe(
        catchError(() => { markFailed(); return of(emptyPage); })),
      rails: this.railService.list().pipe(catchError(() => { markFailed(); return of<PaymentRailView[]>([]); })),
    }).subscribe(({ chains, assets, rails }) => {
      this.chains = chains.filter((c) => c.chainType === 'EVM' && c.enabled);
      this.assets = assets.content;
      this.rails = rails.filter((r) => r.railType === 'STABLECOIN' && r.enabled);
      this.cdr.markForCheck();
    });
  }

  private static readonly ADDRESS = /^0x[0-9a-fA-F]{40}$/;

  isValid(): boolean {
    return !!this.chainConfigId && !!this.collateralAssetId && !!this.loanRailCode
      && MarketRegisterDialogComponent.ADDRESS.test(this.marketAddress.trim())
      && (!this.vaultAddress.trim() || MarketRegisterDialogComponent.ADDRESS.test(this.vaultAddress.trim()));
  }

  submit(): void {
    if (!this.isValid()) return;
    this.ref.close({
      chainConfigId: this.chainConfigId,
      marketAddress: this.marketAddress.trim(),
      vaultAddress: this.vaultAddress.trim() || null,
      collateralAssetId: this.collateralAssetId,
      loanRailCode: this.loanRailCode,
    } satisfies RegisterMarketRequest);
  }
}
