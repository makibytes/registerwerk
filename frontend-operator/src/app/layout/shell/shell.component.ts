import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SidebarComponent } from '../sidebar/sidebar.component';
import { AuthService } from '../../core/auth/auth.service';
import { environment } from '../../../environments/environment';

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
    }

    .main {
      flex: 1;
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
      overflow-y: auto;
      padding: 28px 28px;
      background: var(--rw-bg);
    }
  `],
  template: `
    <div class="shell">
      <aside class="sidebar-col">
        <app-sidebar />
      </aside>

      <div class="main">
        <header class="topbar">
          <span class="topbar-title">Operator Administration</span>
          @if (isTestEnv) {
            <span class="env-badge">Test</span>
          }
          <span class="spacer"></span>
          <button
            class="logout-btn"
            mat-icon-button
            (click)="logout()"
            matTooltip="Sign out"
            matTooltipPosition="below"
          >
            <mat-icon>logout</mat-icon>
          </button>
        </header>

        <main class="content">
          <router-outlet />
        </main>
      </div>
    </div>
  `,
})
export class ShellComponent {
  readonly isTestEnv = environment.testEnvironment;
  constructor(private readonly authService: AuthService) {}
  logout(): void { this.authService.logout(); }
}
