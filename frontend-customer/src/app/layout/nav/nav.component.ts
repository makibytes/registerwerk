import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { AuthService } from '../../core/auth/auth.service';
import { environment } from '../../../environments/environment';

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
    RouterLink,
    RouterLinkActive,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatDividerModule,
  ],
  template: `
    <header class="nav-bar">
      <a routerLink="/dashboard" class="brand" aria-label="Registerwerk Home">
        <div class="brand-icon">
          <mat-icon>account_balance</mat-icon>
        </div>
        <div class="brand-text">
          <span class="brand-name">Registerwerk</span>
          <span class="brand-sub">Customer Portal</span>
        </div>
      </a>

      <div class="divider-v"></div>

      <nav class="nav-links" aria-label="Main navigation">
        @for (link of visibleLinks; track link.route) {
          <a
            class="nav-link"
            [routerLink]="link.route"
            routerLinkActive="active"
          >
            <mat-icon>{{ link.icon }}</mat-icon>
            <span>{{ link.label }}</span>
          </a>
        }
      </nav>

      <div class="spacer"></div>

      @if (isTestEnv) {
        <span class="env-badge">Test</span>
      }

      <button class="user-btn" mat-button [matMenuTriggerFor]="userMenu" aria-label="User menu">
        <div class="avatar">{{ (userName || userEmail || 'U')[0].toUpperCase() }}</div>
        <span class="user-label">{{ userName || userEmail || 'User' }}</span>
        <mat-icon class="chevron">expand_more</mat-icon>
      </button>

      <mat-menu #userMenu="matMenu" class="user-menu-panel">
        <div class="menu-user-info" mat-menu-item disabled>
          <div class="menu-avatar">{{ (userName || userEmail || 'U')[0].toUpperCase() }}</div>
          <div>
            <div class="menu-user-name">{{ userName || 'User' }}</div>
            <div class="menu-user-email">{{ userEmail }}</div>
          </div>
        </div>
        <mat-divider></mat-divider>
        <button mat-menu-item (click)="logout()">
          <mat-icon>logout</mat-icon>
          <span>Sign out</span>
        </button>
      </mat-menu>
    </header>
  `,
  styles: [`
    .nav-bar {
      display: flex;
      align-items: center;
      height: 56px;
      padding: 0 20px;
      background: var(--rw-nav-bg);
      border-bottom: 1px solid var(--rw-nav-border);
      position: sticky;
      top: 0;
      z-index: 100;
      gap: 4px;
    }

    .brand {
      display: flex;
      align-items: center;
      gap: 10px;
      text-decoration: none;
      flex-shrink: 0;
      margin-right: 4px;
    }

    .brand-icon {
      width: 32px;
      height: 32px;
      border-radius: 8px;
      background: linear-gradient(135deg, #2DD4BF 0%, #0D9488 100%);
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;

      mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
        color: #FFFFFF;
      }
    }

    .brand-text {
      display: flex;
      flex-direction: column;
    }

    .brand-name {
      font-size: 14px;
      font-weight: 700;
      color: var(--rw-nav-brand-name);
      letter-spacing: -0.2px;
      line-height: 1.2;
    }

    .brand-sub {
      font-size: 10px;
      font-weight: 500;
      color: var(--rw-nav-fg);
      letter-spacing: 0.5px;
    }

    .divider-v {
      width: 1px;
      height: 20px;
      background: var(--rw-nav-border);
      margin: 0 12px;
      flex-shrink: 0;
    }

    .nav-links {
      display: flex;
      align-items: center;
      gap: 2px;
    }

    .nav-link {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 6px 12px;
      border-radius: 7px;
      text-decoration: none;
      color: var(--rw-nav-fg);
      font-size: 13px;
      font-weight: 500;
      transition: background 0.15s ease, color 0.15s ease;
      letter-spacing: 0.1px;

      mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
        opacity: 0.7;
      }

      &:hover {
        background: var(--rw-nav-hover-bg);
        color: var(--rw-nav-fg-hover);

        mat-icon { opacity: 0.9; }
      }

      &.active {
        background: var(--rw-nav-active-bg);
        color: var(--rw-nav-fg-active);

        mat-icon { opacity: 1; color: var(--rw-nav-accent); }
      }
    }

    .spacer { flex: 1; }

    .user-btn {
      display: flex !important;
      align-items: center !important;
      gap: 8px !important;
      padding: 4px 8px 4px 6px !important;
      border-radius: 8px !important;
      color: var(--rw-nav-fg) !important;
      height: auto !important;
      transition: background 0.15s ease !important;

      &:hover { background: var(--rw-nav-hover-bg) !important; }
    }

    .avatar {
      width: 26px;
      height: 26px;
      border-radius: 50%;
      background: linear-gradient(135deg, #2DD4BF 0%, #0D9488 100%);
      color: white;
      font-size: 12px;
      font-weight: 700;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }

    .user-label {
      font-size: 13px;
      font-weight: 500;
      color: var(--rw-nav-user-label);
      max-width: 120px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .chevron {
      font-size: 18px !important;
      width: 18px !important;
      height: 18px !important;
      opacity: 0.5;
    }

    .env-badge {
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.8px;
      text-transform: uppercase;
      padding: 2px 8px;
      border-radius: 4px;
      background: rgba(251, 146, 60, 0.15);
      color: #FB923C;
      border: 1px solid rgba(251, 146, 60, 0.3);
      flex-shrink: 0;
      margin-right: 4px;
    }

    .menu-user-info {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 10px 16px !important;
      cursor: default;
      pointer-events: none;
      opacity: 1 !important;
    }

    .menu-avatar {
      width: 32px;
      height: 32px;
      border-radius: 50%;
      background: linear-gradient(135deg, #2DD4BF 0%, #0D9488 100%);
      color: white;
      font-size: 14px;
      font-weight: 700;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }

    .menu-user-name {
      font-size: 13px;
      font-weight: 600;
      line-height: 1.3;
    }

    .menu-user-email {
      font-size: 11px;
      color: var(--rw-text-secondary);
      line-height: 1.3;
    }
  `]
})
export class NavComponent implements OnInit {
  private readonly auth = inject(AuthService);
  readonly isTestEnv = environment.testEnvironment;

  userName: string | null = null;
  userEmail: string | null = null;
  visibleLinks: NavLink[] = [];

  private readonly allLinks: NavLink[] = [
    { label: 'Dashboard',      route: '/dashboard',     icon: 'grid_view',       roles: [] },
    { label: 'Issuances',      route: '/issuances',     icon: 'description',     roles: ['ISSUER', 'REGISTRY_ADMIN'] },
    { label: 'Investments',    route: '/investments',   icon: 'savings',         roles: ['INVESTOR', 'REGISTRY_ADMIN'] },
    { label: 'Trading',        route: '/trading',       icon: 'candlestick_chart', roles: ['TRADER', 'REGISTRY_ADMIN'] },
    { label: 'Company Admin',  route: '/company-admin', icon: 'manage_accounts', roles: ['COMPANY_ADMIN'] },
    { label: 'KYC',            route: '/kyc',           icon: 'verified_user',   roles: [] },
    { label: 'Endpoints',     route: '/endpoints',     icon: 'contacts',        roles: [] },
  ];

  ngOnInit(): void {
    this.userName = this.auth.getUserName();
    this.userEmail = this.auth.getUserEmail();
    this.visibleLinks = this.allLinks.filter(link =>
      link.roles.length === 0 || link.roles.some(r => this.auth.hasRole(r))
    );
  }

  logout(): void { this.auth.logout(); }
}
