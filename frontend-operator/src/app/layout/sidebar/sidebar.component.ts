import { Component, inject, output } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { environment } from '../../../environments/environment';
import { AuthService } from '../../core/auth/auth.service';

interface NavItem {
  label: string;
  icon: string;
  route: string;
  roles?: string[];
}

interface NavSection {
  label: string;
  items: NavItem[];
  /** Section is shown if the user has ANY of these roles; omit to show to everyone. */
  roles?: string[];
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
        color: var(--rw-accent-contrast);
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

        mat-icon { color: var(--rw-sidebar-icon-hover); }
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
      font-family: 'Manrope Variable', sans-serif;
      cursor: pointer;
      text-decoration: none;
      letter-spacing: 0.2px;
      transition: background 0.15s, border-color 0.15s;
      white-space: nowrap;

      mat-icon { font-size: 14px; width: 14px; height: 14px; }

      &:hover {
        background: rgba(245,158,11,0.16);
        border-color: rgba(245,158,11,0.4);
        color: var(--rw-accent);
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

    <nav class="nav-section" aria-label="Operator navigation">
      @for (section of visibleSections; track section.label) {
        <div class="nav-section-label">{{ section.label }}</div>
        @for (item of section.items; track item.route) {
          <a
            class="nav-item"
            [routerLink]="item.route"
            routerLinkActive="active"
            [routerLinkActiveOptions]="{ exact: item.route === '/dashboard' }"
            (click)="navigated.emit()"
          >
            <mat-icon>{{ item.icon }}</mat-icon>
            <span>{{ item.label }}</span>
          </a>
        }
      }
    </nav>

    <div class="sidebar-footer">
      <div class="sidebar-footer-text">Registerwerk v1.0</div>
      @if (customerUrl) {
        <a [href]="customerUrl" target="_blank" rel="noopener noreferrer" class="portal-switch-btn"
           matTooltip="Open Customer Portal in a new tab">
          <mat-icon>open_in_new</mat-icon>
          Customer
        </a>
      }
    </div>
  `,
})
export class SidebarComponent {
  private readonly auth = inject(AuthService);

  readonly navigated = output<void>();
  readonly customerUrl = environment.customerUrl;
  readonly navSections: NavSection[] = [
    {
      label: 'Overview',
      items: [
        { label: 'Dashboard',  icon: 'grid_view',     route: '/dashboard' },
      ],
    },
    {
      label: 'Client Servicing',
      roles: ['RELATIONSHIP_MANAGER'],
      items: [
        { label: 'My Clients', icon: 'contact_page', route: '/my-clients' },
      ],
    },
    {
      label: 'Registry',
      roles: ['REGISTRY_ADMIN', 'AUDIT'],
      items: [
        { label: 'Assets',     icon: 'account_balance_wallet', route: '/assets' },
        { label: 'Customers',  icon: 'people_outline',         route: '/customers' },
        { label: 'Registry',   icon: 'hub',                    route: '/registry' },
        { label: 'Onboarding', icon: 'person_add_alt', route: '/onboarding', roles: ['REGISTRY_ADMIN'] },
      ],
    },
    {
      label: 'Compliance',
      roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT'],
      items: [
        { label: 'Screening', icon: 'policy', route: '/compliance/screening', roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'] },
        { label: 'Chain Drift', icon: 'sync_problem', route: '/compliance/chain-drift', roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT'] },
        { label: 'Unresolved Compensation', icon: 'report_problem', route: '/compliance/unresolved-compensation', roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT'] },
        { label: 'Finality Policy', icon: 'rule', route: '/compliance/finality-policy', roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT'] },
        // Not under Infrastructure (REGISTRY_ADMIN-only, see the Operations section note below)
        // — IndexerAdminController's read access is REGISTRY_ADMIN/COMPLIANCE_OFFICER/AUDIT,
        // matching this section's roles exactly, and indexer sync health is the mechanical
        // counterpart to Chain Drift right above it.
        { label: 'Indexers', icon: 'sync', route: '/indexers', roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT'] },
        { label: 'Holder Blocks', icon: 'gavel', route: '/compliance/holder-blocks', roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'] },
        { label: 'Token Admin Grants', icon: 'admin_panel_settings', route: '/compliance/token-admin-grants', roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'] },
        { label: 'CASP Register', icon: 'verified_user', route: '/compliance/casp-register', roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'] },
        { label: 'DORA', icon: 'security_update_warning', route: '/compliance/dora', roles: ['REGISTRY_ADMIN'] },
        { label: 'Access Reviews', icon: 'fact_check', route: '/compliance/access-reviews', roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'] },
        { label: 'Reporting', icon: 'assessment', route: '/compliance/reporting', roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'] },
        { label: 'DSAR Erasure', icon: 'person_off', route: '/compliance/dsar', roles: ['REGISTRY_ADMIN'] },
        { label: 'Support Tickets', icon: 'support_agent', route: '/compliance/support-tickets', roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT'] },
        { label: 'Audit Log', icon: 'receipt_long', route: '/audit', roles: ['REGISTRY_ADMIN', 'AUDIT'] },
      ],
    },
    {
      label: 'Ecosystem',
      roles: ['REGISTRY_ADMIN'],
      items: [
        { label: 'Organizations', icon: 'domain',        route: '/organizations' },
        { label: 'Permissions',   icon: 'verified_user', route: '/permissions' },
        { label: 'Payment Rails', icon: 'payments',      route: '/payment-rails' },
        { label: 'dApp Review',   icon: 'rate_review',   route: '/dapp-review' },
        { label: 'dApp Catalog',  icon: 'storefront',    route: '/dapp-catalog' },
      ],
    },
    {
      label: 'Infrastructure',
      roles: ['REGISTRY_ADMIN'],
      items: [
        { label: 'Wallets',       icon: 'wallet',    route: '/wallets' },
        { label: 'Network Nodes', icon: 'cable',     route: '/network-nodes' },
        { label: 'Endpoints',     icon: 'contacts',  route: '/endpoints' },
        { label: 'Users',         icon: 'group',     route: '/users' },
      ],
    },
    {
      // Matches BlockchainTransactionController's REGISTRY_ADMIN/AUDIT access exactly — a
      // separate section from Infrastructure (REGISTRY_ADMIN-only) rather than widening that
      // one, since Wallets/Network Nodes/Endpoints/Users are genuinely REGISTRY_ADMIN-specific.
      label: 'Operations',
      roles: ['REGISTRY_ADMIN', 'AUDIT'],
      items: [
        { label: 'Transactions', icon: 'receipt_long', route: '/transactions' },
      ],
    },
  ];

  get visibleSections(): NavSection[] {
    return this.navSections
      .filter((section) => !section.roles || section.roles.some((role) => this.auth.hasRole(role)))
      .map((section) => ({
        ...section,
        items: section.items.filter(
          (item) => !item.roles || item.roles.some((role) => this.auth.hasRole(role)),
        ),
      }))
      .filter((section) => section.items.length > 0);
  }
}
