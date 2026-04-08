import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, RouterLink, RouterLinkActive } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { AuthService } from '../../core/auth/auth.service';

interface NavLink {
  label: string;
  route: string;
  icon: string;
  roles: string[];
}

@Component({
  selector: 'app-nav',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatDividerModule,
  ],
  template: `
    <mat-toolbar color="primary" class="app-toolbar">
      <!-- Brand -->
      <a routerLink="/dashboard" class="brand">
        <mat-icon class="brand-icon">account_balance</mat-icon>
        <span class="brand-name">Registerwerk</span>
        <span class="brand-sub">Customer Portal</span>
      </a>

      <span class="spacer"></span>

      <!-- Navigation links -->
      <nav class="nav-links" aria-label="Main navigation">
        @for (link of visibleLinks; track link.route) {
          <a
            mat-button
            [routerLink]="link.route"
            routerLinkActive="active-link"
            class="nav-link"
          >
            <mat-icon>{{ link.icon }}</mat-icon>
            {{ link.label }}
          </a>
        }
      </nav>

      <!-- User menu -->
      <button mat-icon-button [matMenuTriggerFor]="userMenu" aria-label="User menu">
        <mat-icon>account_circle</mat-icon>
      </button>

      <mat-menu #userMenu="matMenu">
        <div class="user-info" mat-menu-item disabled>
          <mat-icon>person</mat-icon>
          <span>{{ userName || userEmail || 'User' }}</span>
        </div>
        <mat-divider></mat-divider>
        <button mat-menu-item (click)="logout()">
          <mat-icon>logout</mat-icon>
          <span>Sign out</span>
        </button>
      </mat-menu>
    </mat-toolbar>
  `,
  styles: [`
    .app-toolbar {
      position: sticky;
      top: 0;
      z-index: 100;
      box-shadow: 0 2px 4px rgba(0,0,0,.2);
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 8px;
      text-decoration: none;
      color: inherit;
    }
    .brand-icon { font-size: 28px; width: 28px; height: 28px; }
    .brand-name { font-size: 18px; font-weight: 600; }
    .brand-sub {
      font-size: 11px;
      opacity: 0.7;
      background: rgba(255,255,255,0.15);
      padding: 2px 6px;
      border-radius: 4px;
    }
    .spacer { flex: 1; }
    .nav-links {
      display: flex;
      align-items: center;
      gap: 4px;
    }
    .nav-link {
      display: flex;
      align-items: center;
      gap: 4px;
      color: rgba(255,255,255,0.85) !important;
    }
    .nav-link.active-link {
      color: white !important;
      background: rgba(255,255,255,0.15) !important;
    }
    .user-info {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 16px;
      font-size: 13px;
      color: #444;
    }
  `]
})
export class NavComponent implements OnInit {
  private readonly auth = inject(AuthService);

  userName: string | null = null;
  userEmail: string | null = null;
  visibleLinks: NavLink[] = [];

  private readonly allLinks: NavLink[] = [
    { label: 'Dashboard',       route: '/dashboard',     icon: 'dashboard',  roles: [] },
    { label: 'My Issuances',    route: '/issuances',     icon: 'description', roles: ['ISSUER', 'REGISTRY_ADMIN'] },
    { label: 'My Investments',  route: '/investments',   icon: 'savings',    roles: ['INVESTOR', 'REGISTRY_ADMIN'] },
    { label: 'Company Admin',   route: '/company-admin', icon: 'manage_accounts', roles: ['COMPANY_ADMIN'] },
    { label: 'KYC Documents',   route: '/kyc',           icon: 'verified_user', roles: [] },
  ];

  ngOnInit(): void {
    this.userName = this.auth.getUserName();
    this.userEmail = this.auth.getUserEmail();
    this.visibleLinks = this.allLinks.filter(link =>
      link.roles.length === 0 || link.roles.some(r => this.auth.hasRole(r))
    );
  }

  logout(): void {
    this.auth.logout();
  }
}
