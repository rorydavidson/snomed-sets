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
  /** A release branch such as MAIN/2026-01-01, or the live branch for older sets. */
  branch: string;
  /** The code system release the content comes from, for example 2026-01-01, or null for the live branch. */
  codeSystemVersion: string | null;
  status: 'DRAFT' | 'PUBLISHED';
  visibility: 'PRIVATE' | 'COMMUNITY';
  ownerName: string;
  ownedByMe: boolean;
  updatedAt: string;
  /** The newest published version number, or null if never published. */
  latestVersion: number | null;
  /** True only for the owner, when the definition differs from the latest published version. */
  unpublishedChanges: boolean;
  /** Free-text labels, in lower case. */
  tags: string[];
  /** True when the owner has marked the whole value set as not to be used any more. */
  deprecated: boolean;
  /** True when the set has been published and every one of its versions is deprecated. */
  allVersionsDeprecated: boolean;
  deprecationReason: string | null;
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
  deprecated: boolean;
  deprecationReason: string | null;
}

/** The fields a person can set when creating or editing a value set. */
export type ValueSetDraft = Pick<
  ValueSet,
  'type' | 'codeSystem' | 'name' | 'description' | 'ecl' | 'visibility' | 'tags'
> & {
  /** The whole list of a hand-picked value set. Empty for an ECL one. */
  conceptIds: string[];
  /**
   * The release to build a new value set on, for example 2026-01-01; the latest when null.
   * Ignored when editing: only an upgrade moves a value set to another release.
   */
  codeSystemVersion?: string | null;
};

/** One release of a code system. */
export interface CodeSystemVersion {
  version: string;
  branchPath: string;
}

/** A SNOMED CT edition or extension held by Snowstorm. */
export interface CodeSystem {
  shortName: string;
  name: string;
  branchPath: string;
  latestVersion: string | null;
  /** The branch the latest release is frozen on, for example MAIN/2026-01-01. New value sets are built on it. */
  latestVersionBranch: string | null;
}

export interface Concept {
  conceptId: string;
  preferredTerm: string | null;
  fullySpecifiedName: string | null;
  /** Language of the preferred term, for example "en". Missing only in old data. */
  language?: string | null;
  /** False for a concept kept in a hand-picked list after a release made it inactive. */
  active?: boolean;
}

export interface ConceptRelationship {
  typeId: string;
  typeTerm: string | null;
  targetId: string;
  targetTerm: string | null;
  group: number;
}

/** The edition a concept was looked up in. */
export interface EditionSummary {
  shortName: string;
  name: string;
  /** The language terms are shown in first, for example "no"; "en" for the International Edition. */
  language: string;
  /** Languages the edition holds, by code, with their names. */
  languages: Record<string, string>;
}

export interface ConceptDescription {
  term: string;
  type: string;
  language: string;
  /** Language reference sets where this is the preferred term: en-US, en-GB, or an identifier named in `names`. */
  preferredIn: string[];
  /** The same, where it is an acceptable synonym. */
  acceptableIn: string[];
  moduleId: string | null;
}

/** What is shown when a concept is clicked: its descriptions, inferred relationships and where it comes from. */
export interface ConceptDetails {
  conceptId: string;
  fullySpecifiedName: string | null;
  /** In the edition's language where the concept has a term in it. */
  preferredTerm: string | null;
  preferredTermLanguage: string | null;
  active: boolean;
  definitionStatus: string | null;
  moduleId: string | null;
  effectiveTime: string | null;
  descriptions: ConceptDescription[];
  parents: ConceptRelationship[];
  attributes: ConceptRelationship[];
  /** Null when Snowstorm could not say which edition the branch belongs to. */
  edition: EditionSummary | null;
  moduleName: string | null;
  /** True when the concept's module is part of the International Edition rather than a national extension. */
  international: boolean;
  /** Names for module and language reference set identifiers. */
  names: Record<string, string>;
  /** Which of the modules mentioned belong to the International Edition. */
  internationalModules: string[];
}

/** Whether a newer release of the code system is available. Only the owner is told. */
export interface UpgradeAvailability {
  codeSystem: string;
  currentVersion: string | null;
  latestVersion: string | null;
  available: boolean;
}

export interface UpgradeReplacement {
  conceptId: string;
  term: string | null;
  /** The historical association that suggests it, for example REPLACED_BY or SAME_AS. */
  association: string;
  /** Already in the list, so accepting it would add nothing. */
  alreadyInSet: boolean;
}

