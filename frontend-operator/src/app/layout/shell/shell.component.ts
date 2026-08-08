import { Component, HostListener, inject, signal } from '@angular/core';
import { BreakpointObserver } from '@angular/cdk/layout';
import { RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SidebarComponent } from '../sidebar/sidebar.component';
import { AuthService } from '../../core/auth/auth.service';
import { environment } from '../../../environments/environment';
import { map } from 'rxjs';
import { toSignal } from '@angular/core/rxjs-interop';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, MatButtonModule, MatIconModule, MatTooltipModule, SidebarComponent],
  styles: [`
    .shell {
      display: flex;
      height: 100vh;
      overflow: hidden;
    }

    .sidebar-col {
      width: 232px;
      min-width: 232px;
      background: var(--rw-sidebar-bg);
      display: flex;
      flex-direction: column;
      overflow-y: auto;
      overflow-x: hidden;
      border-right: 1px solid var(--rw-sidebar-border);
      z-index: 20;
    }

    .main {
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    .topbar {
      display: flex;
      align-items: center;
      height: 54px;
      padding: 0 20px;
      background: var(--rw-toolbar-bg);
      border-bottom: 1px solid var(--rw-toolbar-border);
      flex-shrink: 0;
      gap: 12px;
    }

    .topbar-title {
      font-size: 14px;
      font-weight: 600;
      color: var(--rw-text-secondary);
      letter-spacing: 0.1px;
    }

    .spacer { flex: 1; }

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
    }

    .logout-btn {
      color: var(--rw-text-muted) !important;

      &:hover { color: var(--rw-text-primary) !important; }
    }

    .content {
      flex: 1;
      min-width: 0;
      overflow-y: auto;
      padding: 28px 28px;
      background: var(--rw-bg);
    }

    .menu-btn,
    .sidebar-backdrop {
      display: none;
    }

    .skip-link {
      position: fixed;
      top: 8px;
      left: 8px;
      z-index: 100;
      padding: 8px 12px;
      border-radius: var(--rw-radius);
      background: var(--rw-surface);
      color: var(--rw-text-primary);
      box-shadow: var(--rw-shadow-md);
      transform: translateY(-150%);
      transition: transform 120ms ease;

      &:focus { transform: translateY(0); }
    }

    @media (max-width: 900px) {
      .sidebar-col {
        position: fixed;
        inset: 0 auto 0 0;
        transform: translateX(-100%);
        transition: transform 180ms ease;
        box-shadow: 12px 0 32px rgba(0, 0, 0, 0.28);

        &.open { transform: translateX(0); }
      }

      .menu-btn { display: inline-flex; }

      .sidebar-backdrop {
        position: fixed;
        inset: 0;
        z-index: 10;
        display: block;
        padding: 0;
        border: 0;
        background: rgba(7, 9, 26, 0.55);
        cursor: default;
      }

      .content { padding: 22px 20px; }
    }

    @media (max-width: 520px) {
      .topbar { padding: 0 10px; }
      .topbar-title { font-size: 13px; }
      .content { padding: 18px 14px; }
    }
  `],
  template: `
    <a class="skip-link" href="#main-content">Skip to main content</a>
    <div class="shell">
      <aside
        id="operator-navigation"
        class="sidebar-col"
        [class.open]="sidebarOpen()"
        [attr.inert]="isCompact() && !sidebarOpen() ? '' : null"
      >
        <app-sidebar (navigated)="closeSidebar()" />
      </aside>

      @if (sidebarOpen()) {
        <button class="sidebar-backdrop" type="button" aria-label="Close navigation" (click)="closeSidebar()"></button>
      }

      <div class="main">
        <header class="topbar">
          <button
            class="menu-btn"
            mat-icon-button
            type="button"
            aria-label="Open navigation"
            aria-controls="operator-navigation"
            [attr.aria-expanded]="sidebarOpen()"
            (click)="openSidebar()"
          >
            <mat-icon>menu</mat-icon>
          </button>
          <span class="topbar-title">Operator Administration</span>
          @if (isTestEnv) {
            <span class="env-badge">Test</span>
          }
          <span class="spacer"></span>
          <button
            class="logout-btn"
            mat-icon-button
            type="button"
            aria-label="Sign out"
            (click)="logout()"
            matTooltip="Sign out"
            matTooltipPosition="below"
          >
            <mat-icon>logout</mat-icon>
          </button>
        </header>

        <main id="main-content" class="content" tabindex="-1">
          <router-outlet />
        </main>
      </div>
    </div>
  `,
})
export class ShellComponent {
  readonly isTestEnv = environment.testEnvironment;
  readonly sidebarOpen = signal(false);
  private readonly authService = inject(AuthService);
  private readonly breakpointObserver = inject(BreakpointObserver);
  readonly isCompact = toSignal(
    this.breakpointObserver.observe('(max-width: 900px)').pipe(map((state) => state.matches)),
    { initialValue: false },
  );

  openSidebar(): void { this.sidebarOpen.set(true); }
  closeSidebar(): void { this.sidebarOpen.set(false); }
  logout(): void { this.authService.logout(); }

  @HostListener('document:keydown.escape')
  closeSidebarOnEscape(): void {
    if (this.isCompact()) this.closeSidebar();
  }
}
