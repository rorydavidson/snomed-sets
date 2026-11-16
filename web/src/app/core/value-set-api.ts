import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface ValueSet {
  id: number;
  /** Defined by an ECL expression, or by a hand-picked list of concepts. Fixed when created. */
  type: 'ECL' | 'MANUAL';
  /** Short name of the code system the set is built against, for example SNOMEDCT. Fixed when created. */
  codeSystem: string;
  name: string;
  description: string;
  ecl: string;
  branch: string;
  status: 'DRAFT' | 'PUBLISHED';
  visibility: 'PRIVATE' | 'COMMUNITY';
  ownerName: string;
  ownedByMe: boolean;
  updatedAt: string;
  /** The newest published version number, or null if never published. */
  latestVersion: number | null;
  /** True only for the owner, when the definition differs from the latest published version. */
  unpublishedChanges: boolean;
}

/** A definition frozen at the moment it was published. */
export interface ValueSetVersion {
  number: number;
  ecl: string;
  branch: string;
  conceptCount: number;
  note: string;
  publishedAt: string;
  /** False for an old version whose concept list was not stored when it was published. */
  frozen: boolean;
}

/** The fields a person can set when creating or editing a value set. */
export type ValueSetDraft = Pick<ValueSet, 'type' | 'codeSystem' | 'name' | 'description' | 'ecl' | 'visibility'> & {
  /** The whole list of a hand-picked value set. Empty for an ECL one. */
  conceptIds: string[];
};

/** A SNOMED CT edition or extension held by Snowstorm. */
export interface CodeSystem {
  shortName: string;
  name: string;
  branchPath: string;
  latestVersion: string | null;
}

export interface Concept {
  conceptId: string;
  preferredTerm: string | null;
  fullySpecifiedName: string | null;
}

export interface ExpansionPage {
  items: Concept[];
  total: number;
  offset: number;
  limit: number;
}

@Injectable({ providedIn: 'root' })
export class ValueSetApi {
  private readonly http = inject(HttpClient);

  list(): Observable<ValueSet[]> {
    return this.http.get<ValueSet[]>('/api/value-sets');
  }

  get(id: number): Observable<ValueSet> {
    return this.http.get<ValueSet>(`/api/value-sets/${id}`);
  }

  expansion(id: number, offset: number, limit: number): Observable<ExpansionPage> {
    return this.http.get<ExpansionPage>(`/api/value-sets/${id}/expansion`, { params: { offset, limit } });
  }

  create(draft: ValueSetDraft): Observable<ValueSet> {
    return this.http.post<ValueSet>('/api/value-sets', draft);
  }

  update(id: number, draft: ValueSetDraft): Observable<ValueSet> {
    return this.http.put<ValueSet>(`/api/value-sets/${id}`, draft);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`/api/value-sets/${id}`);
  }

  codeSystems(): Observable<CodeSystem[]> {
    return this.http.get<CodeSystem[]>('/api/code-systems');
  }

  /** The hand-picked list as it is being edited. Owner only. */
  members(id: number): Observable<Concept[]> {
    return this.http.get<Concept[]>(`/api/value-sets/${id}/members`);
  }

  searchConcepts(term: string, branch: string): Observable<ExpansionPage> {
    return this.http.get<ExpansionPage>('/api/concepts', { params: { term, branch } });
  }

  /** The direct children of a concept. The total is larger than the list when it was cut short. */
  children(conceptId: string, branch: string): Observable<{ items: Concept[]; total: number }> {
    return this.http.get<{ items: Concept[]; total: number }>(`/api/concepts/${conceptId}/children`, {
      params: { branch },
    });
  }

  versions(id: number): Observable<ValueSetVersion[]> {
    return this.http.get<ValueSetVersion[]>(`/api/value-sets/${id}/versions`);
  }

  publish(id: number, note: string): Observable<ValueSetVersion> {
    return this.http.post<ValueSetVersion>(`/api/value-sets/${id}/versions`, { note });
  }

  versionExpansion(id: number, version: number, offset: number, limit: number): Observable<ExpansionPage> {
    return this.http.get<ExpansionPage>(`/api/value-sets/${id}/versions/${version}/expansion`, {
      params: { offset, limit },
    });
  }

  /** Expands a definition that has not been saved yet. */
  preview(ecl: string, branch: string, limit: number): Observable<ExpansionPage> {
    return this.http.get<ExpansionPage>('/api/ecl/expansion', { params: { ecl, branch, limit } });
  }
}

/** Turns a failed API call into a sentence that can be shown to the user. */
export function describeError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 401) {
      return 'Your session has ended. Sign in again to continue.';
    }
    if (error.status === 404) {
      return 'This value set does not exist, or you do not have access to it.';
    }
    // The backend sends problem details; its "detail" field is written for people.
    const detail: unknown = error.error?.detail;
    if (typeof detail === 'string' && detail.length > 0) {
      return detail;
    }
  }
  return 'Something went wrong. Try again in a moment.';
}
