import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SidebarComponent } from '../sidebar/sidebar.component';
import { AuthService } from '../../core/auth/auth.service';

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
  constructor(private readonly authService: AuthService) {}
  logout(): void { this.authService.logout(); }
}
