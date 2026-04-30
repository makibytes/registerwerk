import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  Endpoint,
  EndpointCreateRequest,
  EndpointUpdateRequest,
  AddressResolveResponse,
} from '@registerwerk/ui';

@Injectable({ providedIn: 'root' })
export class EndpointService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/endpoints`;

  listEndpoints(): Observable<Endpoint[]> {
    return this.http.get<Endpoint[]>(this.base);
  }

  createEndpoint(request: EndpointCreateRequest): Observable<Endpoint> {
    return this.http.post<Endpoint>(this.base, request);
  }

  updateEndpoint(id: string, request: EndpointUpdateRequest): Observable<Endpoint> {
    return this.http.patch<Endpoint>(`${this.base}/${id}`, request);
  }

  deleteEndpoint(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  resolveAddresses(addresses: string[]): Observable<AddressResolveResponse> {
    return this.http.post<AddressResolveResponse>(`${this.base}/resolve`, { addresses });
  }
}
