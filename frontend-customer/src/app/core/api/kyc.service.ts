import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { KycDocument } from '../models';

@Injectable({ providedIn: 'root' })
export class KycService {
  private readonly http = inject(HttpClient);

  private docsUrl(entityId: string): string {
    return `${environment.apiUrl}/customers/${entityId}/kyc/documents`;
  }

  listDocuments(entityId: string): Observable<KycDocument[]> {
    return this.http.get<KycDocument[]>(this.docsUrl(entityId));
  }

  uploadDocument(entityId: string, file: File, documentType: string): Observable<KycDocument> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('documentType', documentType);
    return this.http.post<KycDocument>(this.docsUrl(entityId), formData);
  }

  downloadDocument(entityId: string, docId: string): Observable<Blob> {
    return this.http.get(`${this.docsUrl(entityId)}/${docId}`, { responseType: 'blob' });
  }

  deleteDocument(entityId: string, docId: string): Observable<void> {
    return this.http.delete<void>(`${this.docsUrl(entityId)}/${docId}`);
  }
}
