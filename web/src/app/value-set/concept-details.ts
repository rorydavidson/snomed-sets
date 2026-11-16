import { Component, ElementRef, afterNextRender, inject, input, linkedSignal, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';
import { ConceptDetails as Details, ValueSetApi, describeError } from '../core/value-set-api';

type State = { status: 'loading' } | { status: 'ready'; concept: Details } | { status: 'error'; message: string };

/**
 * A dialog with everything Snowstorm holds about one concept. Parents and attribute
 * values can be clicked to look at those concepts in the same dialog.
 */
@Component({
  selector: 'app-concept-details',
  template: `
    <dialog #dialog class="dialog" aria-labelledby="concept-details-title" (close)="closed.emit()">
      <div class="dialog-head">
        <p class="overline">Concept {{ current() }}</p>
        <button type="button" class="button button-secondary" (click)="dialog.close()">Close</button>
      </div>

      @switch (state().status) {
        @case ('loading') {
          <h2 id="concept-details-title">Loading concept…</h2>
        }
        @case ('error') {
          <h2 id="concept-details-title">The concept could not be loaded</h2>
          <p role="alert">{{ message() }}</p>
        }
        @case ('ready') {
          @if (concept(); as concept) {
            <h2 id="concept-details-title">{{ concept.preferredTerm }}</h2>
            <p>{{ concept.fullySpecifiedName }}</p>
            <div class="card-heading">
              <span class="badge" [class.badge-success]="concept.active">{{ concept.active ? 'Active' : 'Inactive' }}</span>
              <span class="badge">{{ concept.definitionStatus === 'FULLY_DEFINED' ? 'Fully defined' : 'Primitive' }}</span>
            </div>
            <p class="meta">In {{ branch() }} · Module {{ concept.moduleId }} · Effective {{ concept.effectiveTime }}</p>

            <h3 class="overline">Descriptions</h3>
            <ul class="plain-list">
              @for (description of concept.descriptions; track description.term + description.language) {
                <li>
                  {{ description.term }}
                  <span class="meta">
                    {{ description.type === 'FSN' ? 'Fully specified name' : description.preferred ? 'Preferred synonym' : 'Synonym' }}
                    · {{ description.language }}
                  </span>
                </li>
              }
            </ul>

            <h3 class="overline">Parents</h3>
            @if (concept.parents.length === 0) {
              <p class="muted">None.</p>
            }
            <ul class="plain-list">
              @for (parent of concept.parents; track parent.targetId) {
                <li>
                  <button type="button" class="link-button" (click)="current.set(parent.targetId)">{{ parent.targetTerm }}</button>
                  <span class="meta mono">{{ parent.targetId }}</span>
                </li>
              }
            </ul>

            <h3 class="overline">Attributes</h3>
            @if (concept.attributes.length === 0) {
              <p class="muted">None.</p>
            }
            <ul class="plain-list">
              @for (attribute of concept.attributes; track attribute.typeTerm + attribute.targetId + attribute.group) {
                <li>
                  {{ attribute.typeTerm }} =
                  <button type="button" class="link-button" (click)="current.set(attribute.targetId)">{{ attribute.targetTerm }}</button>
                  <span class="meta">{{ attribute.group === 0 ? 'Not grouped' : 'Group ' + attribute.group }}</span>
                </li>
              }
            </ul>
          }
        }
      }
    </dialog>
  `,
})
export class ConceptDetails {
  private readonly api = inject(ValueSetApi);

  readonly conceptId = input.required<string>();
  readonly branch = input.required<string>();
  readonly closed = output<void>();

  /** Starts as the concept that was clicked; changes when a parent or attribute value is followed. */
  protected readonly current = linkedSignal(() => this.conceptId());
  protected readonly state = signal<State>({ status: 'loading' });

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    toObservable(this.current)
      .pipe(
        switchMap((conceptId) =>
          this.api.conceptDetails(conceptId, this.branch()).pipe(
            map((concept): State => ({ status: 'ready', concept })),
            catchError((error: unknown) => of<State>({ status: 'error', message: describeError(error) })),
            startWith<State>({ status: 'loading' }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((state) => this.state.set(state));

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

  protected concept(): Details | null {
    const state = this.state();
    return state.status === 'ready' ? state.concept : null;
  }

  protected message(): string {
    const state = this.state();
    return state.status === 'error' ? state.message : '';
  }
}
