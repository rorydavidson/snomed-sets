import { DecimalPipe } from '@angular/common';
import { Component, ElementRef, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  CodeMap,
  MATCH_CHUNK,
  MAX_MAP_ROWS,
  MAX_TARGETS,
  MapApi,
  MapRow,
  MapTarget,
  RELATIONSHIPS,
  Relationship,
  RowStatus,
  RowUpdate,
  SourceRow,
  describeMapError,
  mapDownloadUrl,
} from '../core/map-api';
import { Concept } from '../core/value-set-api';
import { ConceptDetails } from '../value-set/concept-details';
import { ConceptPicker } from '../value-set/concept-picker';
import { CodeListInput } from './code-list-input';

const PAGE_SIZE = 50;
/** Snowstorm refuses shorter search terms, so the server does not look for them. */
const SHORTEST_SEARCH = 3;

type StatusFilter = RowStatus | 'ALL';

const STATUS_LABELS: Record<RowStatus, string> = {
  UNMAPPED: 'Unmapped',
  SUGGESTED: 'Suggested',
  MAPPED: 'Mapped',
  NO_MAP: 'No map',
};

/**
 * Where a map is worked on: its local codes in a table, and beside it the targets of the code
 * that is selected. Every change to a row is saved as it is made.
 */
@Component({
  selector: 'app-map-page',
  imports: [RouterLink, DecimalPipe, ConceptPicker, ConceptDetails, CodeListInput],
  template: `
    <main class="page">
      <p><a routerLink="/maps">Back to your maps</a></p>

      @if (loadError(); as message) {
        <div class="error-panel" role="alert">
          <h1>This map cannot be shown</h1>
          <p>{{ message }}</p>
        </div>
      } @else if (map(); as map) {
        <div class="card-heading">
          <h1 class="grow-heading">{{ map.name }}</h1>
          <span class="badge">Private</span>
        </div>
        @if (map.description) {
          <p>{{ map.description }}</p>
        }
        <p class="meta">
          {{ map.localSystemName }}{{ map.localSystemVersion ? ' ' + map.localSystemVersion : '' }} to SNOMED CT ·
          {{ map.codeSystem }} {{ map.codeSystemVersion ?? 'live branch' }}
        </p>

        <ul class="counts" aria-label="Progress">
          <li><strong>{{ counts().total | number }}</strong> codes</li>
          <li><strong>{{ counts().MAPPED | number }}</strong> mapped</li>
          <li><strong>{{ counts().NO_MAP | number }}</strong> no map</li>
          <li><strong>{{ counts().SUGGESTED | number }}</strong> suggested</li>
          <li><strong>{{ counts().UNMAPPED | number }}</strong> unmapped</li>
        </ul>

        <div class="actions">
          <button type="button" class="button button-secondary" (click)="adding.set(!adding())">Add codes</button>
          <a class="button button-secondary" [href]="downloadUrl('CSV')" download>Download (CSV)</a>
          <a class="button button-secondary" [href]="downloadUrl('TSV')" download>Download (TSV)</a>
          <button type="button" class="button button-secondary button-danger" (click)="deleteMap()">Delete map</button>
        </div>
        <p class="meta">
          Downloads hold the map as it stands. Suggestions nobody has accepted are left out. In the CSV, a value a
          spreadsheet would read as a formula starts with an apostrophe; the TSV has every code exactly as it was given.
        </p>
        @if (pageError(); as message) {
          <p class="field-error" role="alert">{{ message }}</p>
        }

        @if (adding()) {
          <section class="panel">
            <h2>Add codes</h2>
            <app-code-list-input [existing]="codes()" [room]="room()" (rowsChange)="newRows.set($event)" />
            <div class="actions">
              <button type="button" class="button button-primary" [disabled]="!newRows()?.length || addingRows()" (click)="addRows()">
                {{ addingRows() ? 'Adding…' : 'Add to the map' }}
              </button>
              <button type="button" class="button button-secondary" (click)="adding.set(false)">Cancel</button>
            </div>
          </section>
        }

        @if (matching(); as progress) {
          <p class="matching" role="status">
            Looking for each term in SNOMED CT: {{ progress.done | number }} of {{ progress.total | number }}.
            <button type="button" class="link-button" (click)="stopMatching()">Stop</button>
          </p>
        } @else if (unchecked().length > 0) {
          <p class="matching">
            {{ unchecked().length | number }} {{ unchecked().length === 1 ? 'term has' : 'terms have' }} not been looked for in SNOMED CT yet.
            <button type="button" class="link-button" (click)="matchTerms()">Match remaining</button>
          </p>
        }
        @if (matchError(); as message) {
          <p class="field-error" role="alert">{{ message }}</p>
        }
        @if (singleSuggestions() > 0 && !matching()) {
          <p class="matching">
            {{ singleSuggestions() | number }} {{ singleSuggestions() === 1 ? 'code has' : 'codes have' }} exactly one concept
            with the same term. The same words can still name different things, so look before you accept them all.
            <button type="button" class="link-button" [disabled]="accepting()" (click)="acceptSingles()">
              Accept all {{ singleSuggestions() | number }}
            </button>
          </p>
        }

        <div class="columns">
          <section class="wide">
            <div class="actions">
              <div class="field">
                <label for="status-filter">Show</label>
                <select id="status-filter" (change)="setStatusFilter($any($event.target).value)">
                  <option value="ALL">All codes</option>
                  @for (status of statuses; track status) {
                    <option [value]="status" [selected]="statusFilter() === status">{{ statusLabel(status) }} ({{ counts()[status] }})</option>
                  }
                </select>
              </div>
              <div class="field grow">
                <label for="text-filter">Find a code or term</label>
                <input id="text-filter" type="text" autocomplete="off" (input)="setTextFilter($any($event.target).value)" />
              </div>
            </div>

            @if (shown().length === 0) {
              <p class="muted" role="status">{{ counts().total === 0 ? 'This map has no codes yet. Add some to start.' : 'No codes match.' }}</p>
            } @else {
              <p class="meta">Arrow keys move between rows. Enter accepts a single suggestion. N marks a row as having no map.</p>
              <div class="table-scroll">
                <table class="map-table">
                  <thead>
                    <tr>
                      <th scope="col">Code</th>
                      <th scope="col">Term</th>
                      <th scope="col">Status</th>
                      <th scope="col">SNOMED CT</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (row of pageRows(); track row.id) {
                      <tr tabindex="0" [attr.data-row]="row.id" [class.selected]="row.id === selectedId()" [attr.aria-selected]="row.id === selectedId()"
                          (click)="selectedId.set(row.id)" (focus)="selectedId.set(row.id)" (keydown)="onRowKey($event, row)">
                        <td class="mono">{{ row.code }}</td>
                        <td>{{ row.term }}</td>
                        <td><span class="badge" [class.badge-success]="row.status === 'MAPPED'" [class.badge-warning]="row.status === 'SUGGESTED'">{{ statusLabel(row.status) }}</span></td>
                        <td>
                          @for (target of row.targets; track target.code) {
                            <div [class.suggestion]="row.status === 'SUGGESTED'">
                              {{ target.term }} <span class="meta mono">{{ target.code }}</span>
                              @if (target.relationship !== 'EQUIVALENT') {
                                <span class="meta">({{ relationshipLabel(target.relationship) }})</span>
                              }
                            </div>
                          }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
              @if (pageCount() > 1) {
                <div class="pager actions">
                  <button type="button" class="button button-secondary button-small" [disabled]="page() === 0" (click)="page.set(page() - 1)">Previous</button>
                  <span class="meta">Page {{ page() + 1 }} of {{ pageCount() }}</span>
                  <button type="button" class="button button-secondary button-small" [disabled]="page() >= pageCount() - 1" (click)="page.set(page() + 1)">Next</button>
                </div>
              }
            }
          </section>

          <section class="panel">
            @if (selected(); as row) {
              <h2><span class="mono">{{ row.code }}</span> {{ row.term }}</h2>
              @if (rowError(); as message) {
                <p class="field-error" role="alert">{{ message }}</p>
              }

              @if (row.status === 'SUGGESTED') {
                <p>
                  {{ row.targets.length === 1 ? 'One concept has' : row.targets.length + ' concepts have' }} a description with
                  the same words. Nothing is mapped until you accept one.
                </p>
              } @else if (row.status === 'NO_MAP') {
                <p>Marked as having no map: nothing in SNOMED CT fits.</p>
              } @else if (row.targets.length === 0) {
                <p class="muted">{{ unmappedMessage(row) }}</p>
              }

              @if (row.targets.length > 0) {
                <ul class="targets">
                  @for (target of row.targets; track target.code) {
                    <li>
                      <div>
                        <strong>{{ target.term }}</strong>
                        <span class="meta mono">{{ target.code }} · {{ target.fullySpecifiedName }}</span>
                        @if (target.origin === 'EXACT_TERM') {
                          <span class="meta">Found by its term</span>
                        }
                      </div>
                      <div class="actions">
                        @if (row.status === 'SUGGESTED') {
                          <button type="button" class="button button-primary button-small" [disabled]="saving()" (click)="accept(row, target)">Accept</button>
                        } @else {
                          <label class="visually-hidden" [attr.for]="'relationship-' + target.code">How {{ target.term }} compares</label>
                          <select [id]="'relationship-' + target.code" [disabled]="saving()" (change)="relate(row, target, $any($event.target).value)">
                            @for (option of relationships; track option.value) {
                              <option [value]="option.value" [selected]="option.value === target.relationship">{{ option.label }}</option>
                            }
                          </select>
                          <button type="button" class="link-button" [disabled]="saving()" (click)="removeTarget(row, target)">Remove</button>
                        }
                        <button type="button" class="link-button" (click)="shownConcept.set(target.code)">Details</button>
                      </div>
                    </li>
                  }
                </ul>
              }

              <div class="actions">
                @if (row.status === 'SUGGESTED') {
                  <button type="button" class="button button-secondary button-small" [disabled]="saving()" (click)="clear(row)">Dismiss suggestions</button>
                }
                @if (row.status === 'NO_MAP') {
                  <button type="button" class="button button-secondary button-small" [disabled]="saving()" (click)="clear(row)">Undo no map</button>
                } @else {
                  <button type="button" class="button button-secondary button-small" [disabled]="saving()" (click)="noMap(row)">No map</button>
                }
                <button type="button" class="link-button" [disabled]="saving()" (click)="deleteRow(row)">Remove this code</button>
              </div>

              <div class="field">
                <label for="row-note">Note (optional)</label>
                <textarea id="row-note" rows="2" maxlength="500" [value]="row.note ?? ''" (change)="saveNote(row, $any($event.target).value)"></textarea>
              </div>

              @if (row.status !== 'NO_MAP') {
                <h3>Choose a concept</h3>
                <app-concept-picker [branch]="map.branch" [inList]="acceptedCodes()" (add)="addTargets(row, $event)" (details)="shownConcept.set($event)" />
              }
            } @else {
              <p class="muted">Select a code to see or choose its SNOMED CT concepts.</p>
            }
          </section>
        </div>

        @if (shownConcept(); as conceptId) {
          <app-concept-details [conceptId]="conceptId" [branch]="map.branch" (closed)="shownConcept.set(null)" />
        }
      } @else {
        <p class="muted" role="status">Loading map…</p>
      }
    </main>
  `,
  styles: `
    .counts {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-5);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .matching {
      padding: var(--space-3) var(--space-4);
      background: var(--surface-subtle);
      border-left: 4px solid var(--snomed-cyan);
      border-radius: var(--radius-sm);
    }

    .panel {
      padding: var(--space-5);
      background: var(--surface-card);
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-md);
    }

    .columns > .panel {
      position: sticky;
      top: var(--space-4);
    }

    .map-table tbody tr {
      cursor: pointer;
    }

    .map-table tbody tr.selected {
      background: var(--surface-inset);
    }

    .suggestion {
      font-style: italic;
      color: var(--text-muted);
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
export class MapPage implements OnInit {
  private readonly api = inject(MapApi);
  private readonly router = inject(Router);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly id = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));

  protected readonly statuses: RowStatus[] = ['UNMAPPED', 'SUGGESTED', 'MAPPED', 'NO_MAP'];
  protected readonly relationships = RELATIONSHIPS;

  protected readonly map = signal<CodeMap | null>(null);
  private readonly rows = signal<MapRow[]>([]);
  protected readonly loadError = signal<string | null>(null);
  protected readonly pageError = signal<string | null>(null);

  protected readonly statusFilter = signal<StatusFilter>('ALL');
  private readonly textFilter = signal('');
  protected readonly page = signal(0);
  protected readonly selectedId = signal<number | null>(null);
  /** The concept whose details are open, if any. */
  protected readonly shownConcept = signal<string | null>(null);

  /** True while a change to the selected row is being saved. */
  protected readonly saving = signal(false);
  protected readonly rowError = signal<string | null>(null);

  /** How far term matching has got, or null when it is not running. */
  protected readonly matching = signal<{ done: number; total: number } | null>(null);
  protected readonly matchError = signal<string | null>(null);
  private stopRequested = false;
  protected readonly accepting = signal(false);

  protected readonly adding = signal(false);
  protected readonly newRows = signal<SourceRow[] | null>(null);
  protected readonly addingRows = signal(false);

  protected readonly counts = computed(() => {
    const counts = { total: 0, UNMAPPED: 0, SUGGESTED: 0, MAPPED: 0, NO_MAP: 0 };
    for (const row of this.rows()) {
      counts.total++;
      counts[row.status]++;
    }
    return counts;
  });
  protected readonly codes = computed<ReadonlySet<string>>(() => new Set(this.rows().map((row) => row.code)));
  protected readonly room = computed(() => MAX_MAP_ROWS - this.rows().length);
  /** Rows whose term has not been looked for in SNOMED CT. */
  protected readonly unchecked = computed(() => this.rows().filter((row) => row.status === 'UNMAPPED' && !row.termChecked));
  protected readonly singleSuggestions = computed(
    () => this.rows().filter((row) => row.status === 'SUGGESTED' && row.targets.length === 1).length,
  );

  protected readonly shown = computed(() => {
    const status = this.statusFilter();
    const words = this.textFilter().toLowerCase().split(/\s+/).filter((word) => word !== '');
    return this.rows().filter((row) => {
      if (status !== 'ALL' && row.status !== status) {
        return false;
      }
      const searchable = `${row.code} ${row.term}`.toLowerCase();
      return words.every((word) => searchable.includes(word));
    });
  });
  protected readonly pageCount = computed(() => Math.ceil(this.shown().length / PAGE_SIZE));
  protected readonly pageRows = computed(() => this.shown().slice(this.page() * PAGE_SIZE, (this.page() + 1) * PAGE_SIZE));
  protected readonly selected = computed(() => this.rows().find((row) => row.id === this.selectedId()) ?? null);
  /** The selected row's targets, unless they are only suggestions. */
  protected readonly acceptedCodes = computed<ReadonlySet<string>>(() => {
    const row = this.selected();
    return new Set(row && row.status === 'MAPPED' ? row.targets.map((target) => target.code) : []);
  });

  ngOnInit(): void {
    forkJoin({ map: this.api.get(this.id), rows: this.api.rows(this.id) }).subscribe({
      next: ({ map, rows }) => {
        this.map.set(map);
        this.rows.set(rows);
        // New codes are term matched straight away, without being asked.
        this.matchTerms();
      },
      error: (error: unknown) => this.loadError.set(describeMapError(error)),
    });
  }

  /** Why an unmapped row has no suggestion. A term under three characters is never searched for. */
  protected unmappedMessage(row: MapRow): string {
    if (row.term.trim().length < SHORTEST_SEARCH) {
      return 'This term is too short to look for automatically. Search below to choose a concept.';
    }
    return row.termChecked ? 'No concept has exactly this term. Search below to choose one.' : 'Not mapped yet.';
  }

  protected statusLabel(status: RowStatus): string {
    return STATUS_LABELS[status];
  }

  protected relationshipLabel(relationship: Relationship): string {
    return RELATIONSHIPS.find((option) => option.value === relationship)?.label ?? relationship;
  }

  protected downloadUrl(format: 'CSV' | 'TSV'): string {
    return mapDownloadUrl(this.id, format);
  }

  protected setStatusFilter(status: StatusFilter): void {
    this.statusFilter.set(status);
    this.page.set(0);
  }

  protected setTextFilter(text: string): void {
    this.textFilter.set(text);
    this.page.set(0);
  }

  /**
   * Looks for the terms not yet checked, a piece at a time so a long map shows its progress and
   * no single request runs for minutes. Rows left over when it stops or fails can be matched later.
   */
  protected matchTerms(): void {
    const ids = this.unchecked().map((row) => row.id);
    if (ids.length === 0 || this.matching() !== null) {
      return;
    }
    this.stopRequested = false;
    this.matchError.set(null);
    this.matchFrom(ids, 0);
  }

  protected stopMatching(): void {
    this.stopRequested = true;
  }

  private matchFrom(ids: number[], done: number): void {
    if (done >= ids.length || this.stopRequested) {
      this.matching.set(null);
      return;
    }
    this.matching.set({ done, total: ids.length });
    this.api.matchTerms(this.id, ids.slice(done, done + MATCH_CHUNK)).subscribe({
      next: (matched) => {
        this.replaceRows(matched);
        this.matchFrom(ids, done + MATCH_CHUNK);
      },
      error: (error: unknown) => {
        this.matching.set(null);
        this.matchError.set(`Matching stopped: ${describeMapError(error)}`);
      },
    });
  }

  protected acceptSingles(): void {
    this.accepting.set(true);
    this.pageError.set(null);
    this.api.acceptSingleSuggestions(this.id).subscribe({
      next: () =>
        this.api.rows(this.id).subscribe({
          next: (rows) => {
            this.rows.set(rows);
            this.accepting.set(false);
          },
          error: (error: unknown) => this.failed(error),
        }),
      error: (error: unknown) => this.failed(error),
    });
  }

  private failed(error: unknown): void {
    this.accepting.set(false);
    this.pageError.set(describeMapError(error));
  }

  /** Accepts one suggestion as the row's map, dropping any others. */
  protected accept(row: MapRow, target: MapTarget): void {
    this.save(row, { targets: [choice(target)], noMap: false, note: row.note });
  }

  protected relate(row: MapRow, target: MapTarget, relationship: Relationship): void {
    const targets = row.targets.map((existing) => (existing.code === target.code ? { ...choice(existing), relationship } : choice(existing)));
    this.save(row, { targets, noMap: false, note: row.note });
  }

  protected removeTarget(row: MapRow, target: MapTarget): void {
    const targets = row.targets.filter((existing) => existing.code !== target.code).map(choice);
    this.save(row, { targets, noMap: false, note: row.note });
  }

  /** Adds concepts chosen in the search. Suggestions nobody accepted are not kept alongside them. */
  protected addTargets(row: MapRow, concepts: Concept[]): void {
    const kept = row.status === 'MAPPED' ? row.targets.map(choice) : [];
    const have = new Set(kept.map((target) => target.conceptId));
    const added = concepts
      .filter((concept) => !have.has(concept.conceptId))
      .map((concept) => ({ conceptId: concept.conceptId, relationship: 'EQUIVALENT' as Relationship }));
    if (kept.length + added.length > MAX_TARGETS) {
      this.rowError.set(`A code can have at most ${MAX_TARGETS} targets.`);
      return;
    }
    if (added.length > 0) {
      this.save(row, { targets: [...kept, ...added], noMap: false, note: row.note });
    }
  }

  protected noMap(row: MapRow): void {
    this.save(row, { targets: [], noMap: true, note: row.note });
  }

  /** Back to unmapped: drops suggestions, or undoes "no map". */
  protected clear(row: MapRow): void {
    this.save(row, { targets: [], noMap: false, note: row.note });
  }

  protected saveNote(row: MapRow, note: string): void {
    // A suggested row keeps its suggestions: writing a note does not accept or dismiss them.
    if (row.status === 'SUGGESTED') {
      this.rowError.set('Accept or dismiss the suggestions before adding a note.');
      return;
    }
    this.save(row, { targets: row.targets.map(choice), noMap: row.status === 'NO_MAP', note: note.trim() || null });
  }

  protected deleteRow(row: MapRow): void {
    if (!confirm(`Remove ${row.code} from the map? Its targets go with it.`)) {
      return;
    }
    this.saving.set(true);
    this.rowError.set(null);
    this.api.deleteRow(this.id, row.id).subscribe({
      next: () => {
        this.saving.set(false);
        this.rows.update((rows) => rows.filter((existing) => existing.id !== row.id));
        this.selectedId.set(null);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.rowError.set(describeMapError(error));
      },
    });
  }

  protected deleteMap(): void {
    if (!confirm('Delete this map and everything in it? This cannot be undone.')) {
      return;
    }
    this.api.delete(this.id).subscribe({
      next: () => void this.router.navigate(['/maps']),
      error: (error: unknown) => this.pageError.set(describeMapError(error)),
    });
  }

  protected addRows(): void {
    const rows = this.newRows();
    if (!rows || rows.length === 0) {
      return;
    }
    this.addingRows.set(true);
    this.pageError.set(null);
    this.api.addRows(this.id, rows).subscribe({
      next: () =>
        this.api.rows(this.id).subscribe({
          next: (all) => {
            this.rows.set(all);
            this.addingRows.set(false);
            this.adding.set(false);
            this.matchTerms();
          },
          error: (error: unknown) => this.addFailed(error),
        }),
      error: (error: unknown) => this.addFailed(error),
    });
  }

  private addFailed(error: unknown): void {
    this.addingRows.set(false);
    this.pageError.set(describeMapError(error));
  }

  /** Mapping is repetitive work, so the common decisions can be made without leaving the keyboard. */
  protected onRowKey(event: KeyboardEvent, row: MapRow): void {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const rows = this.pageRows();
      const next = rows[rows.findIndex((candidate) => candidate.id === row.id) + (event.key === 'ArrowDown' ? 1 : -1)];
      if (next) {
        this.host.nativeElement.querySelector<HTMLElement>(`tr[data-row="${next.id}"]`)?.focus();
      }
    } else if (event.key === 'Enter' && row.status === 'SUGGESTED' && row.targets.length === 1 && !this.saving()) {
      event.preventDefault();
      this.accept(row, row.targets[0]);
    } else if (event.key.toLowerCase() === 'n' && row.status !== 'NO_MAP' && !this.saving()) {
      event.preventDefault();
      this.noMap(row);
    }
  }

  private save(row: MapRow, update: RowUpdate): void {
    this.saving.set(true);
    this.rowError.set(null);
    this.api.updateRow(this.id, row.id, update).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.replaceRows([saved]);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.rowError.set(describeMapError(error));
      },
    });
  }

  private replaceRows(changed: MapRow[]): void {
    const byId = new Map(changed.map((row) => [row.id, row]));
    this.rows.update((rows) => rows.map((row) => byId.get(row.id) ?? row));
  }
}

function choice(target: MapTarget): { conceptId: string; relationship: Relationship } {
  return { conceptId: target.code, relationship: target.relationship };
}
