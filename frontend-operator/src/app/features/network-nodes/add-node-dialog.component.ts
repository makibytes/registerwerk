import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ChainHealth, RpcNode, RpcNodeKind } from '../../core/models';
import { RpcNodeWriteRequest } from '../../core/api/chain.service';

/** Add or edit an RPC node — kind-aware since a CHAINCACHE connection needs two more fields
 *  (managementUrl for the capability probe + durable stream, remoteChainKey to pick which of
 *  chaincache's multi-chain keys this ChainConfig maps to) that a DIRECT_RPC node has no use for. */
@Component({
  selector: 'app-add-node-dialog',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
  ],
  styles: [`
    h2 { margin: 0 0 4px; font-size: 17px; font-weight: 700; }
    .subtitle { font-size: 13px; color: var(--rw-text-muted); margin: 0 0 20px; }
    mat-form-field { width: 100%; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
    .kind-toggle { display: block; margin-bottom: 16px; }
    .kind-toggle mat-button-toggle { font-size: 12px; }
    .hint { font-size: 12px; color: var(--rw-text-muted); margin: -8px 0 16px; }
  `],
  template: `
    <div mat-dialog-title style="padding: 20px 24px 0">
      <h2>{{ isEdit ? 'Edit' : 'Add' }} RPC Node</h2>
      <p class="subtitle">{{ data.chain.displayName }}</p>
    </div>
    <mat-dialog-content style="padding: 0 24px 8px">
      <mat-button-toggle-group class="kind-toggle" [(ngModel)]="kind" name="kind" aria-label="Node kind">
        <mat-button-toggle value="DIRECT_RPC">Direct RPC</mat-button-toggle>
        <mat-button-toggle value="CHAINCACHE">chaincache</mat-button-toggle>
      </mat-button-toggle-group>

      @if (kind === 'CHAINCACHE') {
        <p class="hint">
          A chaincache connection gives push-based, gap-free retraction detection and a real SAFE
          tier — a direct node connection can miss a short-lived reorg between polls and has no
          SAFE tier without a <code>safe</code> block tag.
        </p>
      }

      <mat-form-field appearance="outline">
        <mat-label>{{ kind === 'CHAINCACHE' ? 'Per-chain RPC URL (e.g. http://chaincache:8080/anvil/rpc)' : 'RPC URL' }}</mat-label>
        <input matInput [(ngModel)]="url" placeholder="https://mainnet.infura.io/v3/..." type="url" />
      </mat-form-field>

      @if (kind === 'CHAINCACHE') {
        <mat-form-field appearance="outline">
          <mat-label>chaincache instance URL</mat-label>
          <input matInput [(ngModel)]="managementUrl" placeholder="http://chaincache:8080" type="url" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Remote chain key</mat-label>
          <input matInput [(ngModel)]="remoteChainKey" placeholder="anvil" />
        </mat-form-field>
      }

      <mat-form-field appearance="outline">
        <mat-label>Label (optional)</mat-label>
        <input matInput [(ngModel)]="label" placeholder="e.g. Infura, Alchemy, Self-hosted" />
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions style="padding: 0 24px 20px">
      <div class="actions">
        <button mat-button mat-dialog-close>Cancel</button>
        <button mat-flat-button color="primary" [disabled]="!canSubmit()" (click)="submit()">
          {{ isEdit ? 'Save' : 'Add Node' }}
        </button>
      </div>
    </mat-dialog-actions>
  `,
})
export class AddNodeDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<AddNodeDialogComponent>);
  readonly data: { chain: ChainHealth; node?: RpcNode } = inject(MAT_DIALOG_DATA);

  readonly isEdit = !!this.data.node;

  kind: RpcNodeKind = this.data.node?.kind ?? 'DIRECT_RPC';
  url = this.data.node?.url ?? '';
  label = this.data.node?.label ?? '';
  managementUrl = this.data.node?.managementUrl ?? '';
  remoteChainKey = this.data.node?.remoteChainKey ?? '';

  canSubmit(): boolean {
    if (!this.url.trim()) return false;
    if (this.kind === 'CHAINCACHE') {
      return !!this.managementUrl.trim() && !!this.remoteChainKey.trim();
    }
    return true;
  }

  submit() {
    if (!this.canSubmit()) return;
    const request: RpcNodeWriteRequest = {
      url: this.url.trim(),
      label: this.label.trim(),
      kind: this.kind,
      managementUrl: this.kind === 'CHAINCACHE' ? this.managementUrl.trim() : undefined,
      remoteChainKey: this.kind === 'CHAINCACHE' ? this.remoteChainKey.trim() : undefined,
    };
    this.dialogRef.close(request);
  }
}
