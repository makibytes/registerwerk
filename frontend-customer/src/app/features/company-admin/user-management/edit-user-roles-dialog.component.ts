import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { UserRole } from '../../../core/models';

interface EditUserRolesDialogData {
  name: string;
  roles: UserRole[];
}

@Component({
  selector: 'app-edit-user-roles-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatSelectModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>Update roles</h2>
    <mat-dialog-content>
      <p class="dialog-copy">Choose the roles that {{ data.name }} should keep.</p>

      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Roles</mat-label>
        <mat-select [(ngModel)]="roles" multiple>
          @for (role of availableRoles; track role.value) {
            <mat-option [value]="role.value">{{ role.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button color="primary" [disabled]="roles.length === 0" (click)="save()">Save</button>
    </mat-dialog-actions>
  `,
  styles: [`
    .dialog-copy {
      margin: 0 0 16px;
      font-size: 14px;
      color: var(--rw-text-secondary);
    }

    .full-width { width: 100%; }
  `]
})
export class EditUserRolesDialogComponent {
  readonly data = inject<EditUserRolesDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject<MatDialogRef<EditUserRolesDialogComponent, UserRole[]>>(MatDialogRef);

  roles = [...this.data.roles];

  readonly availableRoles: { value: UserRole; label: string }[] = [
    { value: 'ISSUER', label: 'Issuer' },
    { value: 'INVESTOR', label: 'Investor' },
    { value: 'TRADER', label: 'Trader' },
    { value: 'COMPANY_ADMIN', label: 'Company Admin' },
    { value: 'AUDIT', label: 'Audit' },
  ];

  save(): void {
    this.dialogRef.close(this.roles);
  }
}
