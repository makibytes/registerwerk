import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';
import { OperatorWallet, WalletBalance, WalletDefault } from '../models';

@Injectable({ providedIn: 'root' })
export class WalletService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/admin/wallets`;
  private readonly defaultsBase = `${environment.apiUrl}/admin/wallet-defaults`;

  listWallets(): Observable<OperatorWallet[]> {
    return this.http.get<OperatorWallet[]>(this.base);
  }

  /** Every signer-lifecycle call needs step-up + a second approver (P4C-5). */
  generate(name: string, type: 'EVM' | 'SOLANA' | 'CANTON', tokens: DualControlTokens): Observable<OperatorWallet> {
    return this.http.post<OperatorWallet>(`${this.base}/generate`, { name, type }, { headers: dualControlHeaders(tokens) });
  }

  importRaw(name: string, type: 'EVM' | 'SOLANA' | 'CANTON', privateKey: string, tokens: DualControlTokens, partyId?: string, jwt?: string): Observable<OperatorWallet> {
    const body: Record<string, string> = { name, type, privateKey };
    if (partyId) body['partyId'] = partyId;
    if (jwt) body['jwt'] = jwt;
    return this.http.post<OperatorWallet>(`${this.base}/import-raw`, body, { headers: dualControlHeaders(tokens) });
  }

  importKeystore(name: string, password: string, file: File, tokens: DualControlTokens): Observable<OperatorWallet> {
    const form = new FormData();
    form.append('name', name);
    form.append('password', password);
    form.append('file', file, file.name);
    return this.http.post<OperatorWallet>(`${this.base}/import-keystore`, form, { headers: dualControlHeaders(tokens) });
  }

  attachHsm(name: string, keyAlias: string, address: string, tokens: DualControlTokens): Observable<OperatorWallet> {
    return this.http.post<OperatorWallet>(`${this.base}/attach-hsm`, { name, keyAlias, address }, { headers: dualControlHeaders(tokens) });
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

  /** Soft delete (P4C-5): refused (409) for a chain default or a key that ever signed on chain. */
  delete(walletId: string, tokens: DualControlTokens): Observable<void> {
    return this.http.delete<void>(`${this.base}/${walletId}`, { headers: dualControlHeaders(tokens) });
  }

  /** Reverses a soft delete inside the retention window. */
  restore(walletId: string, tokens: DualControlTokens): Observable<OperatorWallet> {
    return this.http.post<OperatorWallet>(`${this.base}/${walletId}/restore`, {}, { headers: dualControlHeaders(tokens) });
  }

  getById(walletId: string): Observable<OperatorWallet> {
    return this.http.get<OperatorWallet>(`${this.base}/${walletId}`);
  }

  getBalances(walletId: string): Observable<WalletBalance[]> {
    return this.http.get<WalletBalance[]>(`${this.base}/${walletId}/balances`);
  }

  listDefaults(): Observable<WalletDefault[]> {
    return this.http.get<WalletDefault[]>(this.defaultsBase);
  }

  setDefault(chainId: string, walletId: string, tokens: DualControlTokens): Observable<WalletDefault> {
    return this.http.put<WalletDefault>(`${this.defaultsBase}/${chainId}`, { walletId }, { headers: dualControlHeaders(tokens) });
  }
}
