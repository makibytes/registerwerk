import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';

interface NavItem {
  label: string;
  icon: string;
  route: string;
}

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, MatListModule, MatIconModule],
  styles: [`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
    }

    .brand {
      padding: 20px 16px 16px;
      border-bottom: 1px solid rgba(255, 255, 255, 0.12);

      .brand-title {
        font-size: 16px;
        font-weight: 600;
        color: white;
        margin: 0;
        white-space: nowrap;
      }

      .brand-subtitle {
        font-size: 11px;
        color: rgba(255, 255, 255, 0.6);
        margin: 2px 0 0;
        white-space: nowrap;
      }
    }

    mat-nav-list {
      padding-top: 8px;
    }

    a[mat-list-item] {
      color: rgba(255, 255, 255, 0.8);
      border-radius: 4px;
      margin: 2px 8px;

      &:hover {
        background: rgba(255, 255, 255, 0.08);
        color: white;
      }

      &.active-link {
        background: rgba(255, 255, 255, 0.15);
        color: white;

        mat-icon {
          color: #ffd740;
        }
      }

      mat-icon {
        color: rgba(255, 255, 255, 0.6);
        margin-right: 12px;
      }

      span {
        font-size: 14px;
      }
    }
  `],
  template: `
    <div class="brand">
      <p class="brand-title">Registerwerk</p>
      <p class="brand-subtitle">Operator Portal</p>
    </div>

    <mat-nav-list>
      @for (item of navItems; track item.route) {
        <a
          mat-list-item
          [routerLink]="item.route"
          routerLinkActive="active-link"
          [routerLinkActiveOptions]="{ exact: item.route === '/dashboard' }"
        >
          <mat-icon matListItemIcon>{{ item.icon }}</mat-icon>
          <span matListItemTitle>{{ item.label }}</span>
        </a>
      }
    </mat-nav-list>
  `,
})
export class SidebarComponent {
  readonly navItems: NavItem[] = [
    { label: 'Dashboard', icon: 'dashboard', route: '/dashboard' },
    { label: 'Customers', icon: 'people', route: '/customers' },
    { label: 'Onboarding', icon: 'person_add', route: '/onboarding' },
    { label: 'Assets', icon: 'account_balance_wallet', route: '/assets' },
    { label: 'Audit Log', icon: 'history', route: '/audit' },
  ];
}
