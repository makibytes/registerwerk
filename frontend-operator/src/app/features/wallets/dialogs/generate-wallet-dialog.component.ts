import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';

@Component({
  selector: 'app-generate-wallet-dialog',
  standalone: true,
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatRadioModule],
  styles: [`
    h2 { margin: 0 0 4px; font-size: 17px; font-weight: 700; }
    .subtitle { font-size: 13px; color: var(--rw-text-muted); margin: 0 0 20px; }
    mat-form-field { width: 100%; }
    .type-row { display: flex; gap: 20px; margin-bottom: 16px; align-items: center; }
    .type-label { font-size: 13px; color: var(--rw-text-secondary); margin-bottom: 4px; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
  `],
  template: `
    <div mat-dialog-title style="padding: 20px 24px 0">
      <h2>Generate New Wallet</h2>
      <p class="subtitle">A fresh keypair will be created and stored encrypted on the server.</p>
    </div>
    <mat-dialog-content style="padding: 0 24px 8px">
      <mat-form-field appearance="outline">
        <mat-label>Wallet name</mat-label>
        <input matInput [(ngModel)]="name" placeholder="e.g. Primary EVM" />
      </mat-form-field>
      <p class="type-label">Type</p>
      <div class="type-row">
        <mat-radio-group [(ngModel)]="type">
          <mat-radio-button value="EVM" style="margin-right: 16px">EVM (Ethereum / Polygon / Base)</mat-radio-button>
          <mat-radio-button value="SOLANA" style="margin-right: 16px">Solana</mat-radio-button>
          <mat-radio-button value="CANTON">Canton (Daml)</mat-radio-button>
        </mat-radio-group>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions style="padding: 0 24px 20px">
      <div class="actions">
        <button mat-button mat-dialog-close>Cancel</button>
        <button mat-flat-button color="primary" [disabled]="!name.trim()" (click)="submit()">Generate</button>
      </div>
    </mat-dialog-actions>
  `,
})
export class GenerateWalletDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<GenerateWalletDialogComponent>);

  name = '';
  type: 'EVM' | 'SOLANA' | 'CANTON' = 'EVM';

  submit() {
    if (!this.name.trim()) return;
    this.dialogRef.close({ name: this.name.trim(), type: this.type });
  }
}
