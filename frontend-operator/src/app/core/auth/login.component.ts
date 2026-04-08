import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from './auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [MatButtonModule, MatCardModule, MatIconModule],
  styles: [`
    .login-container {
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      background: linear-gradient(135deg, #3f51b5 0%, #1a237e 100%);
    }

    mat-card {
      width: 400px;
      padding: 40px 32px;
      text-align: center;
    }

    .logo-area {
      margin-bottom: 32px;

      mat-icon {
        font-size: 48px;
        width: 48px;
        height: 48px;
        color: #3f51b5;
        margin-bottom: 16px;
      }

      h1 {
        font-size: 22px;
        font-weight: 500;
        margin: 0 0 4px;
        color: rgba(0, 0, 0, 0.87);
      }

      p {
        margin: 0;
        color: rgba(0, 0, 0, 0.54);
        font-size: 14px;
      }
    }

    .login-btn {
      width: 100%;
      padding: 12px;
      font-size: 15px;
      margin-top: 8px;
    }

    .ms-icon {
      width: 20px;
      height: 20px;
      margin-right: 8px;
      vertical-align: middle;
    }
  `],
  template: `
    <div class="login-container">
      <mat-card>
        <div class="logo-area">
          <mat-icon>account_balance</mat-icon>
          <h1>Registerwerk</h1>
          <p>Operator Administration Portal</p>
        </div>

        <mat-card-content>
          <button
            mat-raised-button
            color="primary"
            class="login-btn"
            (click)="login()"
          >
            <svg class="ms-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 23 23">
              <path fill="#f3f3f3" d="M0 0h23v23H0z"/>
              <path fill="#f35325" d="M1 1h10v10H1z"/>
              <path fill="#81bc06" d="M12 1h10v10H12z"/>
              <path fill="#05a6f0" d="M1 12h10v10H1z"/>
              <path fill="#ffba08" d="M12 12h10v10H12z"/>
            </svg>
            Login with Microsoft
          </button>
        </mat-card-content>

        <mat-card-footer style="padding: 16px 0 0; color: rgba(0,0,0,0.38); font-size: 12px;">
          Restricted access — authorized personnel only
        </mat-card-footer>
      </mat-card>
    </div>
  `,
})
export class LoginComponent {
  constructor(private readonly authService: AuthService) {}

  login(): void {
    this.authService.login();
  }
}
