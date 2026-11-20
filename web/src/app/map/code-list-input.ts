import { Component, computed, effect, input, output, signal } from '@angular/core';
import { MAX_MAP_ROWS, SourceRow } from '../core/map-api';
import { parseCodeList, repeatedCodes, sourceRowsOf } from './code-list';

const MAX_FILE_BYTES = 5 * 1024 * 1024;
const PREVIEW_ROWS = 5;

/**
 * Takes a list of local codes and terms from a file or from pasted text, lets the person say
 * which columns hold them, and hands the rows to the page. Nothing is sent anywhere from here.
 */
@Component({
  selector: 'app-code-list-input',
  template: `
    <p class="meta">
      Choose a CSV or TSV file, or paste or type one code and its term per line. The file is read in your browser and
      only the code and term columns you pick are sent. Do not use a list that holds anything about patients.
    </p>
    <div class="field">
      <label for="code-file">File</label>
      <input id="code-file" type="file" accept=".csv,.tsv,.txt,text/csv,text/plain,text/tab-separated-values" (change)="readFile($event)" />
    </div>
    <div class="field">
      <label for="code-text">Codes and terms</label>
      <textarea id="code-text" class="mono" rows="8" spellcheck="false" placeholder="D01, Asthma" [value]="text()" (input)="text.set($any($event.target).value)"></textarea>
    </div>
    @if (fileError(); as message) {
      <p class="field-error" role="alert">{{ message }}</p>
    }

    @if (parsed().rows.length > 0 || parsed().hasHeader) {
      <label class="checkbox">
        <input type="checkbox" [checked]="parsed().hasHeader" (change)="headerChoice.set($any($event.target).checked)" />
        <span>The first row is a header</span>
      </label>
      @if (parsed().columns.length > 1) {
        <div class="actions">
          <div class="field">
            <label for="code-column">Code column</label>
            <select id="code-column" (change)="codeChoice.set(+$any($event.target).value)">
              @for (column of parsed().columns; track $index) {
                <option [value]="$index" [selected]="$index === codeColumn()">{{ column }}</option>
              }
            </select>
          </div>
          <div class="field">
            <label for="term-column">Term column</label>
            <select id="term-column" (change)="termChoice.set(+$any($event.target).value)">
              <option value="-1" [selected]="termColumn() === null">None</option>
              @for (column of parsed().columns; track $index) {
                <option [value]="$index" [selected]="$index === termColumn()">{{ column }}</option>
              }
            </select>
          </div>
        </div>
      }
      <p class="meta" role="status">{{ summary() }}</p>
      @if (problem(); as message) {
        <p class="field-error" role="alert">{{ message }}</p>
      }
      @if (rows().length > 0) {
        <div class="table-scroll">
          <table>
            <thead>
              <tr><th scope="col">Code</th><th scope="col">Term</th></tr>
            </thead>
            <tbody>
              @for (row of preview(); track $index) {
                <tr><td class="mono">{{ row.code }}</td><td>{{ row.term }}</td></tr>
              }
            </tbody>
          </table>
        </div>
      }
    }
  `,
})
export class CodeListInput {
  /** Codes the map already has, which cannot be added again. */
  readonly existing = input<ReadonlySet<string>>(new Set());
  /** How many more codes the map can take. */
  readonly room = input(MAX_MAP_ROWS);
  /** The rows as they stand, or null while they cannot be used as they are. */
  readonly rowsChange = output<SourceRow[] | null>();

  protected readonly text = signal('');
  protected readonly fileError = signal<string | null>(null);
  /** The person's own answers, where they have overruled what was worked out from the list. */
  protected readonly headerChoice = signal<boolean | undefined>(undefined);
  protected readonly codeChoice = signal<number | undefined>(undefined);
  protected readonly termChoice = signal<number | undefined>(undefined);

  protected readonly parsed = computed(() => parseCodeList(this.text(), this.headerChoice()));
  protected readonly codeColumn = computed(() => this.codeChoice() ?? this.parsed().codeColumn);
  protected readonly termColumn = computed(() => {
    const chosen = this.termChoice();
    if (chosen === undefined) {
      return this.parsed().termColumn;
    }
    return chosen < 0 ? null : chosen;
  });
  protected readonly rows = computed(() => sourceRowsOf(this.parsed(), this.codeColumn(), this.termColumn()));
  protected readonly preview = computed(() => this.rows().slice(0, PREVIEW_ROWS));

  protected readonly summary = computed(() => {
    const count = this.rows().length;
    const shown = Math.min(count, PREVIEW_ROWS);
    return count === 0 ? 'No codes found.' : `${count} ${count === 1 ? 'code' : 'codes'}. Showing the first ${shown}.`;
  });

  /** Why the rows cannot be used as they are, or null when they can. */
  protected readonly problem = computed(() => {
    const rows = this.rows();
    if (rows.length > this.room()) {
      return `A map can hold ${MAX_MAP_ROWS} codes, and there is room for ${this.room()} more. This list has ${rows.length}.`;
    }
    const repeated = repeatedCodes(rows);
    if (repeated.length > 0) {
      return `Each code can be in a map once. Repeated: ${named(repeated)}.`;
    }
    const existing = rows.filter((row) => this.existing().has(row.code)).map((row) => row.code);
    if (existing.length > 0) {
      return `Already in the map: ${named(existing)}.`;
    }
    const tooLong = rows.find((row) => row.code.length > 100 || row.term.length > 500);
    return tooLong ? `Code ${tooLong.code.slice(0, 40)} is too long: codes can be 100 characters and terms 500.` : null;
  });

  constructor() {
    effect(() => this.rowsChange.emit(this.problem() === null ? this.rows() : null));
  }

  protected readFile(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) {
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      this.fileError.set('That file is over 5 MB, which is more than a list of codes needs.');
      return;
    }
    file.text().then(
      (content) => {
        this.fileError.set(null);
        // A new file has its own columns, so earlier answers no longer apply.
        this.headerChoice.set(undefined);
        this.codeChoice.set(undefined);
        this.termChoice.set(undefined);
        this.text.set(content);
      },
      () => this.fileError.set('The file could not be read.'),
    );
  }
}

/** "A1, B2 and 3 more". */
function named(codes: string[]): string {
  const first = codes.slice(0, 5).join(', ');
  return codes.length > 5 ? `${first} and ${codes.length - 5} more` : first;
}
