import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { AppUserRole } from '../../core/api/admin-user.service';

interface DialogData {
  name: string;
  roles: AppUserRole[];
  entityId: string | null;
}

const OPERATOR_ROLES: { value: AppUserRole; label: string }[] = [
  { value: 'REGISTRY_ADMIN', label: 'Registry Admin (Operator)' },
  { value: 'AUDIT', label: 'Audit (Operator)' },
  { value: 'COMPLIANCE_OFFICER', label: 'Compliance Officer (Operator)' },
  { value: 'RELATIONSHIP_MANAGER', label: 'Relationship Manager (Operator)' },
];

const COMPANY_ROLES: { value: AppUserRole; label: string }[] = [
  { value: 'COMPANY_ADMIN', label: 'Company Admin' },
  { value: 'ISSUER', label: 'Issuer' },
  { value: 'INVESTOR', label: 'Investor' },
  { value: 'TRADER', label: 'Trader' },
];

@Component({
  selector: 'app-operator-edit-user-roles-dialog',
  standalone: true,
  imports: [FormsModule, MatDialogModule, MatFormFieldModule, MatSelectModule, MatButtonModule],
  styles: [`
    .dialog-copy { margin: 0 0 16px; font-size: 14px; color: var(--rw-text-secondary); }
    .full-width { width: 100%; }
  `],
  template: `
    <h2 mat-dialog-title>Update roles</h2>
    <mat-dialog-content>
      <p class="dialog-copy">Choose roles for {{ data.name }}.</p>
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Roles</mat-label>
        <mat-select [(ngModel)]="roles" multiple>
          @for (r of availableRoles; track r.value) {
            <mat-option [value]="r.value">{{ r.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button color="primary" [disabled]="roles.length === 0" (click)="save()">Save</button>
    </mat-dialog-actions>
  `,
})
export class EditUserRolesDialogComponent {
  readonly data = inject<DialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject<MatDialogRef<EditUserRolesDialogComponent, AppUserRole[]>>(MatDialogRef);

  roles: AppUserRole[] = [...this.data.roles];
  readonly availableRoles = this.data.entityId ? COMPANY_ROLES : OPERATOR_ROLES;

  save(): void {
    this.dialogRef.close(this.roles);
  }
}
