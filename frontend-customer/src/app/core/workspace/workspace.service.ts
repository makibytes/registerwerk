import { Injectable, inject, signal } from '@angular/core';
import { AuthService } from '../auth/auth.service';
import { environment } from '../../../environments/environment';

export type WorkspaceKey = 'INVESTOR' | 'TRADER' | 'ISSUER';

export interface WorkspaceNavLink {
  label: string;
  route: string;
  icon: string;
}

export interface WorkspaceDef {
  key: WorkspaceKey;
  label: string;
  icon: string;
  links: WorkspaceNavLink[];
}

const STORAGE_KEY = 'rw.workspace';

/** Priority order used when no workspace is stored yet and more than one is eligible. */
const DEFAULT_PRIORITY: WorkspaceKey[] = ['TRADER', 'INVESTOR', 'ISSUER'];

const WORKSPACES: Record<WorkspaceKey, WorkspaceDef> = {
  TRADER: {
    key: 'TRADER',
    label: 'Trader',
    icon: 'candlestick_chart',
    links: [
      { label: 'Dashboard', route: '/dashboard', icon: 'grid_view' },
      ...(environment.lendingEnabled
        ? [{ label: 'Liquidity', route: '/lending', icon: 'water_drop' }]
        : []),
      { label: 'Trading Desk', route: '/trading', icon: 'candlestick_chart' },
      { label: 'My Positions', route: '/positions', icon: 'account_balance_wallet' },
      { label: 'Marketplace', route: '/marketplace', icon: 'storefront' },
    ],
  },
  INVESTOR: {
    key: 'INVESTOR',
    label: 'Investor',
    icon: 'savings',
    links: [
      { label: 'Dashboard', route: '/dashboard', icon: 'grid_view' },
      { label: 'My Positions', route: '/positions', icon: 'account_balance_wallet' },
      { label: 'Investments', route: '/investments', icon: 'savings' },
      { label: 'My Orders', route: '/investments/orders', icon: 'receipt_long' },
      { label: 'Marketplace', route: '/marketplace', icon: 'storefront' },
    ],
  },
  ISSUER: {
    key: 'ISSUER',
    label: 'Issuer',
    icon: 'description',
    links: [
      { label: 'Dashboard', route: '/dashboard', icon: 'grid_view' },
      { label: 'Issuances', route: '/issuances', icon: 'description' },
      { label: 'My dApps', route: '/publisher', icon: 'widgets' },
      { label: 'Company Admin', route: '/company-admin', icon: 'manage_accounts' },
      { label: 'Marketplace', route: '/marketplace', icon: 'storefront' },
    ],
  },
};

/** Which roles make a given workspace eligible; REGISTRY_ADMIN (impersonation) sees all three. */
const ELIGIBILITY: Record<WorkspaceKey, string[]> = {
  TRADER: ['TRADER', 'REGISTRY_ADMIN'],
  INVESTOR: ['INVESTOR', 'REGISTRY_ADMIN'],
  ISSUER: ['ISSUER', 'COMPANY_ADMIN', 'DAPP_PUBLISHER', 'REGISTRY_ADMIN'],
};

/**
 * Drives the top-bar workspace switcher (Investor / Trader / Issuer) that replaced the flat,
 * role-filtered link list in `layout/nav/nav.component.ts` — each workspace surfaces only the
 * tools relevant to that job, instead of every feature the account happens to hold a role for
 * appearing in one undifferentiated bar. `Trading` and `Issuances` etc. still exist as the same
 * routes/components as before; this only changes how they're grouped and discovered.
 */
@Injectable({ providedIn: 'root' })
export class WorkspaceService {
  private readonly auth = inject(AuthService);

  private readonly _current = signal<WorkspaceKey | null>(null);
  readonly current = this._current.asReadonly();

  /** Workspaces the logged-in user's roles make eligible, in the fixed display order. */
  eligibleWorkspaces(): WorkspaceDef[] {
    return DEFAULT_PRIORITY
      .filter((key) => ELIGIBILITY[key].some((role) => this.auth.hasRole(role)))
      .map((key) => WORKSPACES[key]);
  }

  /** The active workspace definition, initializing from storage or the eligible default. */
  activeWorkspace(): WorkspaceDef {
    const key = this.resolveActiveKey();
    return WORKSPACES[key];
  }

  setWorkspace(key: WorkspaceKey): void {
    this._current.set(key);
    try {
      localStorage.setItem(STORAGE_KEY, key);
    } catch {
      // Storage can be unavailable (private browsing, quota) — the in-memory signal still works
      // for the rest of this session, it just won't be remembered on next login.
    }
  }

  private resolveActiveKey(): WorkspaceKey {
    const inMemory = this._current();
    if (inMemory && this.isEligible(inMemory)) return inMemory;

    const stored = this.readStored();
    if (stored && this.isEligible(stored)) {
      this._current.set(stored);
      return stored;
    }

    const eligible = this.eligibleWorkspaces();
    const fallback = eligible[0]?.key ?? 'INVESTOR';
    this._current.set(fallback);
    return fallback;
  }

  private isEligible(key: WorkspaceKey): boolean {
    return ELIGIBILITY[key].some((role) => this.auth.hasRole(role));
  }

  private readStored(): WorkspaceKey | null {
    try {
      const value = localStorage.getItem(STORAGE_KEY);
      return value === 'INVESTOR' || value === 'TRADER' || value === 'ISSUER' ? value : null;
    } catch {
      return null;
    }
  }
}
