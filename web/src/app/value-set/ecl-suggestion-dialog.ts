import { Component, ElementRef, OnInit, afterNextRender, inject, input, output, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { EclPrefill, EclSuggestion, SavedEcl, ValueSetApi, describeError } from '../core/value-set-api';

type State =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; suggestion: EclSuggestion };

/**
 * Shows an ECL expression worked out to match a hand-picked list, how well it matches, and
 * offers to start a new ECL value set from it. The list itself is never changed.
 */
@Component({
  selector: 'app-ecl-suggestion-dialog',
  styles: `
    dialog {
      width: min(860px, calc(100vw - 2 * var(--space-4)));
      max-height: calc(100vh - 2 * var(--space-6));
      padding: 0;
      overflow: hidden;
      color: var(--text-body);
      background: var(--surface-card);
      border: 0;
      border-radius: var(--radius-md);
      box-shadow: 0 24px 64px rgb(0 56 101 / 35%);

      &::backdrop {
        background: rgb(0 56 101 / 55%);
      }
    }

    .frame {
      display: flex;
      flex-direction: column;
      max-height: calc(100vh - 2 * var(--space-6));
    }

    .head,
    .foot {
      flex: none;
      padding: var(--space-4) var(--space-6);
    }

    .head {
      border-bottom: 1px solid var(--border-subtle);
    }

    .foot {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      background: var(--surface-subtle);
      border-top: 1px solid var(--border-subtle);
    }

    .body {
      display: flex;
      flex: 1 1 auto;
      flex-direction: column;
      gap: var(--space-3);
      min-height: 0;
      padding: var(--space-4) var(--space-6);
      overflow-y: auto;
    }

    pre {
      max-height: 40vh;
      margin: 0;
      overflow: auto;
      white-space: pre-wrap;
    }

    ul {
      margin: 0;
      padding-left: var(--space-4);
    }
  `,
  template: `
    <dialog #dialog aria-labelledby="suggestion-title" (close)="closed.emit()">
      <div class="frame">
        <div class="head">
          <h2 id="suggestion-title">Suggest an ECL expression</h2>
          <p class="meta">An expression that matches this list, worked out from where its concepts sit in SNOMED CT.</p>
        </div>
        @switch (state().status) {
          @case ('loading') {
            <div class="body">
              <p class="muted" role="status">Working out an expression. A long list can take a minute.</p>
            </div>
          }
          @case ('error') {
            <div class="body">
              <div class="error-panel" role="alert">
                <h3>No expression could be worked out</h3>
                <p>{{ errorMessage() }}</p>
              </div>
            </div>
          }
          @case ('ready') {
            @if (suggestion(); as result) {
              <div class="body">
                <p role="status">
                  @if (result.exact) {
                    <strong>Matches all {{ result.conceptCount }} concepts in the list exactly.</strong>
                  } @else {
                    <strong>Not an exact match:</strong>
                    {{ result.missingCount }} of the list's concepts missing, {{ result.extraCount }} extra.
                  }
                  {{ shape(result) }}
                </p>
                @if (result.namedConcepts === result.conceptCount) {
                  <p class="meta">
                    No shorter expression was found, so this is the list written out. The concepts do not share enough of
                    the hierarchy to be described more briefly.
                  </p>
                }
                <pre class="ecl ecl-block">{{ result.ecl }}</pre>
                <p class="meta">
                  Worked out against {{ result.codeSystem }}, {{ result.codeSystemVersion ?? 'live branch' }}. A value set
                  defined by this expression follows it: when upgraded to a newer release, new concepts below these clauses
                  join it, which a hand-picked list never does.
                </p>
                @if (result.leftOut.length > 0) {
                  <p class="meta">
                    {{ result.leftOut.length }} inactive {{ result.leftOut.length === 1 ? 'concept is' : 'concepts are' }}
                    kept in the list. No ECL expression can match an inactive concept, so
                    {{ result.leftOut.length === 1 ? 'it is' : 'they are' }} left out.
                  </p>
                }
                @if (result.missing.length > 0) {
                  <h3>In the list but not matched</h3>
                  <ul>
                    @for (concept of result.missing; track concept.conceptId) {
                      <li>{{ concept.preferredTerm }} <span class="mono">{{ concept.conceptId }}</span></li>
                    }
                  </ul>
                }
                @if (result.extra.length > 0) {
                  <h3>Matched but not in the list</h3>
                  <ul>
                    @for (concept of result.extra; track concept.conceptId) {
                      <li>{{ concept.preferredTerm }} <span class="mono">{{ concept.conceptId }}</span></li>
                    }
                  </ul>
                }
                @if (!result.fitsDefinition) {
                  <p class="field-error" role="alert">
                    At {{ result.ecl.length }} characters this is longer than the {{ result.maxLength }} a definition can
                    hold, so it can be copied but not used for a new value set.
                  </p>
                }
                @if (copied()) {
                  <p class="meta" role="status">Copied.</p>
                }
                @if (saveState() === 'saved') {
                  <p class="meta" role="status">Kept with this value set. The hand-picked list is still its definition.</p>
                } @else if (saveError(); as message) {
                  <p class="field-error" role="alert">It could not be kept: {{ message }}</p>
                }
              </div>
            }
          }
        }
        <div class="foot">
          @if (suggestion(); as result) {
            <button type="button" class="button button-primary" [disabled]="!result.fitsDefinition" (click)="createFrom(result)">
              Create an ECL value set from this
            </button>
            <button type="button" class="button button-secondary" (click)="copy(result.ecl)">Copy expression</button>
            <button
              type="button"
              class="button button-secondary"
              [disabled]="!result.fitsDefinition || saveState() !== 'idle'"
              (click)="keep(result)"
            >
              {{ saveState() === 'saved' ? 'Kept with this value set' : 'Keep with this value set' }}
            </button>
          }
          <button type="button" class="button button-secondary" (click)="close()">Close</button>
        </div>
      </div>
    </dialog>
  `,
})
export class EclSuggestionDialog implements OnInit {
  private readonly api = inject(ValueSetApi);
  private readonly router = inject(Router);

