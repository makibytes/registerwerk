import { Component, OnInit, inject } from '@angular/core';
import { Router } from '@angular/router';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-handoff',
  standalone: true,
  imports: [MatProgressSpinnerModule],
  styles: [`
    .page {
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      background: #0F1A2E;
      padding: 24px;
    }

    .notice {
      max-width: 460px;
      text-align: center;
      color: #E5E7EB;
      font-family: 'Manrope Variable', sans-serif;

      h1 {
        font-size: 20px;
        font-weight: 700;
        margin: 0 0 12px;
      }

      p {
        font-size: 14px;
        line-height: 1.6;
        color: #9CA3AF;
        margin: 0;
      }


      button {
        margin-top: 20px;
        border: 1px solid rgba(45,212,191,0.35);
        border-radius: 8px;
        padding: 9px 14px;
        background: rgba(45,212,191,0.12);
        color: #5EEAD4;
        cursor: pointer;
        font: inherit;
        font-size: 13px;
        font-weight: 700;
      }
    }
  `],
  template: `
    <div class="page">
      @if (unsupported) {
        <div class="notice">
          <h1>Impersonation is unavailable</h1>
          <p>
            This portal signs in through Microsoft Entra ID, which issues the session directly to
            each user. Registerwerk cannot act on a customer's behalf in this mode. Ask the
            customer to sign in themselves, or use the operator portal's read-only views.
          </p>
          <button type="button" (click)="goToLogin()">Return to sign in</button>
        </div>
      } @else if (failed) {
        <div class="notice" role="alert">
          <h1>Impersonation could not be started</h1>
          <p>The handoff link is invalid, expired, or has already been used. Return to the operator portal and start a new impersonation session.</p>
          <button type="button" (click)="goToLogin()">Return to sign in</button>
        </div>
      } @else {
        <div class="notice" role="status">
          <mat-spinner diameter="40" />
          <p>Starting the secure customer session…</p>
        </div>
      }
    </div>
  `,
})
export class HandoffComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /**
   * Shown instead of silently bouncing to /login. The backend already refuses to mint an
   * impersonation token when ENTRA_ENABLED=true, so an operator arriving here has followed a
   * link that cannot work — saying why beats an unexplained redirect.
   */
  unsupported = false;
  failed = false;

  ngOnInit(): void {
    const fragment = window.location.hash.slice(1);
    const params = new URLSearchParams(fragment);
    const token = params.get('token');
    const entityId = params.get('entityId');
    const entityName = params.get('entityName') ?? '';

    // Remove secrets and metadata before doing any asynchronous work. URLSearchParams already
    // decodes values; a second decodeURIComponent call could throw for legitimate '%' names.
    history.replaceState(null, '', window.location.pathname);

    if (!this.auth.supportsImpersonation()) {
      this.unsupported = true;
      return;
    }

    if (token && entityId) {
      this.auth.enterImpersonation(token, entityId, entityName).subscribe({
        next: () => this.router.navigate(['/dashboard']),
        error: () => { this.failed = true; },
      });
    } else {
      this.failed = true;
    }
  }

  goToLogin(): void {
    this.router.navigate(['/login']);
  }
}
