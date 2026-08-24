import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ChainHealth, RpcNode } from '../../core/models';
import { RpcNodeWriteRequest } from '../../core/api/chain.service';

/** Add or edit an RPC node. There is no kind selector — whether a URL is a plain direct-RPC
 *  endpoint or a genuine chaincache connection is auto-detected backend-side from the URL alone
 *  (see ChaincacheClient#detect); an operator has no way to declare or mis-declare it. On edit,
 *  the currently-detected kind is shown read-only so it's clear what Registerwerk found, without
 *  offering any control to override it. */
@Component({
  selector: 'app-add-node-dialog',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  styles: [`
    h2 { margin: 0 0 4px; font-size: 17px; font-weight: 700; }
    .subtitle { font-size: 13px; color: var(--rw-text-muted); margin: 0 0 20px; }
    mat-form-field { width: 100%; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
    .hint { font-size: 12px; color: var(--rw-text-muted); margin: -8px 0 16px; }
    .detected-kind {
      display: flex; align-items: center; gap: 8px;
      padding: 10px 12px; margin-bottom: 16px;
      border-radius: 6px; background: var(--rw-bg); border: 1px solid var(--rw-border);
      font-size: 12px; color: var(--rw-text-secondary);
    }
    .detected-kind mat-icon { font-size: 16px; width: 16px; height: 16px; color: var(--rw-accent); }
    .detected-kind strong { color: var(--rw-text-primary); }
  `],
  template: `
    <div mat-dialog-title style="padding: 20px 24px 0">
      <h2>{{ isEdit ? 'Edit' : 'Add' }} RPC Node</h2>
      <p class="subtitle">{{ data.chain.displayName }}</p>
    </div>
    <mat-dialog-content style="padding: 0 24px 8px">
      @if (isEdit) {
        <div class="detected-kind">
          <mat-icon>{{ data.node!.kind === 'CHAINCACHE' ? 'bolt' : 'lan' }}</mat-icon>
          <span>
            Detected as <strong>{{ data.node!.kind === 'CHAINCACHE' ? 'a chaincache connection' : 'a direct RPC node' }}</strong>
            — re-checked automatically whenever the URL changes.
          </span>
        </div>
      }

      <mat-form-field appearance="outline">
        <mat-label>RPC URL</mat-label>
        <input matInput [(ngModel)]="url" placeholder="https://mainnet.infura.io/v3/..." type="url" />
      </mat-form-field>
      <p class="hint">
        Registerwerk detects automatically whether this is a plain direct-RPC endpoint or a
        chaincache connection. Chaincache runs one workload per chain, so the URL names both the
        workload and the chain it serves, e.g. <code>http://chaincache-sepolia:8080/sepolia/rpc</code>
        — there is nothing else to configure here.
      </p>

      <mat-form-field appearance="outline">
        <mat-label>Label (optional)</mat-label>
        <input matInput [(ngModel)]="label" placeholder="e.g. Infura, Alchemy, Self-hosted" />
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions style="padding: 0 24px 20px">
      <div class="actions">
        <button type="button" mat-button mat-dialog-close>Cancel</button>
        <button type="button" mat-flat-button color="primary" [disabled]="!canSubmit()" (click)="submit()">
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

  url = this.data.node?.url ?? '';
  label = this.data.node?.label ?? '';

  canSubmit(): boolean {
    return !!this.url.trim();
  }

  submit() {
    if (!this.canSubmit()) return;
    const request: RpcNodeWriteRequest = {
      url: this.url.trim(),
      label: this.label.trim(),
    };
    this.dialogRef.close(request);
  }
}
