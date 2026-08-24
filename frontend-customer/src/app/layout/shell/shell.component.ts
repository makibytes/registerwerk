import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { NavComponent } from '../nav/nav.component';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, NavComponent],
  template: `
    <a class="skip-link" href="#main-content">Skip to main content</a>
    <app-nav></app-nav>
    <main id="main-content" class="shell-content" tabindex="-1">
      <router-outlet></router-outlet>
    </main>
  `,
  styles: [`
    :host {
      display: flex;
      flex-direction: column;
      min-height: 100vh;
    }
    .shell-content {
      flex: 1;
      background: var(--rw-bg);
    }
    .skip-link {
      position: fixed;
      left: 16px;
      top: 12px;
      z-index: 1000;
      padding: 8px 12px;
      border-radius: var(--rw-radius);
      background: var(--rw-accent);
      color: var(--rw-accent-contrast);
      font-weight: 700;
      text-decoration: none;
      transform: translateY(-200%);
    }
    .skip-link:focus { transform: translateY(0); }
  `]
})
export class ShellComponent {}