/** What an upgrade to the latest release would change. Nothing has been saved. */
export interface UpgradePreview {
  codeSystem: string;
  type: 'ECL' | 'MANUAL';
  fromVersion: string | null;
  toVersion: string;
  fromBranch: string;
  toBranch: string;
  conceptCountBefore: number;
  /** ECL only: concepts matched only in the new release. */
  added: Concept[];
  /** ECL only: concepts no longer matched. */
  removed: Concept[];
  termChanges: { conceptId: string; oldTerm: string | null; newTerm: string | null }[];
  /** Hand-picked only: concepts inactive in the new release, with suggested replacements. */
  inactivated: {
    conceptId: string;
    term: string | null;
    reason: string | null;
    replacements: UpgradeReplacement[];
    /** Kept, flagged as inactive, by an earlier upgrade, so it starts as kept again. */
    keptBefore: boolean;
  }[];
}

/** One identifier from an imported list, with the term the list gave it if it had a column of terms. */
export interface IdentifierEntry {
  identifier: string;
  term: string | null;
}

export type IdentifierRejection = 'NOT_AN_IDENTIFIER' | 'INVALID_CHECK_DIGIT' | 'NOT_A_CONCEPT' | 'NOT_IN_RELEASE';

/** What an imported list of identifiers holds on a release. Nothing has been saved. */
export interface IdentifierCheck {
  branch: string;
  entryCount: number;
  /** Identifiers naming a concept already named earlier in the list. */
  duplicateCount: number;
  found: {
    conceptId: string;
    preferredTerm: string | null;
    fullySpecifiedName: string | null;
    language: string | null;
    /** Set when the list gave a description identifier, which was turned into its concept. */
    descriptionId: string | null;
    suppliedTerm: string | null;
    /** The list's term is neither the preferred term nor the FSN. It may still be a synonym. */
    termDiffers: boolean;
  }[];
  inactive: {
    conceptId: string;
    term: string | null;
    reason: string | null;
    descriptionId: string | null;
    replacements: UpgradeReplacement[];
  }[];
  rejected: { identifier: string; reason: IdentifierRejection }[];
}

/** An ECL expression worked out to match a hand-picked list, checked by expanding it. Nothing is saved. */
export interface EclSuggestion {
  codeSystem: string;
  codeSystemVersion: string | null;
  branch: string;
  ecl: string;
  /** Clauses covering part of the hierarchy, such as << X. */
  structuredClauses: number;
  /** Concepts named one by one. */
  namedConcepts: number;
  /** Active concepts in the list. */
  conceptCount: number;
  exact: boolean;
  /** Some of the list's concepts the expression misses. */
  missing: Concept[];
  missingCount: number;
  /** Some of the concepts the expression matches that the list does not have. */
  extra: Concept[];
  extraCount: number;
  /** Inactive concepts kept in the list, which no ECL expression can match. */
  leftOut: Concept[];
  /** False when the expression is longer than a value set definition may be. */
  fitsDefinition: boolean;
  maxLength: number;
}

/** What the editor fills in when a new ECL value set starts from a suggestion. */
export interface EclPrefill {
  name: string;
  description: string;
  ecl: string;
  codeSystem: string;
  codeSystemVersion: string | null;
}

export type UpgradeChangeType =
  | 'ADDED'
  | 'REMOVED'
  | 'INACTIVATED'
  | 'INACTIVE_KEPT'
  | 'REPLACEMENT_ADDED'
  | 'REPLACEMENT_DECLINED'
  | 'TERM_CHANGED';

/** A saved upgrade, with a change report that can be downloaded. */
export interface ValueSetUpgrade {
  id: number;
  fromVersion: string | null;
  toVersion: string;
  fromBranch: string;
  toBranch: string;
  upgradedAt: string;
  /** The version published with the upgrade, or null if it was saved as a draft. */
  publishedVersion: number | null;
  changeCounts: Partial<Record<UpgradeChangeType, number>>;
}

export interface UpgradeRequest {
  targetVersion: string;
  acceptedReplacements: { inactiveConceptId: string; replacementConceptId: string }[];
  /** Inactive concepts to keep in the list, flagged as inactive. Any left out are removed. */
  keptInactiveConceptIds: string[];
  publish: boolean;
  note: string;
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

  /** Tags already in use on value sets the signed-in person can see. */
  tags(): Observable<string[]> {
    return this.http.get<string[]>('/api/tags');
  }

  codeSystems(): Observable<CodeSystem[]> {
    return this.http.get<CodeSystem[]>('/api/code-systems');
  }

