import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';
import { environment } from '../../../environments/environment';

export type TravelRulePeerStatus = 'ACTIVE' | 'DISABLED';

export interface TravelRulePeer {
  vaspId: string;
  legalName: string | null;
  lei: string | null;
  status: TravelRulePeerStatus | string;
  createdAt: string;
}

/** The HMAC key is in this response once and is never readable again. */
export interface CreatedTravelRulePeer {
  peer: TravelRulePeer;
  hmacKey: string;
}

export interface RegisterPeerRequest {
  vaspId: string;
  legalName?: string;
  lei?: string;
}

/**
 * Operator side of the Travel Rule inbox's peer credentials. Register, disable and enable are
 * step-up + second approver; re-registering an existing peer rotates its key but never reactivates
 * it (the explicit `enable` is the only way back).
 */
@Injectable({ providedIn: 'root' })
export class TravelRulePeerService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/compliance/travel-rule/peers`;

  list(): Observable<TravelRulePeer[]> {
    return this.http.get<TravelRulePeer[]>(this.base);
  }

  /** `TRAVEL_RULE_PEER_REGISTER`: registers a peer or rotates its key. */
  register(body: RegisterPeerRequest, tokens: DualControlTokens): Observable<CreatedTravelRulePeer> {
    return this.http.post<CreatedTravelRulePeer>(this.base, body, { headers: dualControlHeaders(tokens) });
  }

  /** `TRAVEL_RULE_PEER_DISABLE`. */
  disable(vaspId: string, tokens: DualControlTokens): Observable<void> {
    return this.http.delete<void>(peerUrl(this.base, vaspId), { headers: dualControlHeaders(tokens) });
  }

  /** `TRAVEL_RULE_PEER_ENABLE`: explicit re-activation of a DISABLED peer. */
  enable(vaspId: string, tokens: DualControlTokens): Observable<void> {
    return this.http.post<void>(`${peerUrl(this.base, vaspId)}/enable`, {}, { headers: dualControlHeaders(tokens) });
  }
}

/** The VASP id (often `did:...`) is one path segment; the approval target must use the same encoding. */
export function peerPath(vaspId: string): string {
  return `/api/v1/compliance/travel-rule/peers/${encodeURIComponent(vaspId)}`;
}

function peerUrl(base: string, vaspId: string): string {
  return `${base}/${encodeURIComponent(vaspId)}`;
}
