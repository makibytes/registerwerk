import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-import-raw-dialog',
  standalone: true,
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatRadioModule, MatIconModule],
  styles: [`
    h2 { margin: 0 0 4px; font-size: 17px; font-weight: 700; }
    .subtitle { font-size: 13px; color: var(--rw-text-muted); margin: 0 0 20px; }
    mat-form-field { width: 100%; }
    .type-row { display: flex; gap: 20px; margin-bottom: 16px; align-items: center; }
    .type-label { font-size: 13px; color: var(--rw-text-secondary); margin-bottom: 4px; }
    .warning-banner { background: rgba(245,158,11,.1); border-left: 3px solid #f59e0b; padding: 10px 14px; border-radius: 4px; margin-bottom: 16px; font-size: 12px; color: var(--rw-text-secondary); display: flex; gap: 8px; align-items: flex-start; }
    .warning-banner mat-icon { font-size: 16px; width: 16px; height: 16px; color: #d97706; flex-shrink: 0; margin-top: 1px; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
  `],
  template: `
    <div mat-dialog-title style="padding: 20px 24px 0">
      <h2>Import Raw Private Key</h2>
      <p class="subtitle">The key is transmitted over local TLS and immediately encrypted on the server.</p>
    </div>
    <mat-dialog-content style="padding: 0 24px 8px">
      <div class="warning-banner">
        <mat-icon>warning_amber</mat-icon>
        <span>Never enter a private key on an untrusted network. This key will be encrypted server-side and never returned in plaintext.</span>
      </div>
      <mat-form-field appearance="outline">
        <mat-label>Wallet name</mat-label>
        <input matInput [(ngModel)]="name" placeholder="e.g. Imported EVM wallet" />
      </mat-form-field>
      <p class="type-label">Type</p>
      <div class="type-row">
        <mat-radio-group [(ngModel)]="type">
          <mat-radio-button value="EVM" style="margin-right: 16px">EVM</mat-radio-button>
          <mat-radio-button value="SOLANA">Solana</mat-radio-button>
        </mat-radio-group>
      </div>
      <mat-form-field appearance="outline">
        <mat-label>Private key (hex)</mat-label>
        <input matInput [(ngModel)]="privateKey" type="password" placeholder="0x..." autocomplete="off" />
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions style="padding: 0 24px 20px">
      <div class="actions">
        <button mat-button mat-dialog-close>Cancel</button>
        <button mat-flat-button color="primary" [disabled]="!name.trim() || !privateKey.trim()" (click)="submit()">Import</button>
      </div>
    </mat-dialog-actions>
  `,
})
export class ImportRawDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<ImportRawDialogComponent>);
  name = ''; type: 'EVM' | 'SOLANA' = 'EVM'; privateKey = '';
  submit() {
    if (!this.name.trim() || !this.privateKey.trim()) return;
    this.dialogRef.close({ name: this.name.trim(), type: this.type, privateKey: this.privateKey.trim() });
  }
}
