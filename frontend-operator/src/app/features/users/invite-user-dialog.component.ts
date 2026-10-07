import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AdminUserService, AppUserRole, OperatorUser, isGatedOperatorAccount } from '../../core/api/admin-user.service';
import { openStepUp } from '../../shared/components/step-up/open-step-up';
import { EntityService } from '../../core/api/entity.service';
import { LegalEntity } from '../../core/models';

const OPERATOR_ROLES: { value: AppUserRole; label: string }[] = [
  { value: 'REGISTRY_ADMIN', label: 'Registry Admin (Operator)' },
  { value: 'AUDIT', label: 'Audit (Operator)' },
  { value: 'COMPLIANCE_OFFICER', label: 'Compliance Officer (Operator)' },
  { value: 'RELATIONSHIP_MANAGER', label: 'Relationship Manager (Operator)' },
  { value: 'SUPPORT_AGENT', label: 'Support Agent (Operator, read-only customer sessions)' },
];

const COMPANY_ROLES: { value: AppUserRole; label: string }[] = [
  { value: 'COMPANY_ADMIN', label: 'Company Admin' },
  { value: 'ISSUER', label: 'Issuer' },
  { value: 'INVESTOR', label: 'Investor' },
  { value: 'TRADER', label: 'Trader' },
];

@Component({
  selector: 'app-operator-invite-user-dialog',
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
  styles: [`
    .full-width { width: 100%; margin-bottom: 8px; }
    .error-message { color: var(--rw-text-danger); font-size: 13px; }
    mat-dialog-content { display: flex; flex-direction: column; padding-top: 16px !important; }
    .scope-hint { font-size: 12px; color: var(--rw-text-muted); margin: -4px 0 8px; }
  `],
  template: `
    <h2 mat-dialog-title>Invite User</h2>

    <mat-dialog-content>
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Email</mat-label>
        <input matInput type="email" [(ngModel)]="email" required placeholder="user@company.com" />
      </mat-form-field>

      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Full Name</mat-label>
        <input matInput [(ngModel)]="fullName" required placeholder="Jane Smith" />
      </mat-form-field>

      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Company (optional)</mat-label>
        <mat-select [(ngModel)]="selectedEntityId" (ngModelChange)="onEntityChange()">
          <mat-option value="">— Operator user (no company) —</mat-option>
          @for (e of entities; track e.id) {
            <mat-option [value]="e.id">{{ e.currentName }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
      <p class="scope-hint">
        @if (!selectedEntityId) {
          Operator users log into the operator portal.
        } @else {
          Company users log into the customer portal.
        }
      </p>

      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Roles</mat-label>
        <mat-select [(ngModel)]="selectedRoles" multiple>
          @for (r of availableRoles; track r.value) {
            <mat-option [value]="r.value">{{ r.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>

      @if (error) {
        <p class="error-message">{{ error }}</p>
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button type="button" mat-button mat-dialog-close>Cancel</button>
      <button type="button"
        mat-raised-button
        color="primary"
        [disabled]="saving || !email || !fullName || selectedRoles.length === 0"
        (click)="invite()">
        @if (saving) { <mat-spinner diameter="18" /> }
        @else { Send Invitation }
      </button>
    </mat-dialog-actions>
  `,
})
export class InviteUserDialogComponent implements OnInit {
  private readonly adminUserService = inject(AdminUserService);
  private readonly entityService = inject(EntityService);
  private readonly dialogRef = inject<MatDialogRef<InviteUserDialogComponent, OperatorUser>>(MatDialogRef);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly dialog = inject(MatDialog);

  email = '';
  fullName = '';
  selectedEntityId = '';
  selectedRoles: AppUserRole[] = [];
  saving = false;
  error = '';

  entities: LegalEntity[] = [];
  availableRoles = OPERATOR_ROLES;

  ngOnInit(): void {
    this.entityService.getEntities({ size: 200 }).subscribe({
      next: (page) => { this.entities = page.content; this.cdr.markForCheck(); }
    });
  }

  onEntityChange(): void {
    this.selectedRoles = [];
    this.availableRoles = this.selectedEntityId ? COMPANY_ROLES : OPERATOR_ROLES;
  }

  invite(): void {
    this.error = '';
    // Operator accounts and administrative roles are gated: step-up plus a second approver (outside bootstrap).
    const gated = isGatedOperatorAccount(this.selectedRoles, this.selectedEntityId || null);
    const request = {
      email: this.email,
      name: this.fullName,
      legalEntityId: this.selectedEntityId || null,
      roles: this.selectedRoles,
    };
    openStepUp(this.dialog, {
      requireDualControl: gated, dualControlOptional: true,
      reason: `Invite ${this.email}`,
      action: 'OPERATOR_USER_INVITE',
      target: 'POST /api/v1/admin/users',
      targetBody: request,
    }).subscribe(tokens => {
      if (!tokens) return;
      this.saving = true;
      this.cdr.markForCheck();
      this.adminUserService.inviteUser(request, tokens).subscribe({
        next: (user) => this.dialogRef.close(user),
        error: (err) => {
          this.saving = false;
          this.error = err?.error?.message ?? 'Failed to send invitation.';
          this.cdr.markForCheck();
        },
      });
    });
  }
}
