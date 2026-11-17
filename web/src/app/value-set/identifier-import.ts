import { Component, ElementRef, afterNextRender, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { Concept, IdentifierCheck, IdentifierRejection, ValueSetApi, describeError } from '../core/value-set-api';
import { ParsedList, entriesOf, parseList } from './identifier-list';
import { humanise } from './upgrade-dialog';

/** The server checks at most this many identifiers in one go. */
const MAX_ENTRIES = 5000;
/** Big enough for any list of 5000 identifiers with terms; anything larger is probably the wrong file. */
const MAX_FILE_BYTES = 5 * 1024 * 1024;
/** Long lists are cut short on screen; the downloaded results always have every line. */
const ROWS_SHOWN = 300;

type Inactive = IdentifierCheck['inactive'][number];

/** Which concepts found the review shows. */
type FoundFilter = 'all' | 'new' | 'inList' | 'notes';

type State =
  | { status: 'input'; message: string | null }
  | { status: 'checking' }
  | { status: 'review'; check: IdentifierCheck };

const REJECTIONS: Record<IdentifierRejection, string> = {
  NOT_AN_IDENTIFIER: 'Not a SNOMED CT identifier',
  INVALID_CHECK_DIGIT: 'The check digit is wrong, so a digit is probably mistyped',
  NOT_A_CONCEPT: 'Identifies a relationship or other component, not a concept',
  NOT_IN_RELEASE: 'Not in this release, for example a concept from another edition',
};

/**
 * Imports concepts into a hand-picked list, from a pasted or uploaded list of identifiers or
 * from what an ECL expression matches today. A list is read in the browser, everything is
 * checked on the server against the release, and nothing is added until the person has
 * reviewed what was found.
 */
@Component({
  selector: 'app-identifier-import',
  styles: `
    dialog {
      width: min(880px, calc(100vw - 2 * var(--space-4)));
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

    textarea {
      width: 100%;
    }

    .columns {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-3);
    }

    .stats {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-1) var(--space-4);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .review {
      max-height: 40vh;
      overflow: auto;
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-sm);

      table {
        width: 100%;
        font-size: var(--fs-body-sm);
        border-collapse: collapse;
      }

      th {
        position: sticky;
        top: 0;
        background: var(--surface-subtle);
      }

      th,
      td {
        padding: var(--space-1) var(--space-2);
        text-align: left;
        vertical-align: top;
        border-top: 1px solid var(--border-subtle);
      }

      label {
        display: flex;
        gap: var(--space-2);
        cursor: pointer;
      }
    }

    .sub {
      display: block;
      font-size: var(--fs-caption);
      color: var(--text-muted);
    }

    h3 {
      margin: 0;
    }
  `,
  template: `
    <dialog #dialog aria-labelledby="import-title" (close)="closed.emit()">
      <div class="frame">
        <div class="head">
          <h2 id="import-title">Import concepts</h2>
          <p class="meta">Checked against {{ branch() }}</p>
        </div>

        @switch (state().status) {
          @case ('review') {
            @if (check(); as result) {
              <div class="body">
                <ul class="stats" role="status">
                  <li><strong>{{ result.found.length }}</strong> found</li>
                  @if (inList().size > 0) {
                    <li><strong>{{ alreadyIn() }}</strong> already in the list</li>
                  }
                  @if (source() === 'identifiers') {
                    <li><strong>{{ result.inactive.length }}</strong> inactive</li>
                    <li><strong>{{ result.rejected.length }}</strong> not recognised</li>
                  }
                  @if (result.duplicateCount > 0) {
                    <li><strong>{{ result.duplicateCount }}</strong> listed twice</li>
                  }
                </ul>
                @if (result.found.length > 0 && alreadyIn() === result.found.length) {
                  <p class="meta" role="status">Everything found is already in the list.</p>
                }
                @if (overCapacity()) {
                  <p class="field-error" role="alert">
                    Adding these would take the list over {{ maxMembers() }} concepts. Remove some from the file and
                    check it again.
                  </p>
                }

                @if (result.inactive.length > 0) {
                  <h3>Inactive concepts</h3>
                  <p class="meta">
                    Inactive concepts are not added. Tick the replacements to add in their place; all suggested
                    replacements start ticked.
                  </p>
                  <div class="actions">
                    <button type="button" class="button button-secondary button-small" (click)="setAll(true)">Add all replacements</button>
                    <button type="button" class="button button-secondary button-small" (click)="setAll(false)">Add no replacements</button>
                  </div>
                  <div class="review">
                    <table>
                      <thead>
                        <tr>
                          <th scope="col">Inactive concept</th>
                          <th scope="col">Add as replacement?</th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (concept of shown(result.inactive); track concept.conceptId) {
                          <tr>
                            <td>
                              {{ concept.term ?? concept.conceptId }}
                              <span class="sub mono">{{ concept.conceptId }}</span>
                              <span class="sub">{{ humanise(concept.reason) }}</span>
                            </td>
                            <td>
                              @for (replacement of concept.replacements; track replacement.conceptId) {
                                @if (isAlreadyIn(replacement.conceptId)) {
                                  <span class="sub">{{ replacement.term }} ({{ replacement.conceptId }}) is already in the list</span>
                                } @else {
                                  <label>
                                    <input
                                      type="checkbox"
                                      [checked]="accepted().has(replacement.conceptId)"
                                      (change)="toggle(replacement.conceptId)"
                                    />
                                    <span>
                                      {{ replacement.term }}
                                      <span class="sub">{{ replacement.conceptId }} · {{ humanise(replacement.association) }}</span>
                                    </span>
                                  </label>
                                }
                              } @empty {
                                <span class="muted">No replacement suggested</span>
                              }
                            </td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  </div>
                  @if (result.inactive.length > rowsShown) {
                    <p class="meta">Showing {{ rowsShown }} of {{ result.inactive.length }}. The buttons above cover them all.</p>
                  }
                }

                @if (result.rejected.length > 0) {
                  <h3>Not recognised</h3>
                  <div class="review">
                    <table>
                      <thead>
                        <tr>
                          <th scope="col">Identifier</th>
                          <th scope="col">Why it is not added</th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (rejected of shown(result.rejected); track $index) {
                          <tr>
                            <td class="mono">{{ rejected.identifier }}</td>
                            <td>{{ rejectionText(rejected.reason) }}</td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  </div>
                }

                @if (result.found.length > 0) {
                  <details [open]="(notes() > 0 || alreadyIn() > 0) && result.found.length <= 50">
                    <summary>
                      <strong>{{ result.found.length }} found</strong>: {{ foundSummary() }}
                    </summary>
                    <div class="field">
                      <label for="found-filter">Show</label>
                      <select id="found-filter" [value]="foundFilter()" (change)="useFoundFilter($event)">
                        <option value="all">All found</option>
                        <option value="new">New to the list</option>
                        <option value="inList">Already in the list</option>
                        <option value="notes">To look at: a different term, or a description identifier</option>
                      </select>
                    </div>
                    <div class="review">
                      <table>
                        <thead>
                          <tr>
                            <th scope="col">Concept</th>
                            <th scope="col">Note</th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (concept of shown(foundShown()); track concept.conceptId) {
                            <tr>
                              <td>
                                {{ concept.preferredTerm }}
                                <span class="sub mono">{{ concept.conceptId }}</span>
                              </td>
                              <td>
                                @if (concept.termDiffers) {
                                  <span class="sub">The list says "{{ concept.suppliedTerm }}"</span>
                                }
                                @if (concept.descriptionId) {
                                  <span class="sub">From description {{ concept.descriptionId }}</span>
                                }
                                @if (isAlreadyIn(concept.conceptId)) {
                                  <span class="sub">Already in the list</span>
                                }
                              </td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    </div>
                  </details>
                }
              </div>
              <div class="foot">
                <button type="button" class="button button-primary" [disabled]="toAdd().length === 0 || overCapacity()" (click)="add()">
                  Add {{ toAdd().length }} new {{ toAdd().length === 1 ? 'concept' : 'concepts' }}
                </button>
                <button type="button" class="button button-secondary" (click)="downloadResults()">Download results (CSV)</button>
                <button type="button" class="button button-secondary" (click)="state.set({ status: 'input', message: null })">
                  Back to the list
                </button>
                <button type="button" class="button button-secondary" (click)="close()">Cancel</button>
              </div>
            }
          }
          @default {
            <div class="body">
              @if (inList().size > 0) {
                <p class="meta">
                  The list has {{ inList().size }} {{ inList().size === 1 ? 'concept' : 'concepts' }}. Any already in it
                  are pointed out and not added again.
                </p>
              }
              <fieldset class="field">
                <legend>Import from</legend>
                <label class="checkbox">
                  <input type="radio" name="import-source" value="identifiers" [checked]="source() === 'identifiers'" (change)="source.set('identifiers')" />
                  <span>A list of identifiers</span>
                </label>
                <label class="checkbox">
                  <input type="radio" name="import-source" value="ecl" [checked]="source() === 'ecl'" (change)="source.set('ecl')" />
                  <span>An ECL expression</span>
                </label>
              </fieldset>
              @if (source() === 'ecl') {
                <p>
                  Every concept the expression matches in this release is added as it is today. The list does not follow
                  the expression afterwards; for that, create a value set defined by ECL. Up to {{ maxMembers() }} concepts.
                </p>
                <div class="field">
                  <label for="import-ecl">ECL expression</label>
                  <textarea id="import-ecl" class="mono" rows="4" maxlength="4000" spellcheck="false" [value]="ecl()" (input)="useEcl($event)"></textarea>
                </div>
              } @else {
              <p>
                Paste identifiers below or choose a file: plain text, CSV or TSV (such as this app's downloads), or a FHIR
                ValueSet. The file is read in your browser and only the identifiers, and terms if you pick a term column,
                are sent to be checked.
              </p>
              <div class="field">
                <label for="import-file">File</label>
                <input id="import-file" type="file" accept=".txt,.csv,.tsv,.json,text/plain,text/csv,application/json" (change)="readFile($event)" />
              </div>
              <div class="field">
                <label for="import-text">Identifiers</label>
                <textarea id="import-text" class="mono" rows="8" spellcheck="false" [value]="text()" (input)="useText($event)"></textarea>
              </div>
              @if (parsed(); as list) {
                @if (list.columns.length > 1) {
                  <div class="columns">
                    <div class="field">
                      <label for="id-column">Identifier column</label>
                      <select id="id-column" [value]="idColumn()" (change)="idColumn.set(+$any($event.target).value)">
                        @for (name of list.columns; track $index) {
                          <option [value]="$index">{{ name }}</option>
                        }
                      </select>
                    </div>
                    <div class="field">
                      <label for="term-column">Term column</label>
                      <select id="term-column" [value]="termColumn() ?? -1" (change)="useTermColumn($event)">
                        <option value="-1">None</option>
                        @for (name of list.columns; track $index) {
                          <option [value]="$index">{{ name }}</option>
                        }
                      </select>
                    </div>
                  </div>
                  <p class="meta">A term column lets the check point out terms that differ from SNOMED CT's.</p>
                }
                <p class="meta" role="status">
                  {{ entries().length }} {{ entries().length === 1 ? 'identifier' : 'identifiers' }} read{{ list.hasHeader ? ', after a header row' : '' }}.
                </p>
              }
              @if (tooMany()) {
                <p class="field-error" role="alert">Up to {{ maxEntries }} identifiers can be checked at once.</p>
              }
              }
              @if (inputMessage(); as message) {
                <div class="error-panel" role="alert">
                  <p>{{ message }}</p>
                </div>
              }
            </div>
            <div class="foot">
              <button
                type="button"
                class="button button-primary"
                [disabled]="!canCheck() || state().status === 'checking'"
                (click)="runCheck()"
              >
                {{ state().status === 'checking' ? 'Checking…' : source() === 'ecl' ? 'Find concepts' : 'Check identifiers' }}
              </button>
              <button type="button" class="button button-secondary" (click)="close()">Cancel</button>
            </div>
          }
        }
      </div>
    </dialog>
  `,
})
export class IdentifierImport {
  private readonly api = inject(ValueSetApi);

  readonly branch = input.required<string>();
  /** Concepts already in the list, which are not added again. */
  readonly inList = input<ReadonlySet<string>>(new Set());
  readonly maxMembers = input(2000);
  readonly added = output<Concept[]>();
  readonly closed = output<void>();

  protected readonly maxEntries = MAX_ENTRIES;
  protected readonly rowsShown = ROWS_SHOWN;
  protected readonly humanise = humanise;
  protected readonly state = signal<State>({ status: 'input', message: null });
  protected readonly source = signal<'identifiers' | 'ecl'>('identifiers');
  protected readonly text = signal('');
  protected readonly ecl = signal('');
  protected readonly idColumn = signal(0);
  protected readonly termColumn = signal<number | null>(null);
  /** Replacement concepts the person has chosen to add. */
  protected readonly accepted = signal<ReadonlySet<string>>(new Set());
  protected readonly foundFilter = signal<FoundFilter>('all');

  protected readonly parsed = computed<ParsedList | null>(() => {
    const list = parseList(this.text());
    return list.rows.length === 0 ? null : list;
  });
  protected readonly entries = computed(() => {
    const list = this.parsed();
    return list === null ? [] : entriesOf(list, this.idColumn(), this.termColumn());
  });
  protected readonly tooMany = computed(() => this.entries().length > MAX_ENTRIES);
  protected readonly canCheck = computed(() =>
    this.source() === 'ecl' ? this.ecl().trim() !== '' : this.entries().length > 0 && !this.tooMany(),
  );
  protected readonly inputMessage = computed(() => {
    const state = this.state();
    return state.status === 'input' ? state.message : null;
  });
  protected readonly check = computed(() => {
    const state = this.state();
    return state.status === 'review' ? state.check : null;
  });
  /** Found concepts with something worth a second look. */
  protected readonly notes = computed(
    () => this.check()?.found.filter((concept) => concept.termDiffers || concept.descriptionId).length ?? 0,
  );
  protected readonly foundSummary = computed(() => {
    const total = this.check()?.found.length ?? 0;
    const parts = [`${total - this.alreadyIn()} new`];
    if (this.alreadyIn() > 0) {
      parts.push(`${this.alreadyIn()} already in the list`);
    }
    if (this.notes() > 0) {
      parts.push(`${this.notes()} to look at`);
    }
    return parts.join(', ');
  });
  /** Found concepts the list already has, which adding leaves alone. */
  protected readonly alreadyIn = computed(
    () => this.check()?.found.filter((concept) => this.inList().has(concept.conceptId)).length ?? 0,
  );
  protected readonly foundShown = computed(() => {
    const found = this.check()?.found ?? [];
    switch (this.foundFilter()) {
      case 'new':
        return found.filter((concept) => !this.inList().has(concept.conceptId));
      case 'inList':
        return found.filter((concept) => this.inList().has(concept.conceptId));
      case 'notes':
        return found.filter((concept) => concept.termDiffers || concept.descriptionId);
      default:
        return found;
    }
  });
  /** The concepts that adding would put in the list: those found, then chosen replacements, none already there. */
  protected readonly toAdd = computed<Concept[]>(() => {
    const check = this.check();
    if (check === null) {
      return [];
    }
    const concepts = new Map<string, Concept>();
    check.found.forEach((concept) =>
      concepts.set(concept.conceptId, {
        conceptId: concept.conceptId,
        preferredTerm: concept.preferredTerm,
        fullySpecifiedName: concept.fullySpecifiedName,
        language: concept.language,
      }),
    );
    check.inactive.forEach((concept) =>
      concept.replacements
        .filter((replacement) => this.accepted().has(replacement.conceptId) && !concepts.has(replacement.conceptId))
        .forEach((replacement) =>
          concepts.set(replacement.conceptId, {
            conceptId: replacement.conceptId,
            preferredTerm: replacement.term,
            fullySpecifiedName: null,
          }),
        ),
    );
    return [...concepts.values()].filter((concept) => !this.inList().has(concept.conceptId));
  });
  protected readonly overCapacity = computed(() => this.inList().size + this.toAdd().length > this.maxMembers());

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

  protected useText(event: Event): void {
    this.setText((event.target as HTMLTextAreaElement).value);
  }

  protected readFile(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) {
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      this.state.set({ status: 'input', message: 'That file is over 5 MB, which is more than a list of identifiers needs.' });
      return;
    }
    file.text().then(
      (content) => {
        this.state.set({ status: 'input', message: null });
        this.setText(content);
      },
      () => this.state.set({ status: 'input', message: 'The file could not be read.' }),
    );
  }

  protected useFoundFilter(event: Event): void {
    this.foundFilter.set((event.target as HTMLSelectElement).value as FoundFilter);
  }

  protected useEcl(event: Event): void {
    this.ecl.set((event.target as HTMLTextAreaElement).value);
  }

  protected useTermColumn(event: Event): void {
    const chosen = Number((event.target as HTMLSelectElement).value);
    this.termColumn.set(chosen < 0 ? null : chosen);
  }

  protected runCheck(): void {
    this.state.set({ status: 'checking' });
    const request =
      this.source() === 'ecl'
        ? this.api.checkEclImport(this.branch(), this.ecl().trim())
        : this.api.checkIdentifiers(this.branch(), this.entries());
    request.subscribe({
      next: (check) => {
        this.accepted.set(
          new Set(check.inactive.flatMap((concept) => concept.replacements.map((replacement) => replacement.conceptId))),
        );
        this.foundFilter.set('all');
        this.state.set({ status: 'review', check });
      },
      error: (error: unknown) => this.state.set({ status: 'input', message: describeError(error) }),
    });
  }

  protected isAlreadyIn(conceptId: string): boolean {
    return this.inList().has(conceptId);
  }

  protected toggle(conceptId: string): void {
    const next = new Set(this.accepted());
    if (!next.delete(conceptId)) {
      next.add(conceptId);
    }
    this.accepted.set(next);
  }

  protected setAll(accept: boolean): void {
    const all = (this.check()?.inactive ?? []).flatMap((concept: Inactive) =>
      concept.replacements.map((replacement) => replacement.conceptId),
    );
    this.accepted.set(accept ? new Set(all) : new Set());
  }

  protected shown<T>(rows: T[]): T[] {
    return rows.slice(0, ROWS_SHOWN);
  }

  protected rejectionText(reason: IdentifierRejection): string {
    return REJECTIONS[reason];
  }

  protected add(): void {
    this.added.emit(this.toAdd());
    this.close();
  }

  /** Built in the browser from the check already on screen, so nothing more is sent anywhere. */
  protected downloadResults(): void {
    const check = this.check();
    if (check === null) {
      return;
    }
    const url = URL.createObjectURL(new Blob([resultsCsv(check, this.accepted())], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'identifier-import.csv';
    link.click();
    URL.revokeObjectURL(url);
  }

  protected close(): void {
    const dialog = this.dialog().nativeElement;
    if (typeof dialog.close === 'function') {
      dialog.close();
    } else {
      this.closed.emit();
    }
  }

  private setText(value: string): void {
    this.text.set(value);
    const list = this.parsed();
    this.idColumn.set(list?.idColumn ?? 0);
    this.termColumn.set(list?.termColumn ?? null);
  }
}

/** One line per outcome, quoted, with a leading apostrophe on anything a spreadsheet might run as a formula. */
export function resultsCsv(check: IdentifierCheck, accepted: ReadonlySet<string>): string {
  const lines: string[][] = [['Outcome', 'Identifier', 'Concept ID', 'Term', 'Detail']];
  check.found.forEach((concept) =>
    lines.push([
      'FOUND',
      concept.descriptionId ?? concept.conceptId,
      concept.conceptId,
      concept.preferredTerm ?? '',
      concept.termDiffers ? `List term: ${concept.suppliedTerm}` : '',
    ]),
  );
  check.inactive.forEach((concept) => {
    lines.push(['INACTIVE', concept.descriptionId ?? concept.conceptId, concept.conceptId, concept.term ?? '', concept.reason ?? '']);
    concept.replacements.forEach((replacement) =>
      lines.push([
        accepted.has(replacement.conceptId) ? 'REPLACEMENT_ADDED' : 'REPLACEMENT_DECLINED',
        concept.conceptId,
        replacement.conceptId,
        replacement.term ?? '',
        replacement.association,
      ]),
    );
  });
  check.rejected.forEach((rejected) => lines.push(['NOT_RECOGNISED', rejected.identifier, '', '', rejected.reason]));
  return lines.map((line) => line.map(quote).join(',')).join('\r\n') + '\r\n';
}

function quote(value: string): string {
  const safe = /^[=+\-@\t\r]/.test(value) ? `'${value}` : value;
  return `"${safe.replaceAll('"', '""')}"`;
}
