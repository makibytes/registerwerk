import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe, CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatDividerModule } from '@angular/material/divider';
import { MatMenuModule } from '@angular/material/menu';
import { AdminUserService, AppUserRole, OperatorUser } from '../../core/api/admin-user.service';
import { InviteUserDialogComponent } from './invite-user-dialog.component';
import { EditUserRolesDialogComponent } from './edit-user-roles-dialog.component';
import { User2faDialogComponent } from './user-2fa-dialog.component';

const ALL_ROLES: AppUserRole[] = [
  'REGISTRY_ADMIN', 'AUDIT', 'COMPLIANCE_OFFICER', 'RELATIONSHIP_MANAGER',
  'COMPANY_ADMIN', 'ISSUER', 'INVESTOR', 'TRADER'
];

const ROLE_LABELS: Record<AppUserRole, string> = {
  REGISTRY_ADMIN: 'Registry Admin',
  AUDIT: 'Audit',
  COMPLIANCE_OFFICER: 'Compliance Officer',
  RELATIONSHIP_MANAGER: 'Relationship Manager',
  COMPANY_ADMIN: 'Company Admin',
  ISSUER: 'Issuer',
  INVESTOR: 'Investor',
  TRADER: 'Trader',
};

@Component({
  selector: 'app-user-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    DatePipe,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    MatTooltipModule,
    MatPaginatorModule,
    MatMenuModule,
    MatDividerModule,
  ],
  styles: [`
    .filter-row {
      display: flex;
      gap: 16px;
      align-items: flex-start;
      margin-bottom: 20px;
      flex-wrap: wrap;

      mat-form-field { min-width: 160px; }
    }

    .spinner-container {
      display: flex;
      justify-content: center;
      padding: 48px;
    }

    .user-cell {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .user-avatar {
      width: 30px;
      height: 30px;
      border-radius: 8px;
      background: rgba(245,158,11,0.15);
      color: var(--rw-accent);
      font-size: 12px;
      font-weight: 700;
      display: grid;
      place-items: center;
      text-transform: uppercase;
      flex-shrink: 0;
    }

    .user-name { font-weight: 600; font-size: 13px; color: var(--rw-text-primary); }
    .user-meta { font-size: 11px; color: var(--rw-text-secondary); }

    .role-list { display: flex; flex-wrap: wrap; gap: 4px; }

    .role-chip {
      display: inline-flex;
      padding: 3px 8px;
      border-radius: 999px;
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.04em;
      background: rgba(245,158,11,0.1);
      color: var(--rw-accent);
    }

    .role-chip.operator-role {
      background: rgba(99,102,241,0.1);
      color: #818cf8;
    }

    .entity-cell { font-size: 12px; color: var(--rw-text-secondary); }
    .operator-badge {
      font-size: 11px;
      font-weight: 700;
      padding: 2px 8px;
      border-radius: 4px;
      background: rgba(245,158,11,0.12);
      color: var(--rw-accent);
    }

    .status-pill {
      display: inline-flex;
      padding: 3px 8px;
      border-radius: 999px;
      font-size: 10px;
      font-weight: 700;
      background: rgba(34,197,94,0.12);
      color: #15803d;
    }

    .status-pill.disabled {
      background: rgba(239,68,68,0.12);
      color: #b91c1c;
    }

    .pending-tag {
      display: block;
      font-size: 10px;
      color: var(--rw-text-muted);
      text-transform: uppercase;
      letter-spacing: 0.04em;
      margin-top: 3px;
    }

    .actions-cell {
      display: flex;
      justify-content: flex-end;
    }

    .menu-delete {
      color: #dc2626;
      mat-icon { color: #dc2626; }
    }

    .empty-row td {
      text-align: center;
      padding: 32px;
      color: var(--rw-text-muted);
    }

    .request-error {
      display: grid;
      justify-items: center;
      gap: 12px;
      padding: 48px 20px;
      text-align: center;
      color: var(--rw-text-danger);
    }

    .table-scroll { overflow-x: auto; }
    table { min-width: 860px; }

    @media (max-width: 640px) {
      .page-header { align-items: flex-start; gap: 12px; flex-direction: column; }
      .filter-row mat-form-field { width: 100%; }
    }
  `],
  template: `
    <div class="page-header">
      <h1>Users</h1>
      <button type="button" mat-raised-button color="primary" (click)="openInvite()">
        <mat-icon>person_add</mat-icon>
        Invite user
      </button>
    </div>

    <div class="content-card">
      <div class="filter-row">
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Search</mat-label>
          <input matInput [(ngModel)]="searchQuery" (ngModelChange)="onFilterChange()" placeholder="Name or email…" />
          <mat-icon matSuffix>search</mat-icon>
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Role</mat-label>
          <mat-select [(ngModel)]="selectedRole" (ngModelChange)="onFilterChange()">
            <mat-option value="">All roles</mat-option>
            @for (role of allRoles; track role) {
              <mat-option [value]="role">{{ roleLabel(role) }}</mat-option>
            }
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Status</mat-label>
          <mat-select [(ngModel)]="selectedEnabled" (ngModelChange)="onFilterChange()">
            <mat-option [value]="undefined">All</mat-option>
            <mat-option [value]="true">Enabled</mat-option>
            <mat-option [value]="false">Disabled</mat-option>
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Scope</mat-label>
          <mat-select [(ngModel)]="selectedScope" (ngModelChange)="onFilterChange()">
            <mat-option value="all">All users</mat-option>
            <mat-option value="operator">Operator only</mat-option>
            <mat-option value="company">Company users</mat-option>
          </mat-select>
        </mat-form-field>
      </div>

      @if (loading) {
        <div class="spinner-container"><mat-spinner diameter="36" /></div>
      } @else if (loadError) {
        <div class="request-error" role="alert">
          <mat-icon>cloud_off</mat-icon>
          <span>Users could not be loaded.</span>
          <button mat-stroked-button type="button" (click)="loadUsers()">Retry</button>
        </div>
      } @else {
        <div class="table-scroll">
        <table mat-table [dataSource]="users" class="full-width-table">
          <ng-container matColumnDef="user">
            <th mat-header-cell *matHeaderCellDef>User</th>
            <td mat-cell *matCellDef="let u">
              <div class="user-cell">
                <div class="user-avatar">{{ (u.name || u.email)[0] }}</div>
                <div>
                  <div class="user-name">{{ u.name || u.email }}</div>
                  <div class="user-meta">{{ u.email }}</div>
                </div>
              </div>
            </td>
          </ng-container>

          <ng-container matColumnDef="company">
            <th mat-header-cell *matHeaderCellDef>Company</th>
            <td mat-cell *matCellDef="let u">
              @if (u.entityId) {
                <span class="entity-cell">{{ u.entityName || u.entityId }}</span>
              } @else {
                <span class="operator-badge">Operator</span>
              }
            </td>
          </ng-container>

          <ng-container matColumnDef="roles">
            <th mat-header-cell *matHeaderCellDef>Roles</th>
            <td mat-cell *matCellDef="let u">
              <div class="role-list">
                @for (role of u.roles; track role) {
                  <span class="role-chip" [class.operator-role]="isOperatorRole(role)">
                    {{ roleLabel(role) }}
                  </span>
                }
              </div>
            </td>
          </ng-container>

          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Status</th>
            <td mat-cell *matCellDef="let u">
              <span class="status-pill" [class.disabled]="!u.enabled">
                {{ u.enabled ? 'Enabled' : 'Disabled' }}
              </span>
              @if (u.passwordSetupRequired) {
                <span class="pending-tag">Awaiting setup</span>
              }
            </td>
          </ng-container>

          <ng-container matColumnDef="lastLogin">
            <th mat-header-cell *matHeaderCellDef>Last login</th>
            <td mat-cell *matCellDef="let u">
              <span class="text-muted">{{ u.lastLoginAt ? (u.lastLoginAt | date:'medium') : 'Never' }}</span>
            </td>
          </ng-container>

          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let u">
              <div class="actions-cell">
                <button type="button" mat-icon-button [matMenuTriggerFor]="userMenu" [matMenuTriggerData]="{ user: u }" matTooltip="Actions">
                  <mat-icon>more_vert</mat-icon>
                </button>
              </div>
            </td>
          </ng-container>

          <mat-menu #userMenu="matMenu">
            <ng-template matMenuContent let-u="user">
              <button type="button" mat-menu-item (click)="editRoles(u)">
                <mat-icon>manage_accounts</mat-icon>
                Edit roles
              </button>
              <button type="button" mat-menu-item (click)="toggleEnabled(u)">
                <mat-icon>{{ u.enabled ? 'block' : 'check_circle' }}</mat-icon>
                {{ u.enabled ? 'Disable' : 'Enable' }}
              </button>
              <button type="button" mat-menu-item [disabled]="u.authProvider !== 'LOCAL'" (click)="sendPasswordReset(u)">
                <mat-icon>lock_reset</mat-icon>
                Reset password
              </button>
              <button type="button" mat-menu-item [disabled]="u.authProvider !== 'ENTRA'" (click)="manage2fa(u)">
                <mat-icon>security</mat-icon>
                Manage 2FA
              </button>
              <mat-divider />
              <button type="button" mat-menu-item class="menu-delete" (click)="deleteUser(u)">
                <mat-icon>delete</mat-icon>
                Delete
              </button>
            </ng-template>
          </mat-menu>

          <tr mat-header-row *matHeaderRowDef="columns"></tr>
          <tr mat-row *matRowDef="let row; columns: columns;"></tr>
          <tr class="mat-row empty-row" *matNoDataRow>
            <td [attr.colspan]="columns.length">No users found.</td>
          </tr>
        </table>
        </div>

        <mat-paginator
          [length]="totalElements"
          [pageSize]="pageSize"
          [pageIndex]="pageIndex"
          [pageSizeOptions]="[10, 25, 50]"
          (page)="onPage($event)"
        />
      }
    </div>
  `,
})
export class UserListComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly adminUserService = inject(AdminUserService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly columns = ['user', 'company', 'roles', 'status', 'lastLogin', 'actions'];
  readonly allRoles = ALL_ROLES;

  users: OperatorUser[] = [];
  totalElements = 0;
  pageIndex = 0;
  pageSize = 25;
  loading = true;
  loadError = false;

  searchQuery = '';
  selectedRole = '';
  selectedEnabled: boolean | undefined = undefined;
  selectedScope: 'all' | 'operator' | 'company' = 'all';

  ngOnInit(): void {
    this.loadUsers();
  }

  onFilterChange(): void {
    this.pageIndex = 0;
    this.loadUsers();
  }

  onPage(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadUsers();
  }

  openInvite(): void {
    const ref = this.dialog.open(InviteUserDialogComponent, { width: '540px' });
    ref.afterClosed().subscribe((user: OperatorUser | undefined) => {
      if (!user) return;
      this.snackBar.open(`Invitation sent to ${user.email}`, 'OK', { duration: 3000 });
      this.loadUsers();
    });
  }

  editRoles(user: OperatorUser): void {
    const ref = this.dialog.open(EditUserRolesDialogComponent, {
      width: '440px',
      data: { name: user.name, roles: user.roles, entityId: user.entityId },
    });
    ref.afterClosed().subscribe((roles: AppUserRole[] | undefined) => {
      if (!roles) return;
      this.adminUserService.updateRoles(user.id, roles).subscribe({
        next: (updated) => {
          this.replaceUser(updated);
          this.snackBar.open(`Roles updated for ${updated.name || updated.email}`, 'OK', { duration: 3000 });
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Failed to update roles', 'Dismiss', { duration: 4000 });
        },
      });
    });
  }

  toggleEnabled(user: OperatorUser): void {
    const request$ = user.enabled
      ? this.adminUserService.disableUser(user.id)
      : this.adminUserService.enableUser(user.id);
    request$.subscribe({
      next: (updated) => {
        this.replaceUser(updated);
        this.snackBar.open(
          user.enabled ? `${updated.name || updated.email} disabled` : `${updated.name || updated.email} enabled`,
          'OK',
          { duration: 3000 }
        );
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Failed to update status', 'Dismiss', { duration: 4000 });
      },
    });
  }

  sendPasswordReset(user: OperatorUser): void {
    this.adminUserService.sendPasswordReset(user.id).subscribe({
      next: () => {
        this.snackBar.open(`Password reset sent to ${user.email}`, 'OK', { duration: 3000 });
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Failed to send reset', 'Dismiss', { duration: 4000 });
      },
    });
  }

  /**
   * Opens the Microsoft Entra 2FA support console — list registered methods, reset them, revoke
   * sessions, and issue a Temporary Access Pass. Only meaningful for ENTRA accounts; local
   * accounts use the password-reset action above.
   */
  manage2fa(user: OperatorUser): void {
    this.dialog.open(User2faDialogComponent, {
      data: user,
      width: '620px',
      autoFocus: false,
    });
  }

  deleteUser(user: OperatorUser): void {
    if (!confirm(`Delete ${user.name || user.email}? This cannot be undone.`)) return;
    this.adminUserService.deleteUser(user.id).subscribe({
      next: () => {
        this.users = this.users.filter(u => u.id !== user.id);
        this.totalElements = Math.max(0, this.totalElements - 1);
        this.snackBar.open(`${user.name || user.email} deleted`, 'OK', { duration: 3000 });
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Failed to delete user', 'Dismiss', { duration: 4000 });
      },
    });
  }

  roleLabel(role: AppUserRole): string {
    return ROLE_LABELS[role] ?? role;
  }

  isOperatorRole(role: AppUserRole): boolean {
    return ['REGISTRY_ADMIN', 'AUDIT', 'COMPLIANCE_OFFICER', 'RELATIONSHIP_MANAGER'].includes(role);
  }

  loadUsers(): void {
    this.loading = true;
    this.loadError = false;
    this.adminUserService.listUsers({
      search: this.searchQuery || undefined,
      role: this.selectedRole || undefined,
      enabled: this.selectedEnabled,
      operatorOnly: this.selectedScope === 'operator' ? true : this.selectedScope === 'company' ? false : undefined,
      page: this.pageIndex,
      size: this.pageSize,
    }).subscribe({
      next: (page) => {
        this.users = page.content;
        this.totalElements = page.totalElements;
        this.loading = false;
        this.loadError = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.loadError = true;
        this.cdr.markForCheck();
      },
    });
  }

  private replaceUser(updated: OperatorUser): void {
    this.users = this.users.map(u => u.id === updated.id ? updated : u);
    this.cdr.markForCheck();
  }
}
