import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface ValueSet {
  id: number;
  name: string;
  description: string;
  ecl: string;
  branch: string;
  status: 'DRAFT' | 'PUBLISHED';
  visibility: 'PRIVATE' | 'COMMUNITY';
  ownerName: string;
  ownedByMe: boolean;
  updatedAt: string;
}

/** The fields a person can set when creating or editing a value set. */
export type ValueSetDraft = Pick<ValueSet, 'name' | 'description' | 'ecl' | 'branch' | 'visibility'>;

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
