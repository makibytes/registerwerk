import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-import-keystore-dialog',
  standalone: true,
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatIconModule],
  styles: [`
    h2 { margin: 0 0 4px; font-size: 17px; font-weight: 700; }
    .subtitle { font-size: 13px; color: var(--rw-text-muted); margin: 0 0 20px; }
    mat-form-field { width: 100%; }
    .file-row { margin-bottom: 16px; }
    .file-label { font-size: 13px; color: var(--rw-text-secondary); margin-bottom: 8px; display: block; }
    .file-btn { cursor: pointer; display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; border: 1px solid var(--rw-border); border-radius: 6px; font-size: 13px; color: var(--rw-text-secondary); background: var(--rw-bg); transition: all 0.15s; }
    .file-btn:hover { background: var(--rw-surface); color: var(--rw-text-primary); }
    .file-btn mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .file-name { font-size: 12px; color: var(--rw-text-muted); margin-top: 6px; font-family: 'IBM Plex Mono', monospace; }
    input[type=file] { display: none; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
  `],
  template: `
    <div mat-dialog-title style="padding: 20px 24px 0">
      <h2>Import Keystore File</h2>
      <p class="subtitle">Supports Web3 Secret Storage v3 (UTC JSON format from MetaMask, Hardhat, Foundry cast, etc.)</p>
    </div>
    <mat-dialog-content style="padding: 0 24px 8px">
      <mat-form-field appearance="outline">
        <mat-label>Wallet name</mat-label>
        <input matInput [(ngModel)]="name" placeholder="e.g. Imported EVM wallet" />
      </mat-form-field>
      <div class="file-row">
        <span class="file-label">Keystore file (JSON)</span>
        <label class="file-btn">
          <mat-icon>upload_file</mat-icon> Choose file
          <input type="file" accept=".json" (change)="onFile($event)" />
        </label>
        @if (file) {
          <div class="file-name">{{ file.name }}</div>
        }
      </div>
      <mat-form-field appearance="outline">
        <mat-label>Keystore password</mat-label>
        <input matInput [(ngModel)]="password" type="password" autocomplete="off" />
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions style="padding: 0 24px 20px">
      <div class="actions">
        <button type="button" mat-button mat-dialog-close>Cancel</button>
        <button type="button" mat-flat-button color="primary" [disabled]="!name.trim() || !file || !password" (click)="submit()">Import</button>
      </div>
    </mat-dialog-actions>
  `,
})
export class ImportKeystoreDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<ImportKeystoreDialogComponent>);
  name = ''; file: File | null = null; password = '';

  onFile(event: Event) {
    const input = event.target as HTMLInputElement;
    this.file = input.files?.[0] ?? null;
  }
  submit() {
    if (!this.name.trim() || !this.file || !this.password) return;
    this.dialogRef.close({ name: this.name.trim(), file: this.file, password: this.password });
  }
}
