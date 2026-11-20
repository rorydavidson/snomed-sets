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

export type Direction = 'TO_SNOMED' | 'FROM_SNOMED';

/** The relationships to choose from, worded for the side the targets are on. */
export function relationshipsFor(direction: Direction): { value: Relationship; label: string }[] {
  const target = direction === 'FROM_SNOMED' ? 'Local code' : 'SNOMED CT concept';
  return [
    { value: 'EQUIVALENT', label: 'Equivalent' },
    { value: 'TARGET_BROADER', label: `${target} is broader` },
    { value: 'TARGET_NARROWER', label: `${target} is narrower` },
    { value: 'RELATED', label: 'Related' },
  ];
}

export interface MapCounts {
  total: number;
  unmapped: number;
  suggested: number;
  mapped: number;
  noMap: number;
}

/**
 * A map between a list of local codes and SNOMED CT. To SNOMED CT, the local codes are its rows.
 * From SNOMED CT, concepts are its rows and the local codes are what targets are chosen from.
 * Only ever sent to its owner.
 */
export interface CodeMap {
  id: number;
  name: string;
  description: string;
  direction: Direction;
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
  /** In a map from SNOMED CT, how many local codes there are to choose from; 0 otherwise. */
  localCodeCount: number;
}

export interface MapTarget {
  code: string;
  term: string;
  /** Empty for a local code. */
  fullySpecifiedName: string;
  relationship: Relationship;
  /** Chosen by a person, or found by matching the source term. */
  origin: 'MANUAL' | 'EXACT_TERM';
}

export interface MapRow {
  id: number;
  code: string;
  term: string;
  /** Of a SNOMED CT source; empty for a local code. */
  fullySpecifiedName: string;
  /** While SUGGESTED, the targets are suggestions nobody has accepted. */
  status: RowStatus;
  note: string | null;
  /** True once the term has been looked for on the other side of the map. */
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
  direction: Direction;
  codeSystem: string;
  /** The release to build on; null for the latest. */
  codeSystemVersion: string | null;
  /** The local codes: what is mapped in a map to SNOMED CT, what is chosen from in a map from it. */
  rows: SourceRow[];
  /** The concepts to map, for a map from SNOMED CT; empty otherwise. */
  conceptIds: string[];
}

export interface RowUpdate {
  /** SNOMED CT concept identifiers in a map to SNOMED CT, local codes in a map from it. */
  targets: { code: string; relationship: Relationship }[];
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

  /** The local codes targets are chosen from, for a map from SNOMED CT. */
  localCodes(id: number): Observable<SourceRow[]> {
    return this.http.get<SourceRow[]>(`/api/maps/${id}/local-codes`);
  }

  /** More local codes: rows to map in a map to SNOMED CT, codes to choose from in a map from it. */
  addLocalCodes(id: number, rows: SourceRow[]): Observable<CodeMap> {
    return this.http.post<CodeMap>(`/api/maps/${id}/local-codes`, { rows });
  }

  /** More concepts to map, for a map from SNOMED CT. */
  addConcepts(id: number, conceptIds: string[]): Observable<CodeMap> {
    return this.http.post<CodeMap>(`/api/maps/${id}/concepts`, { conceptIds });
  }

  updateRow(id: number, rowId: number, update: RowUpdate): Observable<MapRow> {
    return this.http.put<MapRow>(`/api/maps/${id}/rows/${rowId}`, update);
  }

  deleteRow(id: number, rowId: number): Observable<void> {
    return this.http.delete<void>(`/api/maps/${id}/rows/${rowId}`);
  }

  /**
   * Looks for the terms of some unmapped rows on the other side of the map. Returns those rows as they are
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
