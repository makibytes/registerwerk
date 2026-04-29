import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ChainHealth, RpcNode } from '../models';

@Injectable({ providedIn: 'root' })
export class ChainService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/admin/chains`;

  getHealth(): Observable<ChainHealth[]> {
    return this.http.get<ChainHealth[]>(`${this.base}/health`);
  }

  getNodes(chainId: string): Observable<RpcNode[]> {
    return this.http.get<RpcNode[]>(`${this.base}/${chainId}/nodes`);
  }

  addNode(chainId: string, url: string, label: string): Observable<RpcNode> {
    return this.http.post<RpcNode>(`${this.base}/${chainId}/nodes`, { url, label });
  }

  enableNode(chainId: string, nodeId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/${chainId}/nodes/${nodeId}/enable`, {});
  }

  disableNode(chainId: string, nodeId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/${chainId}/nodes/${nodeId}/disable`, {});
  }

  setExclusive(chainId: string, nodeId: string, value: boolean): Observable<void> {
    return this.http.post<void>(`${this.base}/${chainId}/nodes/${nodeId}/exclusive?value=${value}`, {});
  }

  deleteNode(chainId: string, nodeId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${chainId}/nodes/${nodeId}`);
  }
}
