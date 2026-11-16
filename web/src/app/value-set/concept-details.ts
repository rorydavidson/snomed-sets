import { Component, ElementRef, afterNextRender, computed, inject, input, linkedSignal, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { catchError, forkJoin, map, of, startWith, switchMap } from 'rxjs';
import { Concept, ConceptDetails as Details, ConceptRelationship, ValueSetApi, describeError } from '../core/value-set-api';

/** How many children are shown before the rest are folded away. */
const CHILDREN_SHOWN = 12;

interface Loaded {
  concept: Details;
  /** Null when the children could not be loaded; the rest of the view still works. */
  children: { items: Concept[]; total: number } | null;
}

type State = { status: 'loading' } | ({ status: 'ready' } & Loaded) | { status: 'error'; message: string };

interface RoleGroup {
  label: string;
  attributes: ConceptRelationship[];
}

/**
 * A dialog that shows one concept as a map of its relationships: what it is a kind of
 * above it, its own kinds below it, and its defining attributes beside it in their role
 * groups. Every related concept can be clicked to move the map onto it, and the trail
 * at the top leads back.
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
          <h2 id="concept-view-title">{{ view.concept.preferredTerm }}</h2>
          <p class="cv-fsn">{{ view.concept.fullySpecifiedName }}</p>
          <ul class="cv-facts">
            <li class="mono">{{ view.concept.conceptId }}</li>
            <li>{{ view.concept.active ? 'Active' : 'Inactive' }}</li>
            <li>{{ fullyDefined() ? 'Fully defined' : 'Primitive' }}</li>
            <li>Module {{ view.concept.moduleId }}</li>
            <li>Effective {{ view.concept.effectiveTime }}</li>
            <li>{{ branch() }}</li>
          </ul>
        } @else if (state().status === 'error') {
          <h2 id="concept-view-title">The concept could not be loaded</h2>
          <p role="alert">{{ errorMessage() }}</p>
        } @else {
          <h2 id="concept-view-title">Loading concept {{ current() }}…</h2>
        }
      </header>

      @if (loaded(); as view) {
        <div class="cv-body">
          <section class="cv-map" aria-labelledby="cv-map-title">
            <h3 id="cv-map-title" class="overline">Where it sits</h3>

            <p class="cv-caption">Is a kind of</p>
            @if (view.concept.parents.length === 0) {
              <p class="muted">Nothing: this is a top-level concept.</p>
            } @else {
              <ul class="cv-nodes cv-above">
                @for (parent of view.concept.parents; track parent.targetId) {
                  <li>
                    <button type="button" class="cv-node" (click)="goTo(parent.targetId)">
                      {{ parent.targetTerm }}
                      <span class="mono">{{ parent.targetId }}</span>
                    </button>
                  </li>
                }
              </ul>
              <div class="cv-stem" aria-hidden="true"></div>
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
              <div class="cv-stem" aria-hidden="true"></div>
              <ul class="cv-nodes cv-below">
                @for (child of shownChildren(); track child.conceptId) {
                  <li>
                    <button type="button" class="cv-node" (click)="goTo(child.conceptId)">
                      {{ child.preferredTerm }}
                      <span class="mono">{{ child.conceptId }}</span>
                    </button>
                  </li>
                }
              </ul>
              <p class="cv-caption">
                {{ view.children.total }} {{ view.children.total === 1 ? 'kind' : 'kinds' }} of this
                @if (view.children.items.length > childrenShown) {
                  ·
                  <button type="button" class="link-button" (click)="showAllChildren.set(!showAllChildren())">
                    {{ showAllChildren() ? 'Show fewer' : 'Show all ' + view.children.items.length }}
                  </button>
                }
              </p>
            }
          </section>

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
                    <button type="button" class="cv-node cv-value" (click)="goTo(attribute.targetId)">
                      {{ attribute.targetTerm }}
                      <span class="mono">{{ attribute.targetId }}</span>
                    </button>
                  </div>
                }
              </div>
            }

            <h3 class="overline">As an expression</h3>
            <pre class="cv-expression">{{ expression() }}</pre>
            <button type="button" class="link-button" (click)="copyExpression()">
              {{ copied() ? 'Copied' : 'Copy expression' }}
            </button>
          </section>

          <section class="cv-descriptions" aria-labelledby="cv-descriptions-title">
            <h3 id="cv-descriptions-title" class="overline">What it is called</h3>
            <ul>
              @for (description of view.concept.descriptions; track description.term + description.language) {
                <li>
                  <span>{{ description.term }}<span class="lang">{{ description.language }}</span></span>
                  <span class="meta">{{ describeUse(description) }}</span>
                </li>
              }
            </ul>
          </section>
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
  protected readonly showAllChildren = signal(false);
  protected readonly copied = signal(false);
  protected readonly childrenShown = CHILDREN_SHOWN;

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

  protected readonly shownChildren = computed(() => {
    const children = this.loaded()?.children?.items ?? [];
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

  protected describeUse(description: { type: string; preferredIn: string[] }): string {
    const kind = description.type === 'FSN' ? 'Fully specified name' : 'Synonym';
    return description.preferredIn.length === 0 ? kind : `${kind} · preferred in ${description.preferredIn.join(', ')}`;
  }

  protected copyExpression(): void {
    void navigator.clipboard?.writeText(this.expression()).then(() => this.copied.set(true));
  }
}
