import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { describeError } from './value-set-api';

export type RowStatus = 'UNMAPPED' | 'SUGGESTED' | 'MAPPED' | 'NO_MAP';

/**
 * How a target's meaning compares with the source's. The names say which side is broader, as
 * "wider" and "narrower" alone are read both ways round.
 */
export type Relationship = 'EQUIVALENT' | 'TARGET_BROADER' | 'TARGET_NARROWER' | 'RELATED';

export const RELATIONSHIPS: { value: Relationship; label: string }[] = [
  { value: 'EQUIVALENT', label: 'Equivalent' },
  { value: 'TARGET_BROADER', label: 'SNOMED CT concept is broader' },
  { value: 'TARGET_NARROWER', label: 'SNOMED CT concept is narrower' },
  { value: 'RELATED', label: 'Related' },
];

export interface MapCounts {
  total: number;
  unmapped: number;
  suggested: number;
  mapped: number;
  noMap: number;
}

/** A map between a list of local codes and SNOMED CT. Only ever sent to its owner. */
export interface CodeMap {
  id: number;
  name: string;
  description: string;
  direction: 'TO_SNOMED' | 'FROM_SNOMED';
  codeSystem: string;
  /** The release branch SNOMED CT is read from, such as MAIN/2026-01-01. */
  branch: string;
  /** The release the map is built on, for example 2026-01-01, or null for a live branch. */
  codeSystemVersion: string | null;
  /** What the owner calls the local list of codes. */
  localSystemName: string;
  localSystemUri: string | null;
  localSystemVersion: string | null;
  updatedAt: string;
  counts: MapCounts;
}

export interface MapTarget {
  code: string;
  term: string;
  fullySpecifiedName: string;
  relationship: Relationship;
  /** Chosen by a person, or found by matching the source term. */
  origin: 'MANUAL' | 'EXACT_TERM';
}

export interface MapRow {
  id: number;
  code: string;
  term: string;
  /** While SUGGESTED, the targets are suggestions nobody has accepted. */
  status: RowStatus;
  note: string | null;
  /** True once the term has been looked for in SNOMED CT. */
  termChecked: boolean;
  targets: MapTarget[];
}

export interface SourceRow {
  code: string;
  term: string;
}

export type MapDetails = Pick<CodeMap, 'name' | 'description' | 'localSystemName' | 'localSystemUri' | 'localSystemVersion'>;

export interface MapDraft {
  details: MapDetails;
  codeSystem: string;
  /** The release to build on; null for the latest. */
  codeSystemVersion: string | null;
  rows: SourceRow[];
}

export interface RowUpdate {
  targets: { conceptId: string; relationship: Relationship }[];
  noMap: boolean;
  note: string | null;
}

/** The most local codes a map can hold, and the most targets one of them can have. */
export const MAX_MAP_ROWS = 5000;
export const MAX_TARGETS = 10;
/** The most rows whose terms are matched in one request. */
export const MATCH_CHUNK = 100;

@Injectable({ providedIn: 'root' })
export class MapApi {
  private readonly http = inject(HttpClient);

  list(): Observable<CodeMap[]> {
    return this.http.get<CodeMap[]>('/api/maps');
  }

  get(id: number): Observable<CodeMap> {
    return this.http.get<CodeMap>(`/api/maps/${id}`);
  }

  create(draft: MapDraft): Observable<CodeMap> {
    return this.http.post<CodeMap>('/api/maps', draft);
  }

  update(id: number, details: MapDetails): Observable<CodeMap> {
    return this.http.put<CodeMap>(`/api/maps/${id}`, details);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`/api/maps/${id}`);
  }

  /** Every row of the map, in the order the codes were given. */
  rows(id: number): Observable<MapRow[]> {
    return this.http.get<MapRow[]>(`/api/maps/${id}/rows`);
  }

  addRows(id: number, rows: SourceRow[]): Observable<CodeMap> {
    return this.http.post<CodeMap>(`/api/maps/${id}/rows`, { rows });
  }

  updateRow(id: number, rowId: number, update: RowUpdate): Observable<MapRow> {
    return this.http.put<MapRow>(`/api/maps/${id}/rows/${rowId}`, update);
  }

  deleteRow(id: number, rowId: number): Observable<void> {
    return this.http.delete<void>(`/api/maps/${id}/rows/${rowId}`);
  }

  /**
   * Looks for the terms of some unmapped rows in SNOMED CT. Returns those rows as they are
   * afterwards, with what was found as suggestions.
   */
  matchTerms(id: number, rowIds: number[]): Observable<MapRow[]> {
    return this.http.post<MapRow[]>(`/api/maps/${id}/term-matches`, { rowIds });
  }

  /** Accepts the suggestion of every row that has exactly one. */
  acceptSingleSuggestions(id: number): Observable<{ accepted: number }> {
    return this.http.post<{ accepted: number }>(`/api/maps/${id}/accepted-suggestions`, {});
  }
}

export function mapDownloadUrl(id: number, format: 'CSV' | 'TSV'): string {
  return `/api/maps/${id}/download?format=${format}`;
}

/** Turns a failed call about a map into a sentence that can be shown to the user. */
export function describeMapError(error: unknown): string {
  if (error instanceof HttpErrorResponse && error.status === 404) {
    return 'This map does not exist, or you do not have access to it.';
  }
  return describeError(error);
}
