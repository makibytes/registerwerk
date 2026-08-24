import { ChangeDetectorRef, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatSnackBarModule } from '@angular/material/snack-bar';
import { EndpointService } from '../../core/api/endpoint.service';
import { AddressResolutionService } from '../../core/services/address-resolution.service';
import { EndpointAddressType } from '../../core/models';

export interface EndpointDialogData {
  address: string;
}

@Component({
  selector: 'app-endpoint-dialog',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSnackBarModule,
  ],
  styles: [`
    h2 { margin: 0 0 4px; font-size: 17px; font-weight: 700; }
    .subtitle { font-size: 13px; color: var(--rw-text-muted); margin: 0 0 20px; }
    .addr { font-family: 'IBM Plex Mono', monospace; font-size: 12px; }
    mat-form-field { width: 100%; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
  `],
  template: `
    <div mat-dialog-title style="padding: 20px 24px 0">
      <h2>Add to Address Book</h2>
      <p class="subtitle addr">{{ data.address }}</p>
    </div>
    <mat-dialog-content style="padding: 0 24px 8px">
      <mat-form-field appearance="outline">
        <mat-label>Name</mat-label>
        <input matInput [(ngModel)]="name" placeholder="e.g. Deutsche Bank AG" maxlength="200" />
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Type</mat-label>
        <mat-select [(ngModel)]="addressType">
          <mat-option value="WALLET">Wallet</mat-option>
          <mat-option value="CONTRACT">Smart Contract</mat-option>
        </mat-select>
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Notes (optional)</mat-label>
        <textarea matInput [(ngModel)]="notes" rows="2" maxlength="500"
                  placeholder="Optional description or context"></textarea>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions style="padding: 0 24px 20px">
      <div class="actions">
        <button type="button" mat-button mat-dialog-close [disabled]="saving">Cancel</button>
        <button type="button" mat-flat-button color="primary" [disabled]="!name.trim() || saving" (click)="submit()">
          {{ saving ? 'Saving…' : 'Save' }}
        </button>
      </div>
    </mat-dialog-actions>
  `,
})
export class EndpointDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<EndpointDialogComponent>);
  readonly data: EndpointDialogData = inject(MAT_DIALOG_DATA);

  private readonly endpointService = inject(EndpointService);
  private readonly resolution = inject(AddressResolutionService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  name = '';
  addressType: EndpointAddressType = 'WALLET';
  notes = '';
  saving = false;

  submit(): void {
    if (!this.name.trim()) return;
    this.saving = true;
    this.endpointService.createEndpoint({
      address: this.data.address,
      addressType: this.addressType,
      name: this.name.trim(),
      notes: this.notes.trim() || undefined,
    }).subscribe({
      next: ep => {
        this.resolution.invalidate(this.data.address);
        this.snackBar.open(`"${ep.name}" added to address book`, '', { duration: 2500 });
        this.dialogRef.close(ep);
      },
      error: (err) => {
        this.saving = false;
        this.cdr.markForCheck();
        const msg = err?.error?.message ?? 'Failed to save endpoint';
        this.snackBar.open(msg, 'Dismiss', { duration: 4000 });
      },
    });
  }
}
