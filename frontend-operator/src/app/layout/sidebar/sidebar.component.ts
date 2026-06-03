import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { environment } from '../../../environments/environment';

interface NavItem {
  label: string;
  icon: string;
  route: string;
}

interface NavSection {
  label: string;
  items: NavItem[];
}

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, MatIconModule, MatTooltipModule],
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
      padding: 12px 14px;
      border-top: 1px solid var(--rw-sidebar-border);
      flex-shrink: 0;
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .sidebar-footer-text {
      font-size: 10px;
      color: var(--rw-sidebar-fg);
      opacity: 0.4;
      letter-spacing: 0.3px;
      flex: 1;
    }

    .portal-switch-btn {
      display: flex;
      align-items: center;
      gap: 5px;
      padding: 4px 10px;
      border-radius: 6px;
      border: 1px solid rgba(245,158,11,0.25);
      background: rgba(245,158,11,0.08);
      color: rgba(245,158,11,0.8);
      font-size: 11px;
      font-weight: 600;
      font-family: 'Manrope', sans-serif;
      cursor: pointer;
      text-decoration: none;
      letter-spacing: 0.2px;
      transition: background 0.15s, border-color 0.15s;
      white-space: nowrap;

      mat-icon { font-size: 14px; width: 14px; height: 14px; }

      &:hover {
        background: rgba(245,158,11,0.16);
        border-color: rgba(245,158,11,0.4);
        color: #F59E0B;
      }
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
      @for (section of navSections; track section.label) {
        <div class="nav-section-label">{{ section.label }}</div>
        @for (item of section.items; track item.route) {
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
      }
    </nav>

    <div class="sidebar-footer">
      <div class="sidebar-footer-text">Registerwerk v1.0</div>
      @if (customerUrl) {
        <a [href]="customerUrl" target="_blank" class="portal-switch-btn"
           matTooltip="Open Customer Portal in a new tab">
          <mat-icon>open_in_new</mat-icon>
          Customer
        </a>
      }
    </div>
  `,
})
export class SidebarComponent {
  readonly customerUrl = environment.customerUrl;
  readonly navSections: NavSection[] = [
    {
      label: 'Overview',
      items: [
        { label: 'Dashboard',  icon: 'grid_view',     route: '/dashboard' },
      ],
    },
    {
      label: 'Registry',
      items: [
        { label: 'Assets',     icon: 'account_balance_wallet', route: '/assets' },
        { label: 'Customers',  icon: 'people_outline',         route: '/customers' },
        { label: 'Registry',   icon: 'hub',                    route: '/registry' },
        { label: 'Onboarding', icon: 'person_add_alt',         route: '/onboarding' },
      ],
    },
    {
      label: 'Compliance',
      items: [
        { label: 'Screening',      icon: 'policy',                    route: '/compliance/screening' },
        { label: 'Holder Blocks',  icon: 'gavel',                     route: '/compliance/holder-blocks' },
        { label: 'DORA',           icon: 'security_update_warning',   route: '/compliance/dora' },
        { label: 'Reporting',      icon: 'assessment',                route: '/compliance/reporting' },
        { label: 'Audit Log',      icon: 'receipt_long',              route: '/audit' },
      ],
    },
    {
      label: 'Infrastructure',
      items: [
        { label: 'Wallets',       icon: 'wallet',    route: '/wallets' },
        { label: 'Network Nodes', icon: 'cable',     route: '/network-nodes' },
        { label: 'Endpoints',     icon: 'contacts',  route: '/endpoints' },
        { label: 'Users',         icon: 'group',     route: '/users' },
      ],
    },
  ];
}
