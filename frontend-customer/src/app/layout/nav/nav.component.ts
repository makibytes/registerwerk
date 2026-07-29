import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../../core/auth/auth.service';
import { WorkspaceKey, WorkspaceService } from '../../core/workspace/workspace.service';
import { environment } from '../../../environments/environment';

const { operatorUrl } = environment;

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
    MatTooltipModule,
  ],
  template: `
    @if (isImpersonating || canImpersonate) {
      <div class="impersonation-bar">
        <mat-icon>admin_panel_settings</mat-icon>
        @if (isImpersonating) {
          <span>Acting as <strong>{{ impersonationEntityName }}</strong></span>
          <div class="imp-actions">
            <button class="imp-btn" (click)="switchCompany()">Switch company</button>
            <button class="imp-btn imp-btn-exit" (click)="exitImpersonation()">Exit impersonation</button>
          </div>
        } @else {
          <span>Admin mode — no company selected</span>
          <div class="imp-actions">
            <button class="imp-btn" (click)="selectCompany()">Select company</button>
          </div>
        }
        @if (operatorUrl) {
          <a [href]="operatorUrl" target="_blank" class="imp-btn imp-btn-portal">
            Operator Console ↗
          </a>
        }
      </div>
    }
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

      @if (eligibleWorkspaces.length > 1) {
        <button class="workspace-switch" mat-button [matMenuTriggerFor]="workspaceMenu">
          <mat-icon>{{ activeWorkspace.icon }}</mat-icon>
          <span>{{ activeWorkspace.label }}</span>
          <mat-icon class="chevron">expand_more</mat-icon>
        </button>
        <mat-menu #workspaceMenu="matMenu">
          @for (ws of eligibleWorkspaces; track ws.key) {
            <button mat-menu-item (click)="switchWorkspace(ws.key)" [class.active-item]="ws.key === activeWorkspace.key">
              <mat-icon>{{ ws.icon }}</mat-icon>
              <span>{{ ws.label }}</span>
            </button>
          }
        </mat-menu>
        <div class="divider-v"></div>
      } @else if (activeWorkspace) {
        <span class="workspace-label">
          <mat-icon>{{ activeWorkspace.icon }}</mat-icon>
          {{ activeWorkspace.label }}
        </span>
        <div class="divider-v"></div>
      }

      <nav class="nav-links" aria-label="Main navigation">
        @for (link of workspaceLinks; track link.route) {
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

      <a mat-icon-button routerLink="/kyc" routerLinkActive="active" matTooltip="KYC status" class="utility-icon">
        <mat-icon>verified_user</mat-icon>
      </a>
      <a mat-icon-button routerLink="/endpoints" routerLinkActive="active" matTooltip="Endpoints" class="utility-icon">
        <mat-icon>contacts</mat-icon>
      </a>

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
    .impersonation-bar {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 7px 20px;
      background: var(--rw-pending-fg);
      color: var(--rw-surface);
      font-size: 13px;
      font-weight: 600;
      position: relative;
      z-index: 99;

      mat-icon { font-size: 16px; width: 16px; height: 16px; }

      .imp-actions { margin-left: auto; display: flex; gap: 8px; }

      .imp-btn {
        background: rgba(255,255,255,0.15);
        border: 1px solid rgba(255,255,255,0.25);
        border-radius: 5px;
        color: var(--rw-surface);
        font-size: 11px;
        font-weight: 700;
        padding: 3px 10px;
        cursor: pointer;
        font-family: 'Manrope', sans-serif;

        &:hover { background: rgba(255,255,255,0.25); }
      }

      .imp-btn-exit {
        background: rgba(220,38,38,0.3);
        border-color: rgba(220,38,38,0.5);

        &:hover { background: rgba(220,38,38,0.5); }
      }

      .imp-btn-portal {
        margin-left: 6px;
        background: rgba(13,148,136,0.18);
        border-color: rgba(13,148,136,0.35);
        color: var(--rw-nav-accent);
        text-decoration: none;

        &:hover { background: rgba(13,148,136,0.3); }
      }
    }

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

    .workspace-switch {
      display: flex !important;
      align-items: center !important;
      gap: 6px !important;
      padding: 4px 10px 4px 8px !important;
      border-radius: 8px !important;
      color: var(--rw-nav-fg) !important;
      height: auto !important;
      font-weight: 600 !important;
      transition: background 0.15s ease !important;

      mat-icon:not(.chevron) {
        font-size: 17px;
        width: 17px;
        height: 17px;
        color: var(--rw-nav-accent);
      }

      &:hover { background: var(--rw-nav-hover-bg) !important; }
    }

    .workspace-label {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      font-size: 13px;
      font-weight: 600;
      color: var(--rw-nav-fg);

      mat-icon {
        font-size: 17px;
        width: 17px;
        height: 17px;
        color: var(--rw-nav-accent);
      }
    }

    .active-item {
      background: var(--rw-nav-active-bg);
    }

    .utility-icon {
      color: var(--rw-nav-fg) !important;

      &.active {
        color: var(--rw-nav-accent) !important;
      }
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
  private readonly router = inject(Router);
  private readonly workspaceService = inject(WorkspaceService);
  readonly isTestEnv = environment.testEnvironment;
  readonly operatorUrl = operatorUrl;

  userName: string | null = null;
  userEmail: string | null = null;
  isImpersonating = false;
  canImpersonate = false;
  impersonationEntityName = '';

  eligibleWorkspaces = this.workspaceService.eligibleWorkspaces();
  activeWorkspace = this.workspaceService.activeWorkspace();

  get workspaceLinks() {
    return this.activeWorkspace?.links ?? [];
  }

  ngOnInit(): void {
    this.userName = this.auth.getUserName();
    this.userEmail = this.auth.getUserEmail();
    this.isImpersonating = this.auth.isImpersonating();
    this.canImpersonate = this.auth.hasRole('REGISTRY_ADMIN');
    const meta = this.auth.getImpersonationMeta();
    this.impersonationEntityName = meta?.entityName ?? '';
    this.eligibleWorkspaces = this.workspaceService.eligibleWorkspaces();
    this.activeWorkspace = this.workspaceService.activeWorkspace();
  }

  switchWorkspace(key: WorkspaceKey): void {
    this.workspaceService.setWorkspace(key);
    this.activeWorkspace = this.workspaceService.activeWorkspace();
  }

  selectCompany(): void {
    this.router.navigate(['/select-company']);
  }

  switchCompany(): void {
    this.auth.exitImpersonation();
    this.router.navigate(['/select-company']);
  }

  exitImpersonation(): void {
    this.auth.exitImpersonation();
    this.router.navigate(['/select-company']);
  }

  logout(): void { this.auth.logout(); }
}
