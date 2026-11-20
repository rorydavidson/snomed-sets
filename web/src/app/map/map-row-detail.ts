import { Component, computed, input, output, signal } from '@angular/core';
import { Direction, MAX_TARGETS, MapRow, MapTarget, Relationship, RowUpdate, SourceRow, relationshipsFor } from '../core/map-api';
import { Concept } from '../core/value-set-api';
import { hasWords } from '../core/word-filter';
import { ConceptPicker } from '../value-set/concept-picker';

/** Snowstorm refuses shorter search terms, so the server does not look for them. */
const SHORTEST_SEARCH = 3;

/** How many local codes are listed to choose from at once. The filter narrows a longer list. */
const LOCAL_CODES_SHOWN = 50;

/** The words that differ between a map to SNOMED CT and a map from it. */
const WORDS = {
  TO_SNOMED: {
    target: 'concept',
    suggested: 'a description with the same words',
    noMap: 'nothing in SNOMED CT fits',
    remove: 'Remove this code',
  },
  FROM_SNOMED: {
    target: 'local code',
    suggested: 'one of the names of this concept as its term',
    noMap: 'none of the local codes fits',
    remove: 'Remove this concept',
  },
};

/**
 * The targets of one row of a map, and where more are chosen. It works out what a change does to
 * the row and hands that over to be saved; it saves nothing itself.
 */
