import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** The prepared UserOperation a voucher is requested for. Integers are 0x-hex quantities. */
export interface GasSponsorshipVoucherRequest {
  deploymentId: string;
  sender: string;
  nonce: string;
  initCode: string;
  callData: string;
  callGasLimit: string;
  verificationGasLimit: string;
  preVerificationGas: string;
  maxFeePerGas: string;
  maxPriorityFeePerGas: string;
  paymasterVerificationGasLimit: string;
  paymasterPostOpGasLimit: string;
}

/** A signed `EwpgPaymaster` voucher — `paymasterData` goes into the UserOperation unchanged. */
export interface GasSponsorshipVoucher {
  paymaster: `0x${string}`;
  paymasterData: `0x${string}`;
  paymasterVerificationGasLimit: string;
  paymasterPostOpGasLimit: string;
  chainId: number;
  policyId: string;
  validUntil: number;
  validAfter: number;
  maxFeePerGasCap: string;
  maxCostWei: string;
}

/**
 * Backend voucher issuer for sponsored (ERC-4337) transactions — see
 * `asset/web/GasSponsorshipVoucherController.java` and docs/platform/account-abstraction.md.
 */
@Injectable({ providedIn: 'root' })
export class GasSponsorshipApiService {
  private readonly http = inject(HttpClient);

  requestVoucher(body: GasSponsorshipVoucherRequest): Observable<GasSponsorshipVoucher> {
    return this.http.post<GasSponsorshipVoucher>(`${environment.apiUrl}/gas-sponsorship/vouchers`, body);
  }
}
