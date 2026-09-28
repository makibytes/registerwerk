import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PageResponse } from '../models';
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';

/** Who instructed a change to a register entry (§18 eWpG). Recorded on every change (T3-13). */
export type InstructingParty = 'HOLDER' | 'BENEFICIARY' | 'COURT' | 'INSOLVENCY_ADMINISTRATOR' | 'ISSUER_TERMS_CHANGE';

export const INSTRUCTING_PARTIES: { value: InstructingParty; label: string }[] = [
  { value: 'HOLDER', label: 'Holder' },
  { value: 'BENEFICIARY', label: 'Beneficiary' },
  { value: 'COURT', label: 'Court' },
  { value: 'INSOLVENCY_ADMINISTRATOR', label: 'Insolvency administrator' },
  { value: 'ISSUER_TERMS_CHANGE', label: 'Issuer (change of terms)' },
];

export interface RegisterEntry {
  id: string;
  assetId: string;
  investorId: string;
  walletAddress: string;
  nominalAmount: number;
  entryType: 'COLLECTIVE' | 'INDIVIDUAL' | null;
  holderReference: string | null;
  isConsumer: boolean | null;
  thirdPartyRights: string | null;
  disposalRestrictions: string | null;
  legalCapacityNote: string | null;
}

export interface RegisterEntryInstruction {
  instructingParty: InstructingParty;
  instructionReference: string;
}

export interface AddRegisterEntryBody extends RegisterEntryInstruction {
  investorId: string;
  walletAddress: string;
  nominalAmount: number | null;
  singleEntry: boolean;
  isConsumer: boolean;
  thirdPartyRights?: string;
  disposalRestrictions?: string;
  legalCapacityNote?: string;
}

export interface AttributeChangeBody extends RegisterEntryInstruction {
  isConsumer?: boolean | null;
  thirdPartyRights?: string;
  disposalRestrictions?: string;
  legalCapacityNote?: string;
  /** Blank text means "no change"; removing a value needs the explicit flag. */
  clearThirdPartyRights: boolean;
  clearDisposalRestrictions: boolean;
}

export interface HolderChangeRequest {
  id: string;
  assetId: string;
  requestType: 'ADD_HOLDER' | 'UPDATE_ATTRIBUTES';
  holderId: string | null;
  payload: Record<string, unknown>;
  instructingParty: InstructingParty;
  instructionReference: string;
  status: 'REQUESTED' | 'EXECUTED' | 'REJECTED';
  requestedBy: string | null;
  requestedAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionReason: string | null;
}

/**
 * Operator-executed register-entry changes (T3-13): the operator creates/changes entries against a
 * recorded instruction; the issuer can only file requests, which the operator executes (4-eyes) or
 * rejects here.
 */
@Injectable({ providedIn: 'root' })
export class RegisterEntryService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/assets`;

  listEntries(assetId: string): Observable<PageResponse<RegisterEntry>> {
    const params = new HttpParams().set('page', 0).set('size', 200);
    return this.http.get<PageResponse<RegisterEntry>>(`${this.base}/${assetId}/holders`, { params });
  }

  /** Single step-up (`REGISTER_ENTRY_CREATE`). */
  addEntry(assetId: string, body: AddRegisterEntryBody, stepUpToken: string): Observable<RegisterEntry> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    const { singleEntry, ...rest } = body;
    const url = singleEntry ? `${this.base}/${assetId}/holders/single-entry` : `${this.base}/${assetId}/holders`;
    return this.http.post<RegisterEntry>(url, rest, { headers });
  }

  /** Step-up + second approver (`REGISTER_ENTRY_RIGHTS_CHANGE`). */
  updateAttributes(assetId: string, holderId: string, body: AttributeChangeBody,
                   tokens: DualControlTokens): Observable<RegisterEntry> {
    return this.http.patch<RegisterEntry>(`${this.base}/${assetId}/holders/${holderId}/single-entry-attributes`, body,
      { headers: dualControlHeaders(tokens) });
  }

  listRequests(assetId: string): Observable<HolderChangeRequest[]> {
    return this.http.get<HolderChangeRequest[]>(`${this.base}/${assetId}/holders/change-requests`);
  }

  /** Step-up + second approver (`REGISTER_ENTRY_RIGHTS_CHANGE`). */
  executeRequest(assetId: string, requestId: string, tokens: DualControlTokens): Observable<HolderChangeRequest> {
    return this.http.post<HolderChangeRequest>(
      `${this.base}/${assetId}/holders/change-requests/${requestId}/execute`, {}, { headers: dualControlHeaders(tokens) });
  }

  rejectRequest(assetId: string, requestId: string, reason: string): Observable<HolderChangeRequest> {
    return this.http.post<HolderChangeRequest>(
      `${this.base}/${assetId}/holders/change-requests/${requestId}/reject`, { reason });
  }
}