  /** The releases of a code system, newest first. */
  codeSystemVersions(shortName: string): Observable<CodeSystemVersion[]> {
    return this.http.get<CodeSystemVersion[]>(`/api/code-systems/${encodeURIComponent(shortName)}/versions`);
  }

  /** The hand-picked list as it is being edited. Owner only. */
  members(id: number): Observable<Concept[]> {
    return this.http.get<Concept[]>(`/api/value-sets/${id}/members`);
  }

  searchConcepts(term: string, branch: string): Observable<ExpansionPage> {
    return this.http.get<ExpansionPage>('/api/concepts', { params: { term, branch } });
  }

  /** How many direct children each concept has, keyed by concept ID. */
  childCounts(conceptIds: string[], branch: string): Observable<Record<string, number>> {
    return this.http.get<Record<string, number>>('/api/concepts/child-counts', { params: { conceptIds, branch } });
  }

  conceptDetails(conceptId: string, branch: string): Observable<ConceptDetails> {
    return this.http.get<ConceptDetails>(`/api/concepts/${conceptId}`, { params: { branch } });
  }

  /** The direct children of a concept. The total is larger than the list when it was cut short. */
  children(conceptId: string, branch: string): Observable<{ items: Concept[]; total: number }> {
    return this.http.get<{ items: Concept[]; total: number }>(`/api/concepts/${conceptId}/children`, {
      params: { branch },
    });
  }

  /** Throws away the changes made since the published version in use. */
  discardChanges(id: number): Observable<ValueSet> {
    return this.http.post<ValueSet>(`/api/value-sets/${id}/discard-changes`, null);
  }

  deprecate(id: number, reason: string): Observable<ValueSet> {
    return this.http.put<ValueSet>(`/api/value-sets/${id}/deprecation`, { reason });
  }

  restore(id: number): Observable<ValueSet> {
    return this.http.delete<ValueSet>(`/api/value-sets/${id}/deprecation`);
  }

  deprecateVersion(id: number, version: number, reason: string): Observable<ValueSetVersion> {
    return this.http.put<ValueSetVersion>(`/api/value-sets/${id}/versions/${version}/deprecation`, { reason });
  }

  restoreVersion(id: number, version: number): Observable<ValueSetVersion> {
    return this.http.delete<ValueSetVersion>(`/api/value-sets/${id}/versions/${version}/deprecation`);
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

  upgradeAvailability(id: number): Observable<UpgradeAvailability> {
    return this.http.get<UpgradeAvailability>(`/api/value-sets/${id}/upgrade`);
  }

  /** Works out what an upgrade would change, without saving anything. */
  upgradePreview(id: number): Observable<UpgradePreview> {
    return this.http.get<UpgradePreview>(`/api/value-sets/${id}/upgrade/preview`);
  }

  upgrade(id: number, request: UpgradeRequest): Observable<{ valueSet: ValueSet; upgrade: ValueSetUpgrade }> {
    return this.http.post<{ valueSet: ValueSet; upgrade: ValueSetUpgrade }>(`/api/value-sets/${id}/upgrade`, request);
  }

  upgrades(id: number): Observable<ValueSetUpgrade[]> {
    return this.http.get<ValueSetUpgrade[]>(`/api/value-sets/${id}/upgrades`);
  }

  /** Checks a list of identifiers against a release before it goes into a hand-picked list. Saves nothing. */
  checkIdentifiers(branch: string, entries: IdentifierEntry[]): Observable<IdentifierCheck> {
    return this.http.post<IdentifierCheck>('/api/identifier-checks', { branch, entries });
  }

  /** Every concept an ECL expression matches now, in the same shape, to add to a hand-picked list once. */
  checkEclImport(branch: string, ecl: string): Observable<IdentifierCheck> {
    return this.http.post<IdentifierCheck>('/api/identifier-checks/ecl', { branch, ecl });
  }

  /** Works out an ECL expression matching a hand-picked list. Saves nothing. */
  eclSuggestion(id: number): Observable<EclSuggestion> {
    return this.http.get<EclSuggestion>(`/api/value-sets/${id}/ecl-suggestion`);
  }

  /** Expands a definition that has not been saved yet. */
  preview(ecl: string, branch: string, limit: number): Observable<ExpansionPage> {
    return this.http.get<ExpansionPage>('/api/ecl/expansion', { params: { ecl, branch, limit } });
  }
}

/** Where the CSV report of one upgrade is downloaded from. */
export function upgradeReportUrl(valueSetId: number, upgradeId: number): string {
  return `/api/value-sets/${valueSetId}/upgrades/${upgradeId}/report`;
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