  readonly valueSetId = input.required<number>();
  /** The list's name, used to name the new value set. */
  readonly name = input.required<string>();
  readonly closed = output<void>();
  /** The expression the owner chose to keep with the list, as the server checked it. */
  readonly saved = output<SavedEcl>();

  protected readonly state = signal<State>({ status: 'loading' });
  protected readonly copied = signal(false);
  protected readonly saveState = signal<'idle' | 'saving' | 'saved'>('idle');
  protected readonly saveError = signal<string | null>(null);

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
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

  ngOnInit(): void {
    this.api.eclSuggestion(this.valueSetId()).subscribe({
      next: (suggestion) => this.state.set({ status: 'ready', suggestion }),
      error: (error: unknown) => this.state.set({ status: 'error', message: describeError(error) }),
    });
  }

  protected suggestion(): EclSuggestion | null {
    const state = this.state();
    return state.status === 'ready' ? state.suggestion : null;
  }

  protected errorMessage(): string {
    const state = this.state();
    return state.status === 'error' ? state.message : '';
  }

  protected shape(result: EclSuggestion): string {
    const parts: string[] = [];
    if (result.structuredClauses > 0) {
      parts.push(`${result.structuredClauses} ${result.structuredClauses === 1 ? 'clause' : 'clauses'} covering part of the hierarchy`);
    }
    if (result.namedConcepts > 0) {
      parts.push(`${result.namedConcepts} ${result.namedConcepts === 1 ? 'concept' : 'concepts'} named on their own`);
    }
    return `Written as ${parts.join(' and ')}.`;
  }

  protected copy(ecl: string): void {
    void navigator.clipboard?.writeText(ecl).then(() => this.copied.set(true));
  }

  protected keep(result: EclSuggestion): void {
    this.saveState.set('saving');
    this.saveError.set(null);
    this.api.saveEcl(this.valueSetId(), result.ecl).subscribe({
      next: (saved) => {
        this.saveState.set('saved');
        this.saved.emit(saved);
      },
      error: (error: unknown) => {
        this.saveState.set('idle');
        this.saveError.set(describeError(error));
      },
    });
  }

  protected createFrom(result: EclSuggestion): void {
    const prefill: EclPrefill = {
      name: `${this.name()} (ECL)`.slice(0, 200),
      description: `Defined by ECL worked out from the hand-picked list "${this.name()}".`.slice(0, 2000),
      ecl: result.ecl,
      codeSystem: result.codeSystem,
      codeSystemVersion: result.codeSystemVersion,
    };
    this.close();
    void this.router.navigate(['/value-sets/new'], { state: { eclPrefill: prefill } });
  }

  protected close(): void {
    const dialog = this.dialog().nativeElement;
    if (typeof dialog.close === 'function') {
      dialog.close();
    } else {
      this.closed.emit();
    }
  }
}
