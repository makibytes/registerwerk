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
      font-family: 'Manrope', sans-serif;

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
        </div>
      } @else {
        <mat-spinner diameter="40" />
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

  ngOnInit(): void {
    const fragment = window.location.hash.slice(1);
    const params = new URLSearchParams(fragment);
    const token = params.get('token');
    const entityId = params.get('entityId');
    const entityName = decodeURIComponent(params.get('entityName') ?? '');

    if (!this.auth.supportsImpersonation()) {
      // Drop the fragment regardless, so a token never lingers in the address bar or history.
      history.replaceState(null, '', window.location.pathname);
      this.unsupported = true;
      return;
    }

    if (token && entityId) {
      this.auth.enterImpersonation(token, entityId, entityName);
      // Clear the fragment so the token isn't in the URL bar
      history.replaceState(null, '', window.location.pathname);
      this.router.navigate(['/dashboard']);
    } else {
      this.router.navigate(['/login']);
    }
  }
}
