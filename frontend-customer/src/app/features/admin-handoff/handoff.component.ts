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
    }
  `],
  template: `
    <div class="page">
      <mat-spinner diameter="40" />
    </div>
  `,
})
export class HandoffComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  ngOnInit(): void {
    const fragment = window.location.hash.slice(1);
    const params = new URLSearchParams(fragment);
    const token = params.get('token');
    const entityId = params.get('entityId');
    const entityName = decodeURIComponent(params.get('entityName') ?? '');

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
