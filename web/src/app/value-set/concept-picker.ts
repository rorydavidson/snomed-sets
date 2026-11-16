import { Component, computed, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, catchError, debounceTime, distinctUntilChanged, forkJoin, map, of, startWith, switchMap } from 'rxjs';
import { Concept, ExpansionPage, ValueSetApi, describeError } from '../core/value-set-api';

const SEARCH_DELAY_MS = 400;
const MIN_TERM_LENGTH = 3;

type Search =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; page: ExpansionPage }
  | { status: 'error'; message: string };

/**
 * Finds concepts by text and hands the chosen ones to the page that owns the list.
 * Several results can be ticked and added together, or all of their children added instead.
 */
@Component({
  selector: 'app-concept-picker',
  template: `
    <div class="field">
      <label for="concept-search">Search for concepts</label>
      <input id="concept-search" type="text" autocomplete="off" maxlength="200" (input)="onTerm($any($event.target).value)" />
      <p class="meta">Type at least three characters. Results come from {{ branch() }}.</p>
    </div>

    @switch (search().status) {
      @case ('loading') {
        <p class="muted" role="status">Searching…</p>
      }
      @case ('error') {
        <p class="field-error" role="alert">{{ errorMessage() }}</p>
      }
      @case ('ready') {
        @if (results().length === 0) {
          <p class="muted" role="status">No concepts match.</p>
        } @else {
          <p class="meta" role="status">{{ resultsLabel() }}</p>
          <div class="actions">
            <button type="button" class="button button-primary" [disabled]="selected().size === 0" (click)="addSelected()">
              Add selected ({{ selected().size }})
            </button>
            <button type="button" class="button button-secondary" [disabled]="selected().size === 0 || addingChildren()" (click)="addChildrenOfSelected()">
              {{ addingChildren() ? 'Finding children…' : 'Add children of selected' }}
            </button>
            <button type="button" class="button button-secondary" (click)="toggleAll()">
              {{ selected().size === results().length ? 'Clear selection' : 'Select all' }}
            </button>
          </div>
          <ul class="pick-list">
            @for (concept of results(); track concept.conceptId) {
              <li>
                <label class="checkbox">
                  <input type="checkbox" [checked]="selected().has(concept.conceptId)" (change)="toggle(concept.conceptId)" />
                  <span>
                    <span>{{ concept.preferredTerm }}</span>
                    <span class="meta mono">{{ concept.conceptId }} · {{ concept.fullySpecifiedName }}</span>
                  </span>
                </label>
                @if (inList().has(concept.conceptId)) {
                  <span class="badge">In the list</span>
                }
                <button type="button" class="link-button" (click)="details.emit(concept.conceptId)" [attr.aria-label]="'Details of ' + concept.preferredTerm">
                  Details
                </button>
              </li>
            }
          </ul>
        }
      }
    }

    @if (childrenMessage(); as message) {
      <p class="meta" role="status">{{ message }}</p>
    }
    @if (childrenError(); as message) {
      <p class="field-error" role="alert">{{ message }}</p>
    }
  `,
})
export class ConceptPicker {
  private readonly api = inject(ValueSetApi);

  readonly branch = input.required<string>();
  /** Concept IDs already in the value set, so results can be marked. */
  readonly inList = input.required<ReadonlySet<string>>();
  readonly add = output<Concept[]>();
  /** Asks the page to show everything about one concept. */
  readonly details = output<string>();

  protected readonly search = signal<Search>({ status: 'idle' });
  protected readonly selected = signal<ReadonlySet<string>>(new Set());
  protected readonly addingChildren = signal(false);
  protected readonly childrenMessage = signal<string | null>(null);
  protected readonly childrenError = signal<string | null>(null);

  protected readonly results = computed(() => {
    const search = this.search();
    return search.status === 'ready' ? search.page.items : [];
  });
  protected readonly resultsLabel = computed(() => {
    const search = this.search();
    if (search.status !== 'ready') {
      return '';
    }
    const { total, items } = search.page;
    return total > items.length ? `${total} matches. Showing the first ${items.length}.` : `${total} matches.`;
  });
  protected readonly errorMessage = computed(() => {
    const search = this.search();
    return search.status === 'error' ? search.message : '';
  });

  private readonly terms = new Subject<string>();

  constructor() {
    // Wait for a pause in typing, and drop the answer to an earlier term if a newer one is pending.
    this.terms
      .pipe(
        map((term) => term.trim()),
        debounceTime(SEARCH_DELAY_MS),
        distinctUntilChanged(),
        switchMap((term) => (term.length < MIN_TERM_LENGTH ? of<Search>({ status: 'idle' }) : this.find(term))),
        takeUntilDestroyed(),
      )
      .subscribe((search) => {
        this.search.set(search);
        this.selected.set(new Set());
      });
  }

  protected onTerm(term: string): void {
    this.terms.next(term);
  }

  protected toggle(conceptId: string): void {
    const next = new Set(this.selected());
    if (!next.delete(conceptId)) {
      next.add(conceptId);
    }
    this.selected.set(next);
  }

  protected toggleAll(): void {
    const everything = this.results().map((concept) => concept.conceptId);
    this.selected.set(this.selected().size === everything.length ? new Set() : new Set(everything));
  }

  protected addSelected(): void {
    this.clearChildrenMessages();
    this.add.emit(this.selectedConcepts());
    this.selected.set(new Set());
  }

  protected addChildrenOfSelected(): void {
    const parents = this.selectedConcepts();
    this.clearChildrenMessages();
    this.addingChildren.set(true);
    forkJoin(parents.map((parent) => this.api.children(parent.conceptId, this.branch()))).subscribe({
      next: (answers) => {
        this.addingChildren.set(false);
        this.selected.set(new Set());
        const children = answers.flatMap((answer) => answer.items);
        this.add.emit(children);
        this.childrenMessage.set(this.describeChildren(parents, answers, children.length));
      },
      error: (error: unknown) => {
        this.addingChildren.set(false);
        this.childrenError.set(describeError(error));
      },
    });
  }

  private describeChildren(parents: Concept[], answers: { items: Concept[]; total: number }[], found: number): string {
    if (found === 0) {
      return 'The selected concepts have no children.';
    }
    const cutShort = parents.filter((_, index) => answers[index].total > answers[index].items.length);
    const summary = `Found ${found} children of ${parents.length} ${parents.length === 1 ? 'concept' : 'concepts'}.`;
    return cutShort.length === 0
      ? summary
      : `${summary} Not all children of ${cutShort.map((parent) => parent.preferredTerm).join(', ')} were included, because there are too many.`;
  }

  private selectedConcepts(): Concept[] {
    return this.results().filter((concept) => this.selected().has(concept.conceptId));
  }

  private clearChildrenMessages(): void {
    this.childrenMessage.set(null);
    this.childrenError.set(null);
  }

  private find(term: string): Observable<Search> {
    return this.api.searchConcepts(term, this.branch()).pipe(
      map((page): Search => ({ status: 'ready', page })),
      catchError((error: unknown) => of<Search>({ status: 'error', message: describeError(error) })),
      startWith<Search>({ status: 'loading' }),
    );
  }
}
