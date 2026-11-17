import { Component, ElementRef, afterNextRender, computed, inject, input, linkedSignal, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { catchError, forkJoin, map, of, startWith, switchMap } from 'rxjs';
import {
  Concept,
  ConceptDescription,
  ConceptDetails as Details,
  ConceptRelationship,
  ValueSetApi,
  describeError,
} from '../core/value-set-api';

/** How many kinds are listed before the rest are folded away. */
const CHILDREN_SHOWN = 10;
/** From this many kinds on, a filter box helps find one. */
const CHILD_FILTER_FROM = 12;

interface Loaded {
  concept: Details;
  /** Null when the children could not be loaded; the rest of the view still works. */
  children: { items: Concept[]; total: number } | null;
}

type State = { status: 'loading' } | ({ status: 'ready' } & Loaded) | { status: 'error'; message: string };
type Tab = 'overview' | 'definition' | 'descriptions';

interface RoleGroup {
  label: string;
  attributes: ConceptRelationship[];
}

/** The descriptions in one language, with the preferred terms picked out. */
interface LanguageGroup {
  code: string;
  name: string;
  /** True for the edition's own language, which is listed first and highlighted. */
  primary: boolean;
  preferred: ConceptDescription[];
  synonyms: ConceptDescription[];
  fsn: ConceptDescription[];
}

const DIALECT_LABELS: Record<string, string> = { 'en-US': 'US English', 'en-GB': 'GB English' };

/**
 * A dialog that shows one concept: its names in the edition's language first, where it sits in
 * the hierarchy as a compact outline, what defines it, and every description. It says whether
 * the concept comes from the International Edition or a national extension. Every related
 * concept can be clicked to move onto it, and the trail at the top leads back.
 */
@Component({
  selector: 'app-concept-details',
  styleUrl: './concept-details.scss',
  template: `
    <dialog #dialog class="concept-view" aria-labelledby="concept-view-title" (close)="closed.emit()">
      <header class="cv-head">
        <div class="cv-bar">
          <nav class="cv-trail" aria-label="Concepts you came through">
            @for (step of trail(); track $index) {
              <button type="button" class="cv-step" (click)="goBackTo($index)">{{ step.term }}</button>
              <span aria-hidden="true">›</span>
            }
          </nav>
          <button type="button" class="button button-on-dark" (click)="dialog.close()">Close</button>
        </div>

        @if (loaded(); as view) {
          <p class="cv-tag">{{ semanticTag() }}</p>
          <h2 id="concept-view-title">
            {{ view.concept.preferredTerm }}
            @if (view.concept.preferredTermLanguage && view.concept.preferredTermLanguage !== 'en') {
              <span class="cv-lang">{{ view.concept.preferredTermLanguage }}</span>
            }
          </h2>
          <p class="cv-fsn">{{ view.concept.fullySpecifiedName }}</p>
          <ul class="cv-facts" aria-label="About this concept">
            <li class="cv-origin" [class.cv-extension]="!view.concept.international">
              {{ view.concept.international ? 'International' : (view.concept.edition?.name ?? 'Extension') }}
              @if (view.concept.moduleName) {
                · {{ view.concept.moduleName }}
              }
            </li>
            <li>{{ view.concept.active ? 'Active' : 'Inactive' }}</li>
            <li>{{ fullyDefined() ? 'Fully defined' : 'Primitive' }}</li>
            <li class="mono">{{ view.concept.conceptId }}</li>
            @if (view.concept.effectiveTime) {
              <li>Effective {{ view.concept.effectiveTime }}</li>
            }
          </ul>
          <p class="cv-context">{{ contextLine() }}</p>
        } @else if (state().status === 'error') {
          <h2 id="concept-view-title">The concept could not be loaded</h2>
          <p role="alert">{{ errorMessage() }}</p>
        } @else {
          <h2 id="concept-view-title">Loading concept {{ current() }}…</h2>
        }
      </header>

      @if (loaded(); as view) {
        <div class="cv-tabs" role="tablist" aria-label="Concept details">
          <button type="button" role="tab" id="cv-tab-overview" [attr.aria-selected]="tab() === 'overview'" aria-controls="cv-panel" (click)="tab.set('overview')">
            Overview
          </button>
          <button type="button" role="tab" id="cv-tab-definition" [attr.aria-selected]="tab() === 'definition'" aria-controls="cv-panel" (click)="tab.set('definition')">
            Definition
          </button>
          <button type="button" role="tab" id="cv-tab-descriptions" [attr.aria-selected]="tab() === 'descriptions'" aria-controls="cv-panel" (click)="tab.set('descriptions')">
            Descriptions ({{ view.concept.descriptions.length }})
          </button>
        </div>

        <div class="cv-body" id="cv-panel" role="tabpanel" [attr.aria-labelledby]="'cv-tab-' + tab()">
          @switch (tab()) {
            @case ('overview') {
              <section class="cv-names" aria-labelledby="cv-names-title">
                <h3 id="cv-names-title" class="overline">What it is called</h3>
                @for (group of languageGroups(); track group.code) {
                  <div class="cv-language" [class.cv-primary]="group.primary">
                    <p class="cv-language-name">{{ group.name }} <span class="cv-lang">{{ group.code }}</span></p>
                    @for (description of group.preferred; track description.term) {
                      <p class="cv-preferred">
                        <span class="cv-star" aria-hidden="true">★</span>
                        <span class="cv-term">{{ description.term }}</span>
                        <span class="meta">preferred in {{ refsetList(description.preferredIn, view.concept) }}</span>
                      </p>
                    }
                    @if (group.synonyms.length > 0) {
                      <p class="cv-synonyms">
                        <span class="meta">Also:</span>
                        @for (description of group.synonyms; track description.term; let last = $last) {
                          {{ description.term }}{{ last ? '' : ',' }}
                        }
                      </p>
                    }
                  </div>
                }
              </section>

              <section class="cv-outline" aria-labelledby="cv-outline-title">
                <h3 id="cv-outline-title" class="overline">Where it sits</h3>
                @if (view.concept.parents.length === 0) {
                  <p class="cv-caption">A top-level concept: it is not a kind of anything else.</p>
                } @else {
                  <p class="cv-caption">Is a kind of ({{ view.concept.parents.length }})</p>
                  <ul class="cv-chips cv-above">
                    @for (parent of view.concept.parents; track parent.targetId) {
                      <li>
                        <button type="button" class="cv-chip" [title]="parent.targetId" (click)="goTo(parent.targetId)">
                          {{ parent.targetTerm }}
                        </button>
                      </li>
                    }
                  </ul>
                }

                <div class="cv-focus" [class.cv-defined]="fullyDefined()">
                  {{ view.concept.preferredTerm }}
                  <span class="mono">{{ view.concept.conceptId }}</span>
                </div>

                @if (view.children === null) {
                  <p class="muted" role="alert">Its kinds could not be loaded.</p>
                } @else if (view.children.total === 0) {
                  <p class="cv-caption">Has no kinds of its own</p>
                } @else {
                  <div class="cv-kinds-head">
                    <p class="cv-caption">Kinds of it ({{ view.children.total }})</p>
                    @if (view.children.items.length >= childFilterFrom) {
                      <label class="visually-hidden" for="cv-kind-filter">Filter its kinds</label>
                      <input
                        id="cv-kind-filter"
                        type="search"
                        placeholder="Filter…"
                        autocomplete="off"
                        [value]="childFilter()"
                        (input)="childFilter.set($any($event.target).value)"
                      />
                    }
                  </div>
                  <ul class="cv-kinds cv-below">
                    @for (child of shownChildren(); track child.conceptId) {
                      <li>
                        <button type="button" class="link-button" [title]="child.conceptId" (click)="goTo(child.conceptId)">
                          {{ child.preferredTerm }}
                        </button>
                      </li>
                    }
                  </ul>
                  @if (matchingChildren().length === 0) {
                    <p class="muted">No kinds match.</p>
                  }
                  @if (matchingChildren().length > childrenShown) {
                    <button type="button" class="link-button" (click)="showAllChildren.set(!showAllChildren())">
                      {{ showAllChildren() ? 'Show fewer' : 'Show all ' + matchingChildren().length }}
                    </button>
                  }
                  @if (view.children.items.length < view.children.total) {
                    <p class="cv-caption">The first {{ view.children.items.length }} are listed.</p>
                  }
                }
              </section>
            }
            @case ('definition') {
              <section class="cv-attributes" aria-labelledby="cv-attributes-title">
                <h3 id="cv-attributes-title" class="overline">What defines it</h3>
                @if (roleGroups().length === 0) {
                  <p class="muted">No attributes. It is defined only by what it is a kind of.</p>
                }
                @for (group of roleGroups(); track group.label) {
                  <div class="cv-group">
                    <p class="cv-group-label">{{ group.label }}</p>
                    @for (attribute of group.attributes; track attribute.typeId + attribute.targetId) {
                      <div class="cv-attribute">
                        <span class="cv-type">{{ attribute.typeTerm }}</span>
                        <span class="cv-arrow" aria-hidden="true"></span>
                        <button type="button" class="cv-chip cv-value" [title]="attribute.targetId" (click)="goTo(attribute.targetId)">
                          {{ attribute.targetTerm }}
                        </button>
                      </div>
                    }
                  </div>
                }
              </section>
              <section aria-labelledby="cv-expression-title">
                <h3 id="cv-expression-title" class="overline">As an expression</h3>
                <pre class="cv-expression">{{ expression() }}</pre>
                <button type="button" class="link-button" (click)="copyExpression()">
                  {{ copied() ? 'Copied' : 'Copy expression' }}
                </button>
              </section>
            }
            @case ('descriptions') {
              <section class="cv-descriptions" aria-labelledby="cv-descriptions-title">
                <h3 id="cv-descriptions-title" class="visually-hidden">Every description</h3>
                @for (group of languageGroups(); track group.code) {
                  <h4 class="cv-language-name">{{ group.name }} <span class="cv-lang">{{ group.code }}</span></h4>
                  <div class="table-scroll">
                    <table>
                      <thead>
                        <tr>
                          <th scope="col">Term</th>
                          <th scope="col">Use</th>
                          <th scope="col">Module</th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (description of ordered(group); track description.term + description.type) {
                          <tr [class.cv-preferred-row]="description.preferredIn.length > 0 && description.type !== 'FSN'">
                            <td>{{ description.term }}</td>
                            <td class="meta">{{ describeUse(description, view.concept) }}</td>
                            <td>
                              @if (description.moduleId) {
                                <span class="cv-module" [class.cv-extension]="!isInternational(description.moduleId, view.concept)" [title]="description.moduleId">
                                  {{ view.concept.names[description.moduleId] ?? description.moduleId }}
                                </span>
                              }
                            </td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  </div>
                }
              </section>
            }
          }
        </div>
      }
    </dialog>
  `,
})
export class ConceptDetails {
  private readonly api = inject(ValueSetApi);

  readonly conceptId = input.required<string>();
  readonly branch = input.required<string>();
  readonly closed = output<void>();

  /** Starts as the concept that was clicked; changes when a related concept is followed. */
  protected readonly current = linkedSignal(() => this.conceptId());
  /** The concepts passed through on the way to the current one, oldest first. */
  protected readonly trail = linkedSignal<string, { conceptId: string; term: string }[]>({
    source: this.conceptId,
    computation: () => [],
  });
  protected readonly state = signal<State>({ status: 'loading' });
  protected readonly tab = signal<Tab>('overview');
  protected readonly showAllChildren = signal(false);
  protected readonly childFilter = signal('');
  protected readonly copied = signal(false);
  protected readonly childrenShown = CHILDREN_SHOWN;
  protected readonly childFilterFrom = CHILD_FILTER_FROM;

  protected readonly loaded = computed<Loaded | null>(() => {
    const state = this.state();
    return state.status === 'ready' ? state : null;
  });
  protected readonly errorMessage = computed(() => {
    const state = this.state();
    return state.status === 'error' ? state.message : '';
  });
  protected readonly fullyDefined = computed(() => this.loaded()?.concept.definitionStatus === 'FULLY_DEFINED');

  /** The hierarchy name in brackets at the end of a fully specified name, for example "disorder". */
  protected readonly semanticTag = computed(() => {
    const match = /\(([^()]+)\)\s*$/.exec(this.loaded()?.concept.fullySpecifiedName ?? '');
    return match ? match[1] : 'concept';
  });

  /** Which edition and release the concept is shown from, and in which language. */
  protected readonly contextLine = computed(() => {
    const concept = this.loaded()?.concept;
    if (!concept) {
      return '';
    }
    const release = /(\d{4}-\d{2}-\d{2})$/.exec(this.branch())?.[1];
    const where = concept.edition?.name ?? this.branch();
    const when = release ? `, release ${release}` : ', live branch';
    const language = concept.edition && concept.edition.language !== 'en' ? `. ${this.languageName(concept.edition.language, concept)} names first` : '';
    return `Shown from ${where}${when}${language}.`;
  });

  /** Descriptions by language: the edition's own first, then English, then any others. */
  protected readonly languageGroups = computed<LanguageGroup[]>(() => {
    const concept = this.loaded()?.concept;
    if (!concept) {
      return [];
    }
    const primary = concept.edition?.language ?? 'en';
    const codes = [...new Set(concept.descriptions.map((description) => description.language))].sort((a, b) => {
      const rank = (code: string) => (code === primary ? 0 : code === 'en' ? 1 : 2);
      return rank(a) - rank(b) || a.localeCompare(b);
    });
    return codes.map((code) => {
      const inLanguage = concept.descriptions.filter((description) => description.language === code);
      const synonyms = inLanguage.filter((description) => description.type !== 'FSN');
      return {
        code,
        name: this.languageName(code, concept),
        primary: code === primary,
        fsn: inLanguage.filter((description) => description.type === 'FSN'),
        preferred: synonyms.filter((description) => description.preferredIn.length > 0),
        synonyms: synonyms.filter((description) => description.preferredIn.length === 0),
      };
    });
  });

  protected readonly matchingChildren = computed(() => {
    const words = this.childFilter().toLowerCase().split(/\s+/).filter((word) => word !== '');
    return (this.loaded()?.children?.items ?? []).filter((child) => {
      const searchable = `${child.preferredTerm ?? ''} ${child.conceptId}`.toLowerCase();
      return words.every((word) => searchable.includes(word));
    });
  });

  protected readonly shownChildren = computed(() => {
    const children = this.matchingChildren();
    return this.showAllChildren() ? children : children.slice(0, CHILDREN_SHOWN);
  });

  /** Attributes gathered by role group. Group 0 holds the ones that stand alone. */
  protected readonly roleGroups = computed<RoleGroup[]>(() => {
    const byGroup = new Map<number, ConceptRelationship[]>();
    for (const attribute of this.loaded()?.concept.attributes ?? []) {
      byGroup.set(attribute.group, [...(byGroup.get(attribute.group) ?? []), attribute]);
    }
    return [...byGroup.entries()]
      .sort(([a], [b]) => a - b)
      .map(([group, attributes]) => ({ label: group === 0 ? 'On their own' : `Group ${group}`, attributes }));
  });

  /** The inferred definition written as a SNOMED CT compositional expression. */
  protected readonly expression = computed(() => {
    const concept = this.loaded()?.concept;
    if (!concept) {
      return '';
    }
    const write = (id: string, term: string | null) => `${id} |${term ?? ''}|`;
    const parents = concept.parents.map((parent) => write(parent.targetId, parent.targetTerm)).join(' +\n    ');
    const refinements = this.roleGroups().map((group) => {
      const attributes = group.attributes
        .map((a) => `${write(a.typeId, a.typeTerm)} = ${write(a.targetId, a.targetTerm)}`)
        .join(',\n      ');
      return group.label === 'On their own' ? `    ${attributes.replaceAll('\n      ', '\n    ')}` : `    { ${attributes} }`;
    });
    const head = `${this.fullyDefined() ? '===' : '<<<'} ${parents}`;
    return refinements.length === 0 ? head : `${head} :\n${refinements.join(',\n')}`;
  });

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    toObservable(this.current)
      .pipe(
        switchMap((conceptId) =>
          forkJoin({
            concept: this.api.conceptDetails(conceptId, this.branch()),
            // The children are an addition: if they fail, the concept is still shown.
            children: this.api.children(conceptId, this.branch()).pipe(catchError(() => of(null))),
          }).pipe(
            map((loaded): State => ({ status: 'ready', ...loaded })),
            catchError((error: unknown) => of<State>({ status: 'error', message: describeError(error) })),
            startWith<State>({ status: 'loading' }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((state) => {
        this.state.set(state);
        this.showAllChildren.set(false);
        this.childFilter.set('');
        this.copied.set(false);
      });

    // showModal gives the focus trap and Escape handling of a real modal dialog.
    afterNextRender(() => {
      const dialog = this.dialog().nativeElement;
      if (typeof dialog.showModal === 'function') {
        dialog.showModal();
      } else {
        dialog.setAttribute('open', '');
      }
    });
  }

  protected goTo(conceptId: string): void {
    const from = this.loaded()?.concept;
    if (from && from.conceptId !== conceptId) {
      this.trail.set([...this.trail(), { conceptId: from.conceptId, term: from.preferredTerm ?? from.conceptId }]);
    }
    this.current.set(conceptId);
  }

  protected goBackTo(index: number): void {
    const step = this.trail()[index];
    this.trail.set(this.trail().slice(0, index));
    this.current.set(step.conceptId);
  }

  /** Snowstorm's name for a language where the edition gives one, otherwise the browser's. */
  protected languageName(code: string, concept: Details): string {
    const known = concept.edition?.languages[code];
    if (known) {
      return known;
    }
    try {
      return new Intl.DisplayNames(['en'], { type: 'language' }).of(code) ?? code;
    } catch {
      return code;
    }
  }

  /** "US English, GB English", or the language reference sets' names without the common suffix. */
  protected refsetList(refsets: string[], concept: Details): string {
    return refsets
      .map((refset) => DIALECT_LABELS[refset] ?? (concept.names[refset] ?? refset).replace(/ language reference set/i, ''))
      .join(', ');
  }

  protected describeUse(description: ConceptDescription, concept: Details): string {
    const kind = description.type === 'FSN' ? 'Fully specified name' : 'Synonym';
    const parts = [kind];
    if (description.preferredIn.length > 0) {
      parts.push(`preferred in ${this.refsetList(description.preferredIn, concept)}`);
    }
    if (description.acceptableIn.length > 0) {
      parts.push(`acceptable in ${this.refsetList(description.acceptableIn, concept)}`);
    }
    return parts.join(' · ');
  }

  /** Fully specified name first, then preferred terms, then the other synonyms. */
  protected ordered(group: LanguageGroup): ConceptDescription[] {
    return [...group.fsn, ...group.preferred, ...group.synonyms];
  }

  protected isInternational(moduleId: string, concept: Details): boolean {
    return concept.internationalModules.includes(moduleId);
  }

  protected copyExpression(): void {
    void navigator.clipboard?.writeText(this.expression()).then(() => this.copied.set(true));
  }
}
