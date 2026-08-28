import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatTabsModule } from '@angular/material/tabs';
import { forkJoin } from 'rxjs';
import { CompanyService } from '../../../core/api/company.service';
import { CompanyUser, LegalEntity, UserRole } from '../../../core/models';
import { InviteUserDialogComponent } from './invite-user-dialog.component';
import { EditUserRolesDialogComponent } from './edit-user-roles-dialog.component';
import { ExternalIdEditorComponent } from '../../../shared/components/external-id-editor.component';

@Component({
  selector: 'app-user-list',
  standalone: true,
  imports: [
    CommonModule,
    DatePipe,
    RouterLink,
    RouterLinkActive,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    MatTooltipModule,
    MatTabsModule,
    ExternalIdEditorComponent,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <div>
          <h1>Company Admin</h1>
          <p class="subtitle">Manage access, roles, and identity configuration for your company.</p>
        </div>
      </div>

      <nav mat-tab-nav-bar [tabPanel]="tabPanel">
        <a mat-tab-link routerLink="/company-admin/users" routerLinkActive #rla1="routerLinkActive" [active]="rla1.isActive">
          <mat-icon>people</mat-icon>&nbsp;Users
        </a>
        <a mat-tab-link routerLink="/company-admin/idp" routerLinkActive #rla2="routerLinkActive" [active]="rla2.isActive">
          <mat-icon>vpn_key</mat-icon>&nbsp;IdP Settings
        </a>
        <a mat-tab-link routerLink="/company-admin/external-ids" routerLinkActive #rla3="routerLinkActive" [active]="rla3.isActive">
          <mat-icon>tag</mat-icon>&nbsp;External IDs
        </a>
        <a mat-tab-link routerLink="/company-admin/org-identity" routerLinkActive #rla4="routerLinkActive" [active]="rla4.isActive">
          <mat-icon>fingerprint</mat-icon>&nbsp;Organization
        </a>
        <a mat-tab-link routerLink="/company-admin/beneficial-owners" routerLinkActive #rlaBo="routerLinkActive" [active]="rlaBo.isActive">
          <mat-icon>diversity_3</mat-icon>&nbsp;Beneficial Owners
        </a>
      </nav>
      <mat-tab-nav-panel #tabPanel></mat-tab-nav-panel>

      @if (entity) {
        <mat-card class="entity-card">
          <mat-card-content>
            <div class="entity-info">
              <div class="entity-mark">
                <mat-icon>domain</mat-icon>
              </div>
              <div>
                <h3>{{ entity.legalName }}</h3>
                <p>{{ entity.registrationNumber || 'No registration number' }} · {{ entity.jurisdiction || '—' }}</p>
                <div class="entity-external-id">
                  <app-external-id-editor
                    [subjectType]="'LEGAL_ENTITY'"
                    [subjectId]="entity.id"
                    [value]="entity.externalId"
                    label="Company external ID"
                    placeholder="ID used in your internal systems"
                    (valueChange)="entity.externalId = $event"
                  />
                </div>
              </div>
            </div>
          </mat-card-content>
        </mat-card>
      }

      @if (managedExternally) {
        <div class="managed-banner">
          <mat-icon>info</mat-icon>
          <span>User provisioning, password resets, and account lifecycle changes are managed in your identity provider while Entra mode is enabled.</span>
        </div>
      }

      <mat-card>
        <mat-card-header>
          <mat-card-title>Users ({{ users.length }})</mat-card-title>
          <button mat-flat-button color="primary" type="button" [disabled]="managedExternally" (click)="openInvite()">
            <mat-icon>person_add</mat-icon>
            Invite user
          </button>
        </mat-card-header>

        <mat-card-content>
          @if (loading) {
            <div class="loading-overlay"><mat-spinner diameter="36"></mat-spinner></div>
          } @else if (loadError) {
            <div class="load-error" role="alert">
              <mat-icon>error_outline</mat-icon>
              <span>Company users could not be loaded.</span>
              <button mat-stroked-button type="button" (click)="load()">Retry</button>
            </div>
          } @else {
            <div class="table-wrap">
            <table mat-table [dataSource]="users" class="mat-elevation-z0 full-width-table">
              <ng-container matColumnDef="name">
                <th mat-header-cell *matHeaderCellDef>User</th>
                <td mat-cell *matCellDef="let user">
                  <div class="user-cell">
                    <div class="user-avatar">{{ user.name?.[0] || 'U' }}</div>
                    <div>
                      <div class="user-name">{{ user.name }}</div>
                      <div class="user-meta">{{ user.email }}</div>
                    </div>
                  </div>
                </td>
              </ng-container>

              <ng-container matColumnDef="roles">
                <th mat-header-cell *matHeaderCellDef>Roles</th>
                <td mat-cell *matCellDef="let user">
                  <div class="role-list">
                    @for (role of user.roles; track role) {
                      <span class="role-chip">{{ roleLabel(role) }}</span>
                    }
                  </div>
                </td>
              </ng-container>

              <ng-container matColumnDef="status">
                <th mat-header-cell *matHeaderCellDef>Status</th>
                <td mat-cell *matCellDef="let user">
                  <div class="status-stack">
                    <span class="status-pill" [class.status-disabled]="!user.enabled">
                      {{ user.enabled ? 'Enabled' : 'Disabled' }}
                    </span>
                    @if (user.passwordSetupRequired) {
                      <span class="pending-copy">Awaiting setup</span>
                    }
                  </div>
                </td>
              </ng-container>

              <ng-container matColumnDef="lastLogin">
                <th mat-header-cell *matHeaderCellDef>Last login</th>
                <td mat-cell *matCellDef="let user">
                  <span class="text-muted">
                    {{ user.lastLoginAt ? (user.lastLoginAt | date: 'medium') : 'Never' }}
                  </span>
                </td>
              </ng-container>

              <ng-container matColumnDef="actions">
                <th mat-header-cell *matHeaderCellDef>Actions</th>
                <td mat-cell *matCellDef="let user">
                  <div class="actions-cell">
                    <button mat-stroked-button type="button" [disabled]="managedExternally || busyUsers.has(user.id)" (click)="editRoles(user)">Roles</button>
                    <button mat-stroked-button type="button" [disabled]="managedExternally || busyUsers.has(user.id)" (click)="toggleEnabled(user)">
                      {{ user.enabled ? 'Disable' : 'Enable' }}
                    </button>
                    <button mat-stroked-button type="button" [disabled]="managedExternally || user.authProvider !== 'LOCAL' || busyUsers.has(user.id)" (click)="sendPasswordReset(user)">
                      Reset password
                    </button>
                    <button mat-icon-button color="warn" type="button" [disabled]="managedExternally || busyUsers.has(user.id)" matTooltip="Delete user" (click)="removeUser(user)">
                      <mat-icon>delete</mat-icon>
                    </button>
                  </div>
                </td>
              </ng-container>

              <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>

              <tr class="mat-row" *matNoDataRow>
                <td class="mat-cell empty-row" [attr.colspan]="displayedColumns.length">
                  No users found yet.
                </td>
              </tr>
            </table>
            </div>
          }
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .subtitle {
      margin: 6px 0 0;
      color: var(--rw-text-secondary);
      font-size: 14px;
    }

    .entity-card { margin: 18px 0 16px; }

    .entity-info {
      display: flex;
      align-items: center;
      gap: 16px;
    }

    .entity-mark {
      width: 52px;
      height: 52px;
      border-radius: 18px;
      display: grid;
      place-items: center;
      background: linear-gradient(135deg, rgba(45, 212, 191, 0.18), rgba(13, 148, 136, 0.14));
      color: var(--rw-accent);
    }

    .entity-mark mat-icon { font-size: 26px; width: 26px; height: 26px; }
    .entity-info h3 { margin: 0 0 4px; color: var(--rw-text-primary); }
    .entity-info p { margin: 0; color: var(--rw-text-secondary); font-size: 13px; }
    .entity-external-id { margin-top: 12px; max-width: 520px; }

    .managed-banner {
      margin: 0 0 16px;
      padding: 14px 16px;
      border-radius: 16px;
      border: 1px solid rgba(13, 148, 136, 0.2);
      background: rgba(13, 148, 136, 0.08);
      color: var(--rw-text-primary);
      display: flex;
      gap: 12px;
      align-items: flex-start;
    }

    mat-card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 12px;
    }

    .user-cell {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .user-avatar {
      width: 34px;
      height: 34px;
      border-radius: 12px;
      display: grid;
      place-items: center;
      background: linear-gradient(135deg, rgba(45, 212, 191, 0.18), rgba(13, 148, 136, 0.12));
      color: var(--rw-accent);
      font-weight: 800;
      text-transform: uppercase;
    }

    .user-name {
      font-weight: 700;
      color: var(--rw-text-primary);
    }

    .user-meta {
      font-size: 12px;
      color: var(--rw-text-secondary);
    }

    .role-list {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }

    .role-chip {
      display: inline-flex;
      align-items: center;
      padding: 5px 10px;
      border-radius: 999px;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.04em;
      background: rgba(13, 148, 136, 0.1);
      color: var(--rw-accent);
    }

    .status-stack {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .status-pill {
      display: inline-flex;
      width: fit-content;
      padding: 4px 10px;
      border-radius: 999px;
      font-size: 11px;
      font-weight: 700;
      background: rgba(34, 197, 94, 0.12);
      color: #15803d;
    }

    .status-pill.status-disabled {
      background: rgba(239, 68, 68, 0.12);
      color: #b91c1c;
    }

    .pending-copy {
      font-size: 11px;
      color: var(--rw-text-muted);
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }

    .actions-cell {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      justify-content: flex-end;
    }

    .empty-row {
      text-align: center;
      padding: 32px;
      color: var(--rw-text-muted);
    }
    .table-wrap { overflow-x: auto; }
    .table-wrap table { min-width: 900px; }
    .load-error { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 10px; padding: 48px 16px; color: var(--rw-text-secondary); }
    .load-error mat-icon { color: var(--rw-text-danger); }
  `]
})
export class UserListComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly companyService = inject(CompanyService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  users: CompanyUser[] = [];
  entity: LegalEntity | null = null;
  managedExternally = false;
  loading = true;
  loadError = false;
  readonly busyUsers = new Set<string>();

  readonly displayedColumns = ['name', 'roles', 'status', 'lastLogin', 'actions'];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadError = false;
    forkJoin({
      entity: this.companyService.getMyEntity(),
      users: this.companyService.getEntityUsers(),
      idp: this.companyService.getIdpSettings(),
    }).subscribe({
      next: ({ entity, users, idp }) => {
        this.entity = entity;
        this.users = users;
        this.managedExternally = idp.lifecycleManagedExternally;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.loadError = true;
        this.cdr.markForCheck();
      }
    });
  }

  openInvite(): void {
    const ref = this.dialog.open(InviteUserDialogComponent, { width: '520px', maxWidth: '95vw' });
    ref.afterClosed().subscribe((user: CompanyUser | undefined) => {
      if (!user) return;
      this.users = [...this.users, user];
      this.snackBar.open(`Invitation sent to ${user.email}`, 'OK', { duration: 3000 });
      this.cdr.markForCheck();
    });
  }

  editRoles(user: CompanyUser): void {
    const ref = this.dialog.open(EditUserRolesDialogComponent, {
      width: '420px',
      maxWidth: '95vw',
      data: { name: user.name, roles: user.roles },
    });
    ref.afterClosed().subscribe((roles: UserRole[] | undefined) => {
      if (!roles || roles.join('|') === user.roles.join('|')) return;
      this.companyService.updateUserRoles(user.id, roles).subscribe({
        next: (updated) => {
          this.replaceUser(updated);
          this.snackBar.open(`Updated roles for ${updated.name}.`, 'OK', { duration: 3000 });
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Failed to update roles.', 'Dismiss', { duration: 4000 });
          this.cdr.markForCheck();
        }
      });
    });
  }

  toggleEnabled(user: CompanyUser): void {
    if (this.busyUsers.has(user.id)) return;
    this.busyUsers.add(user.id);
    const request$ = user.enabled
      ? this.companyService.disableUser(user.id)
      : this.companyService.enableUser(user.id);

    request$.subscribe({
      next: (updated) => {
        this.busyUsers.delete(user.id);
        this.replaceUser(updated);
        this.snackBar.open(
          user.enabled ? `${updated.name} has been disabled.` : `${updated.name} has been re-enabled.`,
          'OK',
          { duration: 3000 }
        );
      },
      error: (err) => {
        this.busyUsers.delete(user.id);
        this.snackBar.open(err?.error?.message ?? 'Failed to update status.', 'Dismiss', { duration: 4000 });
        this.cdr.markForCheck();
      }
    });
  }

  sendPasswordReset(user: CompanyUser): void {
    if (this.busyUsers.has(user.id)) return;
    this.busyUsers.add(user.id);
    this.companyService.sendPasswordReset(user.id).subscribe({
      next: () => {
        this.busyUsers.delete(user.id);
        this.snackBar.open(`Password reset link sent to ${user.email}.`, 'OK', { duration: 3000 });
      },
      error: (err) => {
        this.busyUsers.delete(user.id);
        this.snackBar.open(err?.error?.message ?? 'Failed to send password reset.', 'Dismiss', { duration: 4000 });
        this.cdr.markForCheck();
      }
    });
  }

  removeUser(user: CompanyUser): void {
    if (this.busyUsers.has(user.id)) return;
    if (!confirm(`Delete ${user.name} from your organisation?`)) return;
    this.busyUsers.add(user.id);

    this.companyService.removeUser(user.id).subscribe({
      next: () => {
        this.busyUsers.delete(user.id);
        this.users = this.users.filter(existing => existing.id !== user.id);
        this.snackBar.open(`${user.name} has been removed.`, 'OK', { duration: 3000 });
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.busyUsers.delete(user.id);
        this.snackBar.open(err?.error?.message ?? 'Failed to remove user.', 'Dismiss', { duration: 4000 });
        this.cdr.markForCheck();
      }
    });
  }

  roleLabel(role: UserRole): string {
    const labels: Record<UserRole, string> = {
      ISSUER: 'Issuer',
      INVESTOR: 'Investor',
      TRADER: 'Trader',
      COMPANY_ADMIN: 'Company Admin',
      REGISTRY_ADMIN: 'Registry Admin',
      AUDIT: 'Audit',
    };
    return labels[role] ?? role;
  }

  private replaceUser(updated: CompanyUser): void {
    this.users = this.users.map(existing => existing.id === updated.id ? updated : existing);
    this.cdr.markForCheck();
  }
}
