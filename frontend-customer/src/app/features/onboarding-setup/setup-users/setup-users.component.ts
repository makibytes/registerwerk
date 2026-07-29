import { ChangeDetectorRef, Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatListModule } from '@angular/material/list';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { CompanyService } from '../../../core/api/company.service';
import { CompanyUser, UserRole } from '../../../core/models';

@Component({
  selector: 'app-setup-users',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatListModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>Set Up Users</h1>
        <p class="subtitle">Invite colleagues to your organisation</p>
      </div>

      <mat-card>
        <mat-card-content>
          <!-- Invite form -->
          <div class="invite-form">
            <h3>Invite a User</h3>
            <div class="form-row">
              <mat-form-field appearance="outline" class="flex-field">
                <mat-label>Email</mat-label>
                <input matInput type="email" [(ngModel)]="inviteEmail" placeholder="colleague@company.com" />
              </mat-form-field>

              <mat-form-field appearance="outline" class="flex-field">
                <mat-label>Name</mat-label>
                <input matInput [(ngModel)]="inviteName" placeholder="Full Name" />
              </mat-form-field>

              <mat-form-field appearance="outline" style="width:240px">
                <mat-label>Roles</mat-label>
                <mat-select [(ngModel)]="inviteRoles" multiple>
                  @for (role of availableRoles; track role.value) {
                    <mat-option [value]="role.value">{{ role.label }}</mat-option>
                  }
                </mat-select>
              </mat-form-field>

              <button
                mat-raised-button
                color="primary"
                [disabled]="!inviteEmail || !inviteName || inviting || inviteRoles.length === 0"
                (click)="invite()"
              >
                @if (inviting) { <mat-spinner diameter="18"></mat-spinner> }
                @else { Invite }
              </button>
            </div>
          </div>

          <!-- Existing users list -->
          @if (users.length > 0) {
            <h3>Users ({{ users.length }})</h3>
            <mat-list>
              @for (user of users; track user.id) {
                <mat-list-item>
                  <mat-icon matListItemIcon>person</mat-icon>
                  <span matListItemTitle>{{ user.name }}</span>
                  <span matListItemLine>{{ user.email }} · {{ user.roles.join(', ') }}</span>
                </mat-list-item>
              }
            </mat-list>
          }
        </mat-card-content>

        <mat-card-actions align="end">
          <button mat-button (click)="skip()">Skip for now</button>
          <button mat-raised-button color="accent" (click)="next()">
            Next: IdP Settings
            <mat-icon>arrow_forward</mat-icon>
          </button>
        </mat-card-actions>
      </mat-card>
    </div>
  `,
  styles: [`
    .page-container { padding: 32px; max-width: 800px; margin: 0 auto; }
    .page-header h1 { margin: 0; }
    .subtitle { color: var(--rw-text-secondary); margin: 4px 0 24px; }
    .invite-form { margin-bottom: 24px; }
    .form-row { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
    .flex-field { flex: 1; min-width: 180px; }
  `]
})
export class SetupUsersComponent implements OnInit {
  private readonly company = inject(CompanyService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  users: CompanyUser[] = [];
  inviteEmail = '';
  inviteName = '';
  inviteRoles: UserRole[] = ['ISSUER'];
  inviting = false;

  readonly availableRoles = [
    { value: 'ISSUER' as UserRole,        label: 'Issuer' },
    { value: 'INVESTOR' as UserRole,      label: 'Investor' },
    { value: 'TRADER' as UserRole,        label: 'Trader' },
    { value: 'COMPANY_ADMIN' as UserRole, label: 'Company Admin' },
    { value: 'AUDIT' as UserRole,         label: 'Audit' },
  ];

  ngOnInit(): void {
    this.company.getEntityUsers().subscribe({
      next: users => { this.users = users; this.cdr.markForCheck(); },
      error: () => {
        this.snackBar.open('Failed to load company users', 'Dismiss', { duration: 5000 });
        this.cdr.markForCheck();
      }
    });
  }

  invite(): void {
    this.inviting = true;
    this.company.inviteUser({ email: this.inviteEmail, name: this.inviteName, roles: this.inviteRoles }).subscribe({
      next: (user) => {
        this.inviting = false;
        this.users = [...this.users, user];
        this.inviteEmail = '';
        this.inviteName = '';
        this.inviteRoles = ['ISSUER'];
        this.cdr.markForCheck();
        this.snackBar.open(`Invitation sent to ${user.email}`, 'OK', { duration: 3000 });
      },
      error: () => {
        this.inviting = false;
        this.cdr.markForCheck();
        this.snackBar.open('Failed to send invitation', 'Dismiss', { duration: 4000 });
      }
    });
  }

  next(): void {
    this.router.navigate(['/onboarding/setup-idp']);
  }

  skip(): void {
    this.router.navigate(['/dashboard']);
  }
}
