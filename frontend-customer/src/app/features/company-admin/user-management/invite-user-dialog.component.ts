import { ChangeDetectorRef, Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CompanyService } from '../../../core/api/company.service';
import { CompanyUser, UserRole } from '../../../core/models';

@Component({
  selector: 'app-invite-user-dialog',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <h2 mat-dialog-title>Invite User</h2>

    <mat-dialog-content>
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Email</mat-label>
        <input matInput type="email" maxlength="320" autocomplete="email" [(ngModel)]="email" required placeholder="colleague@company.com" />
      </mat-form-field>

      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Full Name</mat-label>
        <input matInput maxlength="200" autocomplete="name" [(ngModel)]="name" required placeholder="Jane Smith" />
      </mat-form-field>

      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Roles</mat-label>
        <mat-select [(ngModel)]="selectedRoles" multiple>
          @for (r of availableRoles; track r.value) {
            <mat-option [value]="r.value">{{ r.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>

      @if (error) {
        <p class="error-message" role="alert">{{ error }}</p>
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button
        mat-raised-button
        color="primary"
        type="button"
        [disabled]="saving || !isValidEmail() || !name.trim() || selectedRoles.length === 0"
        (click)="invite()"
      >
        @if (saving) { <mat-spinner diameter="18"></mat-spinner> }
        @else { Send Invitation }
      </button>
    </mat-dialog-actions>
  `,
  styles: [`
    .full-width { width: 100%; margin-bottom: 8px; }
    .error-message { color: #c62828; font-size: 13px; }
    mat-dialog-content { display: flex; flex-direction: column; padding-top: 16px !important; }
  `]
})
export class InviteUserDialogComponent {
  private readonly companyService = inject(CompanyService);
  private readonly dialogRef = inject<MatDialogRef<InviteUserDialogComponent, CompanyUser>>(MatDialogRef);
  private readonly cdr = inject(ChangeDetectorRef);

  email = '';
  name  = '';
  selectedRoles: UserRole[] = ['ISSUER'];
  saving = false;
  error  = '';

  readonly availableRoles: { value: UserRole; label: string }[] = [
    { value: 'ISSUER',        label: 'Issuer' },
    { value: 'INVESTOR',      label: 'Investor' },
    { value: 'TRADER',        label: 'Trader' },
    { value: 'COMPANY_ADMIN', label: 'Company Admin' },
    { value: 'AUDIT',         label: 'Audit' },
  ];

  invite(): void {
    const email = this.email.trim().toLowerCase();
    const name = this.name.trim();
    if (this.saving || !this.isValidEmail() || !name || this.selectedRoles.length === 0) return;
    this.saving = true;
    this.error = '';

    this.companyService
      .inviteUser({ email, name, roles: [...new Set(this.selectedRoles)] })
      .subscribe({
        next: (user) => this.dialogRef.close(user),
        error: (err) => {
          this.saving = false;
          this.error = err?.error?.message ?? 'Failed to send invitation.';
          this.cdr.markForCheck();
        },
      });
  }

  isValidEmail(): boolean {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.email.trim());
  }
}
