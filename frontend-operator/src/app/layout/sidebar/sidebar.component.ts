import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';

interface NavItem {
  label: string;
  icon: string;
  route: string;
}

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, MatIconModule],
  styles: [`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
    }

    .brand {
      padding: 22px 18px 18px;
      border-bottom: 1px solid var(--rw-sidebar-border);
      background: var(--rw-sidebar-brand-bg);
      flex-shrink: 0;
    }

    .brand-lockup {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .brand-icon-wrap {
      width: 32px;
      height: 32px;
      border-radius: 8px;
      background: linear-gradient(135deg, #F59E0B 0%, #D97706 100%);
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;

      mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
        color: #07091A;
      }
    }

    .brand-text {
      .brand-name {
        font-size: 14px;
        font-weight: 700;
        color: var(--rw-sidebar-brand-name);
        margin: 0;
        letter-spacing: -0.2px;
        line-height: 1.2;
      }

      .brand-tag {
        font-size: 10px;
        font-weight: 600;
        color: var(--rw-sidebar-fg);
        letter-spacing: 0.8px;
        text-transform: uppercase;
        margin: 2px 0 0;
      }
    }

    .nav-section {
      padding: 16px 10px 8px;
      flex: 1;
      overflow-y: auto;
    }

    .nav-section-label {
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.9px;
      text-transform: uppercase;
      color: var(--rw-sidebar-fg);
      padding: 0 8px 8px;
      opacity: 0.5;
    }

    .nav-item {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 10px;
      border-radius: 7px;
      margin-bottom: 2px;
      text-decoration: none;
      color: var(--rw-sidebar-fg);
      font-size: 13px;
      font-weight: 500;
      cursor: pointer;
      transition: background 0.15s ease, color 0.15s ease;
      letter-spacing: 0.1px;

      mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
        color: var(--rw-sidebar-icon);
        flex-shrink: 0;
        transition: color 0.15s ease;
      }

      &:hover {
        background: var(--rw-sidebar-hover-bg);
        color: var(--rw-sidebar-fg-hover);

        mat-icon { color: rgba(255,255,255,0.7); }
      }

      &.active {
        background: var(--rw-sidebar-active-bg);
        color: var(--rw-sidebar-fg-active);

        mat-icon { color: var(--rw-sidebar-icon-active); }
      }
    }

    .sidebar-footer {
      padding: 14px 18px;
      border-top: 1px solid var(--rw-sidebar-border);
      flex-shrink: 0;
    }

    .sidebar-footer-text {
      font-size: 10px;
      color: var(--rw-sidebar-fg);
      opacity: 0.4;
      letter-spacing: 0.3px;
    }
  `],
  template: `
    <div class="brand">
      <div class="brand-lockup">
        <div class="brand-icon-wrap">
          <mat-icon>account_balance</mat-icon>
        </div>
        <div class="brand-text">
          <p class="brand-name">Registerwerk</p>
          <p class="brand-tag">Operator Portal</p>
        </div>
      </div>
    </div>

    <nav class="nav-section">
      <div class="nav-section-label">Navigation</div>
      @for (item of navItems; track item.route) {
        <a
          class="nav-item"
          [routerLink]="item.route"
          routerLinkActive="active"
          [routerLinkActiveOptions]="{ exact: item.route === '/dashboard' }"
        >
          <mat-icon>{{ item.icon }}</mat-icon>
          {{ item.label }}
        </a>
      }
    </nav>

    <div class="sidebar-footer">
      <div class="sidebar-footer-text">Registerwerk v1.0</div>
    </div>
  `,
})
export class SidebarComponent {
  readonly navItems: NavItem[] = [
    { label: 'Dashboard',     icon: 'grid_view',              route: '/dashboard' },
    { label: 'Assets',        icon: 'account_balance_wallet', route: '/assets' },
    { label: 'Audit Log',     icon: 'receipt_long',           route: '/audit' },
    { label: 'Customers',     icon: 'people_outline',         route: '/customers' },
    { label: 'Endpoints',     icon: 'contacts',               route: '/endpoints' },
    { label: 'Network Nodes', icon: 'cable',                  route: '/network-nodes' },
    { label: 'Onboarding',    icon: 'person_add_alt',         route: '/onboarding' },
    { label: 'Registry',      icon: 'hub',                    route: '/registry' },
    { label: 'Users',         icon: 'group',                  route: '/users' },
    { label: 'Wallets',       icon: 'wallet',                 route: '/wallets' },
  ];
}
