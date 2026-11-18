import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { SIGN_IN_URL } from '../core/session';
import { ConceptSearchHit, ValueSetApi, describeError } from '../core/value-set-api';

export type SearchMode = 'sets' | 'concepts';

/** What the library should show: every set, sets matching words, or sets containing matching concepts. */
export type LibrarySearchResult =
  | { mode: 'sets'; query: string }
  | { mode: 'concepts'; query: string; hits: ConceptSearchHit[] }
  | null;

/** Visitors may search for concepts once in this long; the server enforces the same limit. */
const VISITOR_WAIT_MS = 3 * 60 * 1000;
/** Kept in this browser only, so a reload still shows the wait. It holds nothing about the person. */
const NEXT_SEARCH_KEY = 'snomed-sets.next-concept-search';
const MIN_CONCEPT_LENGTH = 3;

/**
 * Searches the library, either for value sets by name, description and tag, or for value sets
 * containing concepts. Runs only when the search button is pressed, never while typing.
 */
@Component({
  selector: 'app-library-search',
  templateUrl: './library-search.html',
  styleUrl: './library-search.scss',
})
export class LibrarySearch {
  private readonly api = inject(ValueSetApi);

  readonly signedIn = input.required<boolean>();
  readonly results = output<LibrarySearchResult>();

  protected readonly signInUrl = SIGN_IN_URL;
  protected readonly mode = signal<SearchMode>('sets');
  protected readonly text = signal('');
  protected readonly searching = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Whether a search is applied, so it can be cleared. */
  protected readonly active = signal(false);

  private readonly now = signal(Date.now());
  private readonly nextConceptSearchAt = signal(readNextSearch());
  /** Seconds a visitor must wait before their next concept search, or 0. */
  protected readonly waitSeconds = computed(() =>
    this.signedIn() ? 0 : Math.max(0, Math.ceil((this.nextConceptSearchAt() - this.now()) / 1000)),
  );
  protected readonly waitLabel = computed(() => {
    const seconds = this.waitSeconds();
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
  });

  constructor() {
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected setMode(mode: SearchMode): void {
    this.mode.set(mode);
    this.error.set(null);
  }

  /** From a tag in the table: show the sets carrying it. */
  searchTag(tag: string): void {
    this.mode.set('sets');
    this.text.set(tag);
    this.runSetSearch(tag);
  }

  protected submit(event: Event): void {
    event.preventDefault();
    this.error.set(null);
    const query = this.text().trim();
    if (this.mode() === 'sets') {
      this.runSetSearch(query);
      return;
    }
    if (query.length < MIN_CONCEPT_LENGTH) {
      this.error.set(`Type at least ${MIN_CONCEPT_LENGTH} characters of a concept term, or a SNOMED CT ID.`);
      return;
    }
    if (this.waitSeconds() > 0) {
      return;
    }
    this.searching.set(true);
    this.api.conceptSearch(query).subscribe({
      next: (hits) => {
        this.searching.set(false);
        this.startWait(VISITOR_WAIT_MS);
        this.active.set(true);
        this.results.emit({ mode: 'concepts', query, hits });
      },
      error: (error: unknown) => {
        this.searching.set(false);
        if (error instanceof HttpErrorResponse && error.status === 429) {
          // The note about the limit and its countdown already say what to do.
          const retryAfter = Number(error.headers.get('Retry-After'));
          this.startWait(Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter * 1000 : VISITOR_WAIT_MS);
          return;
        }
        this.error.set(describeError(error));
      },
    });
  }

  protected clear(): void {
    this.text.set('');
    this.error.set(null);
    this.active.set(false);
    this.results.emit(null);
  }

  private runSetSearch(query: string): void {
    this.active.set(query !== '');
    this.results.emit(query === '' ? null : { mode: 'sets', query });
  }

  private startWait(ms: number): void {
    if (this.signedIn()) {
      return;
    }
    const next = Date.now() + ms;
    this.now.set(Date.now());
    this.nextConceptSearchAt.set(next);
    try {
      localStorage.setItem(NEXT_SEARCH_KEY, String(next));
    } catch {
      // Without storage the wait is only shown until the page is reloaded; the server still applies it.
    }
  }
}

function readNextSearch(): number {
  try {
    return Number(localStorage.getItem(NEXT_SEARCH_KEY)) || 0;
  } catch {
    return 0;
  }
}
