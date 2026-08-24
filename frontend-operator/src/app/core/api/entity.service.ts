import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  LegalEntity,
  LegalEntityNameHistory,
  EntityMergeRecordView,
  PageResponse,
  EntityFilterParams,
  ClientCategory,
  KnowledgeExperienceLevel,
  RiskTolerance,
  SuitabilityAssessment,
} from '../models';

@Injectable({ providedIn: 'root' })
export class EntityService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/entities`;

  getEntities(params: EntityFilterParams = {}): Observable<PageResponse<LegalEntity>> {
    let httpParams = new HttpParams();
    if (params.type) httpParams = httpParams.set('type', params.type);
    if (params.status) httpParams = httpParams.set('status', params.status);
    if (params.kycStatus) httpParams = httpParams.set('kycStatus', params.kycStatus);
    if (params.search) httpParams = httpParams.set('search', params.search);
    if (params.page != null) httpParams = httpParams.set('page', params.page.toString());
    if (params.size != null) httpParams = httpParams.set('size', params.size.toString());
    if (params.sort) httpParams = httpParams.set('sort', params.sort);

    return this.http.get<PageResponse<LegalEntity>>(this.base, { params: httpParams });
  }

  getEntity(id: string): Observable<LegalEntity> {
    return this.http.get<LegalEntity>(`${this.base}/${id}`);
  }

  /** "My clients" — entities assigned to the caller as relationship manager (Track 5-4). */
  myClients(): Observable<LegalEntity[]> {
    return this.http.get<LegalEntity[]>(`${this.base}/my-clients`);
  }

  assignRelationshipManager(id: string, relationshipManagerId: string | null): Observable<LegalEntity> {
    return this.http.post<LegalEntity>(`${this.base}/${id}/relationship-manager`, { relationshipManagerId });
  }

  createEntity(body: Partial<LegalEntity>): Observable<LegalEntity> {
    return this.http.post<LegalEntity>(this.base, body);
  }

  updateEntity(id: string, body: Partial<LegalEntity>): Observable<LegalEntity> {
    return this.http.put<LegalEntity>(`${this.base}/${id}`, body);
  }

  suspendEntity(id: string): Observable<LegalEntity> {
    return this.http.post<LegalEntity>(`${this.base}/${id}/suspend`, {});
  }

  dissolveEntity(id: string): Observable<LegalEntity> {
    return this.http.post<LegalEntity>(`${this.base}/${id}/dissolve`, {});
  }

  reactivateEntity(id: string): Observable<LegalEntity> {
    return this.http.post<LegalEntity>(`${this.base}/${id}/reactivate`, {});
  }

  getEntityHistory(id: string): Observable<{ nameHistory: LegalEntityNameHistory[]; mergeRecords: EntityMergeRecordView[] }> {
    return this.http.get<{ nameHistory: LegalEntityNameHistory[]; mergeRecords: EntityMergeRecordView[] }>(`${this.base}/${id}/history`);
  }

  mergeEntity(sourceId: string, body: {
    targetEntityId: string;
    mergeType: 'ABSORPTION' | 'CONSOLIDATION';
    effectiveDate: string;
    notes?: string;
  }): Observable<EntityMergeRecordView> {
    return this.http.post<EntityMergeRecordView>(`${this.base}/${sourceId}/merge`, body);
  }

  /**
   * Wraps `CustomerController.terminateEntity` (`POST /entities/{id}/terminate`), which had
   * no frontend caller: the customer off-ramp — disabling users, cancelling listings, revoking
   * admin grants, and moving the entity to CLOSED — was previously curl-only despite carrying
   * the same step-up + dual-control bar as a forced transfer.
   */
  terminateEntity(
    id: string,
    reason: string,
    stepUpToken: string,
    dualControlToken: string,
  ): Observable<LegalEntity> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<LegalEntity>(`${this.base}/${id}/terminate`, { reason }, { headers });
  }

  /** Sets the entity's MiFID II client category — the firm classifies the client. */
  classifyClient(id: string, clientCategory: ClientCategory): Observable<LegalEntity> {
    return this.http.post<LegalEntity>(`${this.base}/${id}/classification`, { clientCategory });
  }

  listSuitabilityAssessments(id: string): Observable<SuitabilityAssessment[]> {
    return this.http.get<SuitabilityAssessment[]>(`${this.base}/${id}/suitability-assessments`);
  }

  recordSuitabilityAssessment(id: string, body: {
    knowledgeExperience: KnowledgeExperienceLevel;
    riskTolerance: RiskTolerance;
    investmentHorizonYears?: number | null;
    financialSituationAdequate: boolean;
    notes?: string | null;
  }): Observable<SuitabilityAssessment> {
    return this.http.post<SuitabilityAssessment>(`${this.base}/${id}/suitability-assessments`, body);
  }
}
