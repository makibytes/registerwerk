import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatTabsModule } from '@angular/material/tabs';
import { CompanyService } from '../../../core/api/company.service';
import { CompanyUser, LegalEntity, UserRole } from '../../../core/models';
import { InviteUserDialogComponent } from './invite-user-dialog.component';

@Component({
  selector: 'app-user-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    RouterLinkActive,
    FormsModule,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    MatTooltipModule,
    MatTabsModule,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>Company Admin</h1>
      </div>

      <!-- Sub-tabs -->
      <nav mat-tab-nav-bar [tabPanel]="tabPanel">
        <a mat-tab-link routerLink="/company-admin/users" routerLinkActive #rla1="routerLinkActive" [active]="rla1.isActive">
          <mat-icon>people</mat-icon>&nbsp;Users
        </a>
        <a mat-tab-link routerLink="/company-admin/idp" routerLinkActive #rla2="routerLinkActive" [active]="rla2.isActive">
          <mat-icon>vpn_key</mat-icon>&nbsp;IdP Settings
        </a>
      </nav>
      <mat-tab-nav-panel #tabPanel></mat-tab-nav-panel>

      <!-- Entity Info -->
      @if (entity) {
        <mat-card class="entity-card">
          <mat-card-content>
            <div class="entity-info">
              <mat-icon class="entity-icon">business</mat-icon>
              <div>
                <h3>{{ entity.legalName }}</h3>
                <p>Registration: {{ entity.registrationNumber }} · {{ entity.jurisdiction }}</p>
              </div>
            </div>
          </mat-card-content>
        </mat-card>
      }

      <!-- Users section -->
      <mat-card>
        <mat-card-header>
          <mat-card-title>Users ({{ users.length }})</mat-card-title>
          <div mat-card-avatar></div>
          <button mat-raised-button color="primary" (click)="openInvite()">
            <mat-icon>person_add</mat-icon>
            Invite User
          </button>
        </mat-card-header>
        <mat-card-content>
          @if (loading) {
            <div class="loading-overlay"><mat-spinner diameter="36"></mat-spinner></div>
          } @else {
            <table mat-table [dataSource]="users" class="mat-elevation-z0">
              <ng-container matColumnDef="name">
                <th mat-header-cell *matHeaderCellDef>Name</th>
                <td mat-cell *matCellDef="let u">{{ u.name }}</td>
              </ng-container>
              <ng-container matColumnDef="email">
                <th mat-header-cell *matHeaderCellDef>Email</th>
                <td mat-cell *matCellDef="let u">{{ u.email }}</td>
              </ng-container>
              <ng-container matColumnDef="role">
                <th mat-header-cell *matHeaderCellDef>Role</th>
                <td mat-cell *matCellDef="let u">
                  <span class="role-chip role-{{ u.role.toLowerCase() }}">{{ roleLabel(u.role) }}</span>
                </td>
              </ng-container>
              <ng-container matColumnDef="actions">
                <th mat-header-cell *matHeaderCellDef></th>
                <td mat-cell *matCellDef="let u" class="actions-cell">
                  <button
                    mat-icon-button
                    color="warn"
                    matTooltip="Remove user"
                    (click)="removeUser(u)"
                  >
                    <mat-icon>person_remove</mat-icon>
                  </button>
                </td>
              </ng-container>

              <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>

              <tr class="mat-row" *matNoDataRow>
                <td class="mat-cell empty-row" [attr.colspan]="displayedColumns.length">
                  No users found. Invite your first team member.
                </td>
              </tr>
            </table>
          }
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .entity-card { margin-bottom: 16px; }
    .entity-info { display: flex; align-items: center; gap: 16px; }
    .entity-icon { font-size: 40px; width: 40px; height: 40px; color: #00695c; }
    .entity-info h3 { margin: 0 0 4px; }
    .entity-info p { margin: 0; font-size: 13px; color: #546e7a; }
    mat-card-header { display: flex; align-items: center; justify-content: space-between; }
    .role-chip {
      display: inline-block;
      padding: 2px 10px;
      border-radius: 12px;
      font-size: 12px;
      font-weight: 500;
      background: #e0f2f1;
      color: #00695c;
    }
    .empty-row { text-align: center; padding: 32px; color: #78909c; }
  `]
})
export class UserListComponent implements OnInit {
  private readonly companyService = inject(CompanyService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  users: CompanyUser[] = [];
  entity: LegalEntity | null = null;
  loading = true;

  readonly displayedColumns = ['name', 'email', 'role', 'actions'];

  ngOnInit(): void {
    this.companyService.getMyEntity().subscribe({ next: e => (this.entity = e), error: () => {} });
    this.loadUsers();
  }

  openInvite(): void {
    const ref = this.dialog.open(InviteUserDialogComponent, { width: '460px' });
    ref.afterClosed().subscribe((user: CompanyUser | undefined) => {
      if (user) {
        this.users = [...this.users, user];
        this.snackBar.open(`Invitation sent to ${user.email}`, 'OK', { duration: 3000 });
      }
    });
  }

  removeUser(user: CompanyUser): void {
    if (!confirm(`Remove ${user.name} from your organisation?`)) return;

    this.companyService.removeUser(user.id).subscribe({
      next: () => {
        this.users = this.users.filter(u => u.id !== user.id);
        this.snackBar.open(`${user.name} has been removed.`, 'OK', { duration: 3000 });
      },
      error: () => {
        this.snackBar.open('Failed to remove user.', 'Dismiss', { duration: 4000 });
      }
    });
  }

  roleLabel(role: UserRole): string {
    const labels: Record<UserRole, string> = {
      ISSUER:         'Issuer',
      INVESTOR:       'Investor',
      COMPANY_ADMIN:  'Company Admin',
      REGISTRY_ADMIN: 'Registry Admin',
      AUDITOR:        'Auditor',
    };
    return labels[role] ?? role;
  }

  private loadUsers(): void {
    this.loading = true;
    this.companyService.getEntityUsers().subscribe({
      next: (users) => { this.users = users; this.loading = false; },
      error: () => (this.loading = false),
    });
  }
}