@Component({
  selector: 'app-map-row-detail',
  imports: [ConceptPicker],
  template: `
    <div class="chosen">
      <h2><span class="mono">{{ row().code }}</span> {{ row().term }}</h2>
      @if (fromSnomed()) {
        <p class="meta">
          {{ row().fullySpecifiedName }}
          <button type="button" class="link-button" (click)="details.emit(row().code)">Details</button>
        </p>
      }
      @if (error(); as message) {
        <p class="field-error" role="alert">{{ message }}</p>
      }

      @if (row().status === 'SUGGESTED') {
        <p>
          {{ row().targets.length === 1 ? 'One ' + words().target + ' has' : row().targets.length + ' ' + words().target + 's have' }}
          {{ words().suggested }}. Nothing is mapped until you accept one.
        </p>
      } @else if (row().status === 'NO_MAP') {
        <p>Marked as having no map: {{ words().noMap }}.</p>
      } @else if (row().targets.length === 0) {
        <p class="muted">{{ unmappedMessage() }}</p>
      }

      @if (row().targets.length > 0) {
        <ul class="targets">
          @for (target of row().targets; track target.code) {
            <li>
              <div>
                <strong>{{ target.term }}</strong>
                <span class="meta mono">{{ target.code }}{{ target.fullySpecifiedName ? ' · ' + target.fullySpecifiedName : '' }}</span>
                @if (target.origin === 'EXACT_TERM') {
                  <span class="meta">Found by its term</span>
                }
              </div>
              <div class="actions">
                @if (row().status === 'SUGGESTED') {
                  <button type="button" class="button button-primary button-small" [disabled]="saving()" (click)="accept(target)">Accept</button>
                } @else {
                  <label class="visually-hidden" [attr.for]="'relationship-' + target.code">How {{ target.term }} compares</label>
                  <select [id]="'relationship-' + target.code" [disabled]="saving()" (change)="relate(target, $any($event.target).value)">
                    @for (option of relationships(); track option.value) {
                      <option [value]="option.value" [selected]="option.value === target.relationship">{{ option.label }}</option>
                    }
                  </select>
                  <button type="button" class="link-button" [disabled]="saving()" (click)="removeTarget(target)">Remove</button>
                }
                @if (!fromSnomed()) {
                  <button type="button" class="link-button" (click)="details.emit(target.code)">Details</button>
                }
              </div>
            </li>
          }
        </ul>
      }

      <div class="actions">
        @if (row().status === 'SUGGESTED') {
          <button type="button" class="button button-secondary button-small" [disabled]="saving()" (click)="clear()">Dismiss suggestions</button>
        }
        @if (row().status === 'NO_MAP') {
          <button type="button" class="button button-secondary button-small" [disabled]="saving()" (click)="clear()">Undo no map</button>
        } @else {
          <button type="button" class="button button-secondary button-small" [disabled]="saving()" (click)="update.emit(noMapUpdate(row()))">No map</button>
        }
        <button type="button" class="link-button" [disabled]="saving()" (click)="remove.emit()">{{ words().remove }}</button>
      </div>

      <div class="field">
        <label for="row-note">Note (optional)</label>
        <textarea id="row-note" rows="2" maxlength="500" [value]="row().note ?? ''" (change)="saveNote($any($event.target).value)"></textarea>
      </div>
    </div>

    @if (row().status !== 'NO_MAP') {
      <div class="choose">
        @if (fromSnomed()) {
          <h3>Choose a local code</h3>
          @if (localCodes().length === 0) {
            <p class="muted">This map has no local codes yet. Add some in the Local codes view.</p>
          } @else {
            <div class="field">
              <label for="local-filter">Find a local code or term</label>
              <input id="local-filter" type="text" autocomplete="off" [value]="localFilter()" (input)="localFilter.set($any($event.target).value)" />
            </div>
            <p class="meta" role="status">{{ localCodesLabel() }}</p>
            <ul class="pick-list">
              @for (local of localCodesShown(); track local.code) {
                <li>
                  <span>{{ local.term }} <span class="meta mono">{{ local.code }}</span></span>
                  @if (acceptedCodes().has(local.code)) {
                    <span class="badge">Chosen</span>
                  } @else {
                    <button type="button" class="button button-secondary button-small" [disabled]="saving()" (click)="addLocalTarget(local)"
                            [attr.aria-label]="'Add ' + local.code + ' ' + local.term">Add</button>
                  }
                </li>
              }
            </ul>
          }
        } @else {
          <h3>Choose a concept</h3>
          <app-concept-picker [branch]="branch()" [inList]="acceptedCodes()" (add)="addTargets($event)" (details)="details.emit($event)" />
        }
      </div>
    }
  `,
  styles: `
    /* The row's targets on the left, where more are chosen on the right. */
    :host {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-8);
      padding: var(--space-5);
      background: var(--surface-card);
      border: 1px solid var(--border-subtle);
      border-left: 4px solid var(--color-primary);
      border-radius: var(--radius-md);
    }

    :host > div {
      display: flex;
      flex: 1 1 360px;
      flex-direction: column;
      gap: var(--space-3);
      min-width: 0;
    }

    .targets {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .targets li {
      padding-bottom: var(--space-3);
      border-bottom: 1px solid var(--neutral-100);
    }

    .targets li > div:first-child {
      display: flex;
      flex-direction: column;
    }
  `,
})
export class MapRowDetail {
  readonly row = input.required<MapRow>();
  readonly direction = input.required<Direction>();
  /** The Snowstorm branch of the map's release. */
  readonly branch = input.required<string>();
  /** The codes targets are chosen from, in a map from SNOMED CT. */
  readonly localCodes = input.required<SourceRow[]>();
  /** True while a change to this row is being saved. */
  readonly saving = input(false);
  readonly error = input<string | null>(null);

  /** What the row should become. */
  readonly update = output<RowUpdate>();
  /** Asks for the row to be taken out of the map. */
  readonly remove = output<void>();
  /** Asks for a concept's details to be shown. */
  readonly details = output<string>();
  /** Why a change was not made, when it never got as far as being saved. */
  readonly refused = output<string>();

  protected readonly noMapUpdate = noMapUpdate;
  protected readonly fromSnomed = computed(() => this.direction() === 'FROM_SNOMED');
  protected readonly words = computed(() => WORDS[this.direction()]);
  protected readonly relationships = computed(() => relationshipsFor(this.direction()));
  /** The row's targets, unless they are only suggestions. */
  protected readonly acceptedCodes = computed<ReadonlySet<string>>(() => new Set(this.accepted().map((target) => target.code)));

