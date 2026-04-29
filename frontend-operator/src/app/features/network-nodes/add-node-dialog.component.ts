import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ChainHealth } from '../../core/models';

@Component({
  selector: 'app-add-node-dialog',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
  ],
  styles: [`
    h2 { margin: 0 0 4px; font-size: 17px; font-weight: 700; }
    .subtitle { font-size: 13px; color: var(--rw-text-muted); margin: 0 0 20px; }
    mat-form-field { width: 100%; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
  `],
  template: `
    <div mat-dialog-title style="padding: 20px 24px 0">
      <h2>Add RPC Node</h2>
      <p class="subtitle">{{ data.chain.displayName }}</p>
    </div>
    <mat-dialog-content style="padding: 0 24px 8px">
      <mat-form-field appearance="outline">
        <mat-label>RPC URL</mat-label>
        <input matInput [(ngModel)]="url" placeholder="https://mainnet.infura.io/v3/..." type="url" />
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Label (optional)</mat-label>
        <input matInput [(ngModel)]="label" placeholder="e.g. Infura, Alchemy, Self-hosted" />
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions style="padding: 0 24px 20px">
      <div class="actions">
        <button mat-button mat-dialog-close>Cancel</button>
        <button mat-flat-button color="primary" [disabled]="!url.trim()" (click)="submit()">Add Node</button>
      </div>
    </mat-dialog-actions>
  `,
})
export class AddNodeDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<AddNodeDialogComponent>);
  readonly data: { chain: ChainHealth } = inject(MAT_DIALOG_DATA);

  url   = '';
  label = '';

  submit() {
    if (!this.url.trim()) return;
    this.dialogRef.close({ url: this.url.trim(), label: this.label.trim() });
  }
}
