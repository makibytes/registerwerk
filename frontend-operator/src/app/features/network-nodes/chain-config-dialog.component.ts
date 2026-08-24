import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { ChainConfig, ChainHealth } from '../../core/models';
import { ChainConfigCreateRequest, ChainConfigUpdateRequest } from '../../core/api/chain.service';

/** The "chain config screen" — previously finalityModel/avgBlockSeconds and the RPC/WS/explorer/
 *  graph URLs were only reachable by a direct API call, unreachable from the product itself.
 *  Handles both create (a brand-new ChainConfig) and edit (PATCH an existing one) — create needs
 *  identifier/chainType/networkType/rpcUrl, which edit never changes. finalitySource has no input
 *  control at all: it is fully auto-derived backend-side from whether the chain has an enabled
 *  chaincache-kind RpcNode (see RpcNodeService#recomputeFinalitySource) and is only ever shown
 *  read-only, on edit, so the operator can see it without being able to set it wrong. */
@Component({
  selector: 'app-chain-config-dialog',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatIconModule,
  ],
  styles: [`
    h2 { margin: 0 0 4px; font-size: 17px; font-weight: 700; }
    .subtitle { font-size: 13px; color: var(--rw-text-muted); margin: 0 0 20px; }
    mat-form-field { width: 100%; }
    .row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
    .section-label {
      font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.5px;
      color: var(--rw-text-muted); margin: 16px 0 8px;
    }
    .hint { font-size: 12px; color: var(--rw-text-muted); margin: -8px 0 16px; }
    .derived-source {
      display: flex; align-items: center; gap: 8px;
      padding: 10px 12px; margin-bottom: 4px;
      border-radius: 6px; background: var(--rw-bg); border: 1px solid var(--rw-border);
      font-size: 12px; color: var(--rw-text-secondary);
    }
    .derived-source mat-icon { font-size: 16px; width: 16px; height: 16px; color: var(--rw-accent); }
    .derived-source strong { color: var(--rw-text-primary); }
  `],
  template: `
    <div mat-dialog-title style="padding: 20px 24px 0">
      <h2>{{ isEdit ? 'Chain Settings' : 'Add Chain' }}</h2>
      <p class="subtitle">{{ isEdit ? data.chain!.identifier : 'Register a new blockchain configuration' }}</p>
    </div>
    <mat-dialog-content style="padding: 0 24px 8px">
      @if (!isEdit) {
        <mat-form-field appearance="outline">
          <mat-label>Identifier (e.g. ETHEREUM_MAINNET)</mat-label>
          <input matInput [(ngModel)]="identifier" />
        </mat-form-field>
        <div class="row">
          <mat-form-field appearance="outline">
            <mat-label>Chain type</mat-label>
            <mat-select [(ngModel)]="chainType">
              <mat-option value="EVM">EVM</mat-option>
              <mat-option value="SOLANA">Solana</mat-option>
              <mat-option value="STARKNET">Starknet</mat-option>
              <mat-option value="STELLAR">Stellar</mat-option>
              <mat-option value="CANTON">Canton</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Network type</mat-label>
            <mat-select [(ngModel)]="networkType">
              <mat-option value="MAINNET">Mainnet</mat-option>
              <mat-option value="TESTNET">Testnet</mat-option>
            </mat-select>
          </mat-form-field>
        </div>
        <mat-form-field appearance="outline">
          <mat-label>RPC URL</mat-label>
          <input matInput [(ngModel)]="rpcUrl" type="url" />
        </mat-form-field>
      }

      <mat-form-field appearance="outline">
        <mat-label>Display name</mat-label>
        <input matInput [(ngModel)]="displayName" />
      </mat-form-field>

      <div class="section-label">Finality</div>
      <div class="row">
        <mat-form-field appearance="outline">
          <mat-label>Finality model</mat-label>
          <mat-select [(ngModel)]="finalityModel">
            <mat-option value="TAG_BASED">Tag-based (safe/finalized)</mat-option>
            <mat-option value="DEPTH_BASED">Depth-based (confirmations)</mat-option>
            <mat-option value="INSTANT">Instant (permissioned BFT)</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Avg block seconds</mat-label>
          <input matInput [(ngModel)]="avgBlockSeconds" type="number" min="1" />
        </mat-form-field>
      </div>

      @if (isEdit) {
        <div class="derived-source">
          <mat-icon>{{ data.chain!.finalitySource === 'CHAINCACHE' ? 'bolt' : 'lan' }}</mat-icon>
          <span>
            @if (data.chain!.finalitySource === 'CHAINCACHE' && chaincacheWorkload()) {
              Finality source: <strong>chaincache workload {{ chaincacheWorkload()!.managementUrl }}</strong>
              (chain key <strong>{{ chaincacheWorkload()!.remoteChainKey }}</strong>) — SAFE/FINALIZED
              promotions and reorg retractions are pushed from that workload's durable event stream.
            } @else if (data.chain!.finalitySource === 'CHAINCACHE') {
              Finality source: <strong>a chaincache workload</strong> (durable event stream)
            } @else {
              Finality source: <strong>this registry's own RPC polling</strong>
            }
          </span>
        </div>
        <p class="hint">
          Auto-derived — switches to chaincache the moment this chain has an enabled
          chaincache-kind RPC node, and back the moment it doesn't. Add or remove a node from the
          node list to change it; there is nothing to set here.
        </p>
      }
    </mat-dialog-content>
    <mat-dialog-actions style="padding: 0 24px 20px">
      <div class="actions">
        <button type="button" mat-button mat-dialog-close>Cancel</button>
        <button type="button" mat-flat-button color="primary" [disabled]="!canSubmit()" (click)="submit()">
          {{ isEdit ? 'Save' : 'Create Chain' }}
        </button>
      </div>
    </mat-dialog-actions>
  `,
})
export class ChainConfigDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<ChainConfigDialogComponent>);
  readonly data: { chain?: ChainConfig; chainHealth?: ChainHealth } = inject(MAT_DIALOG_DATA);

  readonly isEdit = !!this.data.chain;

  identifier = '';
  chainType = 'EVM';
  networkType = 'TESTNET';
  rpcUrl = '';
  displayName = this.data.chain?.displayName ?? '';
  finalityModel = this.data.chain?.finalityModel ?? 'DEPTH_BASED';
  avgBlockSeconds: number | null = this.data.chain?.avgBlockSeconds ?? null;

  /** The enabled chaincache-kind node behind {@link ChainConfig#finalitySource}, if any — used
   *  only to name the actual workload in the derived-source explanation above; picking among
   *  more than one is a display nicety here (any lists first), unlike the backend's own real
   *  routing/connection choice in RpcNodeService/ChaincacheDurableStreamManager. */
  chaincacheWorkload() {
    return this.data.chainHealth?.nodes.find(n => n.kind === 'CHAINCACHE' && n.enabled);
  }

  canSubmit(): boolean {
    if (this.isEdit) return !!this.displayName.trim();
    return !!this.identifier.trim() && !!this.rpcUrl.trim() && !!this.displayName.trim();
  }

  submit() {
    if (!this.canSubmit()) return;
    if (this.isEdit) {
      const request: ChainConfigUpdateRequest = {
        displayName: this.displayName.trim(),
        finalityModel: this.finalityModel,
        avgBlockSeconds: this.avgBlockSeconds ?? undefined,
      };
      this.dialogRef.close(request);
    } else {
      const request: ChainConfigCreateRequest = {
        identifier: this.identifier.trim(),
        displayName: this.displayName.trim(),
        chainType: this.chainType,
        networkType: this.networkType,
        rpcUrl: this.rpcUrl.trim(),
        finalityModel: this.finalityModel,
        avgBlockSeconds: this.avgBlockSeconds ?? undefined,
      };
      this.dialogRef.close(request);
    }
  }
}
