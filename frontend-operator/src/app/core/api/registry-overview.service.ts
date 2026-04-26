import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { RegistryOverview } from '../models';

@Injectable({ providedIn: 'root' })
export class RegistryOverviewService {
  private readonly http = inject(HttpClient);

  getOverview(): Observable<RegistryOverview> {
    return this.http.get<RegistryOverview>(`${environment.apiUrl}/entities/overview`);
  }
}