  protected readonly localFilter = signal('');
  private readonly localCodesMatching = computed(() => this.localCodes().filter((local) => hasWords(`${local.code} ${local.term}`, this.localFilter())));
  protected readonly localCodesShown = computed(() => this.localCodesMatching().slice(0, LOCAL_CODES_SHOWN));
  protected readonly localCodesLabel = computed(() => {
    const found = this.localCodesMatching().length;
    return found > LOCAL_CODES_SHOWN ? `${found} local codes. Showing the first ${LOCAL_CODES_SHOWN}; type to narrow them.` : `${found} local ${found === 1 ? 'code' : 'codes'}.`;
  });

  /** Why an unmapped row has no suggestion. A term under three characters is never searched for. */
  protected readonly unmappedMessage = computed(() => {
    const row = this.row();
    if (this.fromSnomed()) {
      return row.termChecked ? 'No local code has one of the names of this concept as its term. Choose one from the list.' : 'Not mapped yet.';
    }
    if (row.term.trim().length < SHORTEST_SEARCH) {
      return 'This term is too short to look for automatically. Search below to choose a concept.';
    }
    return row.termChecked ? 'No concept has exactly this term. Search below to choose one.' : 'Not mapped yet.';
  });

  protected accept(target: MapTarget): void {
    this.update.emit(acceptUpdate(this.row(), target));
  }

  protected relate(target: MapTarget, relationship: Relationship): void {
    this.setTargets(this.row().targets.map((existing) => (existing.code === target.code ? { ...choice(existing), relationship } : choice(existing))));
  }

  protected removeTarget(target: MapTarget): void {
    this.setTargets(this.row().targets.filter((existing) => existing.code !== target.code).map(choice));
  }

  /** Adds concepts chosen in the search. Suggestions nobody accepted are not kept alongside them. */
  protected addTargets(concepts: Concept[]): void {
    const kept = this.accepted();
    const have = new Set(kept.map((target) => target.code));
    const added = concepts
      .filter((concept) => !have.has(concept.conceptId))
      .map((concept) => ({ code: concept.conceptId, relationship: 'EQUIVALENT' as Relationship }));
    if (kept.length + added.length > MAX_TARGETS) {
      this.refused.emit(`A code can have at most ${MAX_TARGETS} targets.`);
      return;
    }
    if (added.length > 0) {
      this.setTargets([...kept, ...added]);
    }
  }

  /** Adds a local code chosen from the list, in a map from SNOMED CT. */
  protected addLocalTarget(local: SourceRow): void {
    const kept = this.accepted();
    if (kept.length >= MAX_TARGETS) {
      this.refused.emit(`A concept can have at most ${MAX_TARGETS} targets.`);
      return;
    }
    this.setTargets([...kept, { code: local.code, relationship: 'EQUIVALENT' }]);
  }

  /** Back to unmapped: drops suggestions, or undoes "no map". */
  protected clear(): void {
    this.setTargets([]);
  }

  protected saveNote(note: string): void {
    const row = this.row();
    // A suggested row keeps its suggestions: writing a note does not accept or dismiss them.
    if (row.status === 'SUGGESTED') {
      this.refused.emit('Accept or dismiss the suggestions before adding a note.');
      return;
    }
    this.update.emit({ targets: row.targets.map(choice), noMap: row.status === 'NO_MAP', note: note.trim() || null });
  }

  /** The targets somebody chose. Suggestions nobody accepted are not among them. */
  private accepted(): RowUpdate['targets'] {
    return this.row().status === 'MAPPED' ? this.row().targets.map(choice) : [];
  }

  private setTargets(targets: RowUpdate['targets']): void {
    this.update.emit({ targets, noMap: false, note: this.row().note });
  }
}

/** Accepts one suggestion as the row's map, dropping any others. */
export function acceptUpdate(row: MapRow, target: MapTarget): RowUpdate {
  return { targets: [choice(target)], noMap: false, note: row.note };
}

/** Marks the row as having nothing to map to. */
export function noMapUpdate(row: MapRow): RowUpdate {
  return { targets: [], noMap: true, note: row.note };
}

function choice(target: MapTarget): { code: string; relationship: Relationship } {
  return { code: target.code, relationship: target.relationship };
}
