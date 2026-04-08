import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { OnboardingToken } from '../models';

@Injectable({ providedIn: 'root' })
export class OnboardingService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/onboarding`;

  generateToken(entityId: string): Observable<OnboardingToken> {
    return this.http.post<OnboardingToken>(`${this.base}/tokens`, { entityId });
  }
}
