import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SidebarComponent } from '../sidebar/sidebar.component';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [
    RouterOutlet,
    MatToolbarModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    SidebarComponent,
  ],
  styles: [`
    .shell-container {
      display: flex;
      height: 100vh;
      overflow: hidden;
    }

    .sidebar {
      width: 220px;
      min-width: 220px;
      background: #3f51b5;
      display: flex;
      flex-direction: column;
      overflow-y: auto;
    }

    .main-area {
      flex: 1;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    mat-toolbar {
      background: white;
      border-bottom: 1px solid #e0e0e0;
      min-height: 56px;
      padding: 0 16px;
      flex-shrink: 0;
      color: rgba(0, 0, 0, 0.87);
      z-index: 1;
    }

    .toolbar-title {
      font-size: 16px;
      font-weight: 500;
    }

    .spacer {
      flex: 1 1 auto;
    }

    .content-area {
      flex: 1;
      overflow-y: auto;
      padding: 24px;
      background: #f5f5f5;
    }
  `],
  template: `
    <div class="shell-container">
      <nav class="sidebar">
        <app-sidebar />
      </nav>

      <div class="main-area">
        <mat-toolbar>
          <span class="toolbar-title">Registerwerk — Operator</span>
          <span class="spacer"></span>
          <button
            mat-icon-button
            (click)="logout()"
            matTooltip="Logout"
          >
            <mat-icon>logout</mat-icon>
          </button>
        </mat-toolbar>

        <main class="content-area">
          <router-outlet />
        </main>
      </div>
    </div>
  `,
})
export class ShellComponent {
  constructor(private readonly authService: AuthService) {}

  logout(): void {
    this.authService.logout();
  }
}
