import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { RegisterDocumentMeta } from '../models';

/**
 * Self-service register-document downloads: a § 19 eWpG Registerauszug for
 * individually entered holdings, or a jurisdiction-labeled holding confirmation for
 * collectively held (nominee) positions — never the same document, see
 * `RegisterDocumentMeta.statutory`. Wraps the customer-facing endpoints added
 * alongside the operator-only `registerstatement` module
 * (`MeRegisterDocumentController`, `MyAssetRegisterExtractController`).
 */
@Injectable({ providedIn: 'root' })
export class RegisterDocumentService {
  private readonly http = inject(HttpClient);

  /** Metadata for every register document available to the logged-in investor. */
  listAvailable(): Observable<RegisterDocumentMeta[]> {
    return this.http.get<RegisterDocumentMeta[]>(`${environment.apiUrl}/me/register-documents`);
  }

  /** Downloads the caller's own register document (Registerauszug / holding confirmation) for one asset. */
  download(assetId: string): Observable<Blob> {
    return this.http.get(`${environment.apiUrl}/me/register-documents/${assetId}`, { responseType: 'blob' });
  }

  /** Downloads the § 10 eWpG register extract for an asset the caller issued. */
  downloadIssuerRegisterExtract(assetId: string): Observable<Blob> {
    return this.http.get(`${environment.apiUrl}/me/assets/${assetId}/register-extract`, { responseType: 'blob' });
  }
}
