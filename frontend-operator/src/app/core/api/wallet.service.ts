import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { OperatorWallet, WalletDefault } from '../models';

@Injectable({ providedIn: 'root' })
export class WalletService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/admin/wallets`;
  private readonly defaultsBase = `${environment.apiUrl}/admin/wallet-defaults`;

  listWallets(): Observable<OperatorWallet[]> {
    return this.http.get<OperatorWallet[]>(this.base);
  }

  generate(name: string, type: 'EVM' | 'SOLANA'): Observable<OperatorWallet> {
    return this.http.post<OperatorWallet>(`${this.base}/generate`, { name, type });
  }

  importRaw(name: string, type: 'EVM' | 'SOLANA', privateKey: string): Observable<OperatorWallet> {
    return this.http.post<OperatorWallet>(`${this.base}/import-raw`, { name, type, privateKey });
  }

  importKeystore(name: string, password: string, file: File): Observable<OperatorWallet> {
    const form = new FormData();
    form.append('name', name);
    form.append('password', password);
    form.append('file', file, file.name);
    return this.http.post<OperatorWallet>(`${this.base}/import-keystore`, form);
  }

  exportKeystore(walletId: string, password: string): Observable<Blob> {
    return this.http.post(`${this.base}/${walletId}/export-keystore`, { password }, { responseType: 'blob' });
  }

  exportRaw(walletId: string): Observable<string> {
    return this.http.post(`${this.base}/${walletId}/export-raw?confirm=true`, {}, { responseType: 'text' });
  }

  rename(walletId: string, name: string): Observable<OperatorWallet> {
    return this.http.patch<OperatorWallet>(`${this.base}/${walletId}`, { name });
  }

  delete(walletId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${walletId}`);
  }

  listDefaults(): Observable<WalletDefault[]> {
    return this.http.get<WalletDefault[]>(this.defaultsBase);
  }

  setDefault(chainId: string, walletId: string): Observable<WalletDefault> {
    return this.http.put<WalletDefault>(`${this.defaultsBase}/${chainId}`, { walletId });
  }
}
