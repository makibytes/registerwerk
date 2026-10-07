import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';
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

export interface OffboardingObligation {
  obligationId: string;
  kind: string;
  refId: string;
  description: string;
}

export interface EntityTask {
  id: string;
  entityId: string;
  kind: string;
  refId: string | null;
  detail: string | null;
  status: 'OPEN' | 'DONE' | string;
  createdAt: string;
  doneAt: string | null;
  doneBy: string | null;
  doneNote: string | null;
}

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

  /** Step-up + second approver (`ENTITY_SUSPEND`); a reason is mandatory. ACTIVE -> SUSPENDED only. */
  suspendEntity(id: string, reason: string, tokens: DualControlTokens): Observable<LegalEntity> {
    return this.http.post<LegalEntity>(`${this.base}/${id}/suspend`, { reason }, { headers: dualControlHeaders(tokens) });
  }

  /**
   * Step-up + second approver (`ENTITY_REACTIVATE`). The server refuses (409) while KYC is expired or
   * rejected, a screening hit is unresolved or a Sperrvermerk is active; show its message.
   */
  reactivateEntity(id: string, reason: string, tokens: DualControlTokens): Observable<LegalEntity> {
    return this.http.post<LegalEntity>(`${this.base}/${id}/reactivate`, { reason }, { headers: dualControlHeaders(tokens) });
  }

  /**
   * Step-up + second approver (`ENTITY_REINSTATE`), body-bound: CLOSED/DISSOLVED -> PENDING_REACTIVATION
   * (never straight to ACTIVE). `reason` and `legalReference` are mandatory; a fresh KYC approval completes it.
   */
  reinstateEntity(id: string, reason: string, legalReference: string, tokens: DualControlTokens): Observable<void> {
    return this.http.post<void>(`${this.base}/${id}/reinstate`, { reason, legalReference }, { headers: dualControlHeaders(tokens) });
  }

  getEntityHistory(id: string): Observable<{ nameHistory: LegalEntityNameHistory[]; mergeRecords: EntityMergeRecordView[] }> {
    return this.http.get<{ nameHistory: LegalEntityNameHistory[]; mergeRecords: EntityMergeRecordView[] }>(`${this.base}/${id}/history`);
  }

  /** Step-up + second approver (`ENTITY_MERGE`); `reason` is mandatory. */
  mergeEntity(sourceId: string, body: {
    targetEntityId: string;
    mergeType: 'ABSORPTION' | 'CONSOLIDATION';
    effectiveDate: string;
    notes?: string;
    reason: string;
    evidenceDocumentId?: string;
  }, tokens: DualControlTokens): Observable<EntityMergeRecordView> {
    return this.http.post<EntityMergeRecordView>(`${this.base}/${sourceId}/merge`, body, { headers: dualControlHeaders(tokens) });
  }

  /** Open obligations that `terminateEntity` needs an acknowledgement for. */
  getOffboardingObligations(id: string): Observable<OffboardingObligation[]> {
    return this.http.get<OffboardingObligation[]>(`${this.base}/${id}/offboarding-obligations`);
  }

  /**
   * `POST /entities/{id}/terminate`: the off-ramp (disables users, cancels listings, moves to CLOSED).
   * Open obligations must each be acknowledged by id, otherwise the server answers 409 listing them.
   */
  terminateEntity(
    id: string,
    reason: string,
    acknowledgedObligations: { obligationId: string; reason: string }[],
    tokens: DualControlTokens,
  ): Observable<LegalEntity> {
    return this.http.post<LegalEntity>(
      `${this.base}/${id}/terminate`, { reason, acknowledgedObligations }, { headers: dualControlHeaders(tokens) });
  }

  /** Sets the entity's MiFID II client category — the firm classifies the client. */
  classifyClient(
    id: string,
    body: { clientCategory: ClientCategory; reason?: string; evidenceDocumentId?: string },
    stepUpToken: string,
    dualControlToken?: string,
  ): Observable<LegalEntity> {
    let headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    // Lowering the category is a 4-eyes action (`CLIENT_CLASSIFICATION_DOWNGRADE`) and needs a reason.
    if (dualControlToken) headers = headers.set('X-Dual-Control-Token', dualControlToken);
    return this.http.post<LegalEntity>(`${this.base}/${id}/classification`, body, { headers });
  }

  listEntityTasks(entityId: string): Observable<EntityTask[]> {
    return this.http.get<EntityTask[]>(`${this.base}/${entityId}/tasks`);
  }

  /** Open operator follow-up tasks over all entities (offboarding, KYC review, chain reinstatement, Sperrvermerk expiry). */
  listOpenTasks(): Observable<EntityTask[]> {
    return this.http.get<EntityTask[]>(`${environment.apiUrl}/entity-tasks`);
  }

  completeTask(taskId: string, note: string): Observable<EntityTask> {
    return this.http.post<EntityTask>(`${environment.apiUrl}/entity-tasks/${taskId}/done`, { note });
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
