import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface DemoOnchainManifest {
  schemaVersion: number;
  network: string;
  contracts: Record<string, string>;
}

@Injectable({ providedIn: 'root' })
export class DemoOnchainService {
  private readonly http = inject(HttpClient);
  getManifest(): Observable<DemoOnchainManifest> {
    return this.http.get<DemoOnchainManifest>(`${environment.apiUrl}/demo/onchain`);
  }
}
