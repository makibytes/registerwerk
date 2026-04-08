import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { KycDocument } from '../models';

@Injectable({ providedIn: 'root' })
export class KycService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/entities`;

  listDocuments(entityId: string): Observable<KycDocument[]> {
    return this.http.get<KycDocument[]>(`${this.base}/${entityId}/kyc/documents`);
  }

  uploadDocument(
    entityId: string,
    file: File,
    documentType: string
  ): Observable<KycDocument> {
    const formData = new FormData();
    formData.append('file', file, file.name);
    formData.append('documentType', documentType);
    return this.http.post<KycDocument>(
      `${this.base}/${entityId}/kyc/documents`,
      formData
    );
  }

  downloadDocument(entityId: string, docId: string): Observable<Blob> {
    return this.http.get(
      `${this.base}/${entityId}/kyc/documents/${docId}/download`,
      { responseType: 'blob' }
    );
  }

  deleteDocument(entityId: string, docId: string): Observable<void> {
    return this.http.delete<void>(
      `${this.base}/${entityId}/kyc/documents/${docId}`
    );
  }

  approveKyc(entityId: string, expiryDate: string): Observable<unknown> {
    return this.http.post(`${this.base}/${entityId}/kyc/approve`, {
      expiryDate,
    });
  }

  rejectKyc(entityId: string, reason: string): Observable<unknown> {
    return this.http.post(`${this.base}/${entityId}/kyc/reject`, { reason });
  }
}
