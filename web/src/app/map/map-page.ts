import { DecimalPipe } from '@angular/common';
import { Component, ElementRef, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, forkJoin } from 'rxjs';
import {
  CodeMap,
  MATCH_CHUNK,
  MAX_MAP_ROWS,
  MapApi,
  MapRow,
  Relationship,
  RowStatus,
  RowUpdate,
  SourceRow,
  describeMapError,
  mapDownloadUrl,
  relationshipsFor,
} from '../core/map-api';
import { Concept } from '../core/value-set-api';
import { hasWords } from '../core/word-filter';
import { ConceptDetails } from '../value-set/concept-details';
import { IdentifierImport } from '../value-set/identifier-import';
import { MapAddCodes } from './map-add-codes';
import { MapAddConcepts } from './map-add-concepts';
import { MapLocalCodes, localCodeLines } from './map-local-codes';
import { MapRowDetail, acceptUpdate, noMapUpdate } from './map-row-detail';

const PAGE_SIZE = 50;

type StatusFilter = RowStatus | 'ALL';
type Column = 'code' | 'term' | 'status' | 'target';
/** The columns filtered by typing. Status is chosen from a list instead. */
type TextColumn = Exclude<Column, 'status'>;
const NO_FILTERS: Record<TextColumn, string> = { code: '', term: '', target: '' };

/** The words that differ between a map to SNOMED CT and a map from it. */
const WORDS = {
  TO_SNOMED: {
    rows: 'codes',
    row: 'code',
    source: 'Code',
    target: 'SNOMED CT',
    matching: 'Looking for each term in SNOMED CT',
    unchecked: 'not been looked for in SNOMED CT yet',
  },
  FROM_SNOMED: {
    rows: 'concepts',
    row: 'concept',
    source: 'SNOMED CT concept',
    target: 'Local code',
    matching: 'Looking for the names of each concept among the local terms',
    unchecked: 'not been looked for among the local terms yet',
  },
};

const STATUS_LABELS: Record<RowStatus, string> = {
  UNMAPPED: 'Unmapped',
  SUGGESTED: 'Suggested',
  MAPPED: 'Mapped',
  NO_MAP: 'No map',
};

/**
 * Where a map is worked on: its local codes in a table across the page, with the targets of the
 * selected code opening under its row. Every change to a row is saved as it is made.
 */
@Component({
  selector: 'app-map-page',
  imports: [RouterLink, DecimalPipe, ConceptDetails, IdentifierImport, MapAddCodes, MapAddConcepts, MapLocalCodes, MapRowDetail],
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
          @if (fromSnomed()) {
            SNOMED CT to {{ map.localSystemName }}{{ map.localSystemVersion ? ' ' + map.localSystemVersion : '' }} ·
          } @else {
            {{ map.localSystemName }}{{ map.localSystemVersion ? ' ' + map.localSystemVersion : '' }} to SNOMED CT ·
          }
          {{ map.codeSystem }} {{ map.codeSystemVersion ?? 'live branch' }}
        </p>
        @if (pageError(); as message) {
          <p class="field-error" role="alert">{{ message }}</p>
        }

        @if (fromSnomed()) {
          <!-- The map is worked on from the concept side; the local codes have a view of their own. -->
          <div class="tabs" role="tablist" aria-label="Views of this map">
            <button type="button" role="tab" id="tab-concepts" [attr.aria-selected]="tab() === 'concepts'" [class.current]="tab() === 'concepts'" (click)="show('concepts')">
              Concepts ({{ counts().total | number }})
            </button>
            <button type="button" role="tab" id="tab-local" [attr.aria-selected]="tab() === 'local'" [class.current]="tab() === 'local'" (click)="show('local')">
              Local codes ({{ localCodes().length | number }}{{ unmappedLocalCodes() > 0 ? ' · ' + unmappedLocalCodes() + ' with nothing mapped' : '' }})
            </button>
          </div>
        }

        @if (tab() === 'local' && fromSnomed()) {
          <app-map-local-codes role="tabpanel" aria-labelledby="tab-local" [lines]="localLines()" [room]="localRoom()" [busy]="addingRows()" [(adding)]="adding" (add)="addRows($event)" />
        } @else {
        <ul class="counts" aria-label="Progress">
          <li><strong>{{ counts().total | number }}</strong> {{ words().rows }}</li>
          <li><strong>{{ counts().MAPPED | number }}</strong> mapped</li>
          <li><strong>{{ counts().NO_MAP | number }}</strong> no map</li>
          <li><strong>{{ counts().SUGGESTED | number }}</strong> suggested</li>
          <li><strong>{{ counts().UNMAPPED | number }}</strong> unmapped</li>
        </ul>

        <div class="actions">
          @if (fromSnomed()) {
            <button type="button" class="button button-secondary" (click)="addingConcepts.set(true)">Add concepts</button>
          } @else {
            <button type="button" class="button button-secondary" (click)="adding.set(!adding())">Add codes</button>
          }
          <a class="button button-secondary" [href]="downloadUrl('CSV')" download>Download (CSV)</a>
          <a class="button button-secondary" [href]="downloadUrl('TSV')" download>Download (TSV)</a>
          <button type="button" class="button button-secondary button-danger" (click)="deleteMap()">Delete map</button>
        </div>
        <p class="meta">
          Downloads hold the map as it stands. Suggestions nobody has accepted are left out. In the CSV, a value a
          spreadsheet would read as a formula starts with an apostrophe; the TSV has every code exactly as it was given.
        </p>
        @if (adding() && !fromSnomed()) {
          <app-map-add-codes heading="Add codes" [existing]="codes()" [room]="room()" [busy]="addingRows()" (add)="addRows($event)" (cancelled)="adding.set(false)" />
        }

        @if (matching(); as progress) {
          <p class="matching" role="status">
            {{ words().matching }}: {{ progress.done | number }} of {{ progress.total | number }}.
            <button type="button" class="link-button" (click)="stopMatching()">Stop</button>
          </p>
        } @else if (unchecked().length > 0) {
          <p class="matching">
            {{ unchecked().length | number }} {{ unchecked().length === 1 ? words().row + ' has' : words().rows + ' have' }} {{ words().unchecked }}.
            <button type="button" class="link-button" (click)="matchTerms()">Match remaining</button>
          </p>
        }
        @if (matchError(); as message) {
          <p class="field-error" role="alert">{{ message }}</p>
        }
        @if (counts().SUGGESTED > 0 && !matching()) {
          <div class="matching bulk">
            <p>
              {{ counts().SUGGESTED | number }} {{ counts().SUGGESTED === 1 ? words().row + ' has' : words().rows + ' have' }}
              suggestions waiting. Tick the ones to decide together, or tick them all with the box at the top of the
              table. The same words can still name different things, so look before you accept.
            </p>
            @if (ticked().length > 0) {
              <div class="actions" role="group" aria-label="Ticked suggestions">
                <strong>{{ ticked().length | number }} ticked</strong>
                <button type="button" class="button button-primary button-small" [disabled]="deciding()" (click)="acceptTicked()">Accept</button>
                <button type="button" class="button button-secondary button-small" [disabled]="deciding()" (click)="dismissTicked()">Dismiss</button>
                <button type="button" class="link-button" [disabled]="deciding()" (click)="checked.set(none)">Untick all</button>
              </div>
              @if (tickedWithSeveral() > 0) {
                <p class="meta">
                  {{ tickedWithSeveral() | number }} of them {{ tickedWithSeveral() === 1 ? 'has' : 'have' }} several
                  suggestions. Accept leaves {{ tickedWithSeveral() === 1 ? 'it' : 'those' }} for you to choose between;
                  Dismiss drops them all.
                </p>
              }
            }
          </div>
        }
        @if (bulkResult(); as message) {
          <p class="matching" role="status">{{ message }}</p>
        }

        <section class="workbench">
            @if (counts().total === 0) {
              <p class="muted" role="status">This map has no {{ words().rows }} yet. Add some to start.</p>
            } @else {
              <p class="meta">
                Click a row to open its targets, and again to close them. Arrow keys move between rows, Space opens
                or closes one, Enter accepts a single suggestion, X ticks a suggestion and N marks a row as having no
                map. Click a column heading to sort by it, and type under it to filter.
              </p>
              <p class="meta" role="status">
                {{ filtered() ? 'Showing ' + shown().length + ' of ' + counts().total + ' ' + words().rows + '.' : '' }}
                @if (filtered()) {
                  <button type="button" class="link-button" (click)="clearFilters()">Clear filters</button>
                }
                @if (sort()) {
                  <button type="button" class="link-button" (click)="sort.set(null)">Back to the original order</button>
                }
              </p>
              <div class="table-scroll">
                <table class="map-table">
                  <thead>
                    <tr>
                      @if (counts().SUGGESTED > 0) {
                        <th scope="col" class="tick">
                          <input type="checkbox" [checked]="allTicked()" [indeterminate]="someTicked()" [disabled]="suggestedShown().length === 0"
                                 [attr.aria-label]="'Tick all ' + suggestedShown().length + ' suggested ' + words().rows + ' shown'" (change)="tickAll($any($event.target).checked)" />
                        </th>
                      }
                      @for (column of columns; track column) {
                        <th scope="col" [attr.aria-sort]="ariaSort(column)">
                          <button type="button" class="sort" (click)="sortBy(column)">
                            {{ heading(column) }}
                            <span aria-hidden="true">{{ sort()?.column !== column ? '↕' : sort()?.descending ? '▼' : '▲' }}</span>
                          </button>
                          @if (column === 'status') {
                            <select id="status-filter" class="column-filter" aria-label="Filter by status" (change)="setStatusFilter($any($event.target).value)">
                              <option value="ALL" [selected]="statusFilter() === 'ALL'">All</option>
                              @for (status of statuses; track status) {
                                <option [value]="status" [selected]="statusFilter() === status">{{ statusLabel(status) }} ({{ counts()[status] }})</option>
                              }
                            </select>
                          } @else {
                            <input type="text" class="column-filter" autocomplete="off" [attr.data-filter]="column" [attr.aria-label]="'Filter by ' + heading(column).toLowerCase()"
                                   [value]="filters()[column]" (input)="setFilter(column, $any($event.target).value)" />
                          }
                        </th>
                      }
                    </tr>
                  </thead>
                  <tbody>
                    @if (shown().length === 0) {
                      <tr class="detail">
                        <td [attr.colspan]="counts().SUGGESTED > 0 ? 5 : 4" class="muted">No {{ words().rows }} match the filters.</td>
                      </tr>
                    }
                    @for (row of pageRows(); track row.id) {
                      <tr tabindex="0" [attr.data-row]="row.id" [class.selected]="row.id === selectedId()" [attr.aria-expanded]="row.id === selectedId()"
                          (click)="toggle(row)" (keydown)="onRowKey($event, row)">
                        @if (counts().SUGGESTED > 0) {
                          <!-- Clicks and keys stop here, so ticking a row does not also open it. -->
                          <td class="tick" (click)="$event.stopPropagation()" (keydown)="$event.stopPropagation()">
                            @if (row.status === 'SUGGESTED') {
                              <input type="checkbox" [checked]="checked().has(row.id)" [attr.aria-label]="'Tick ' + row.code + ' ' + row.term" (change)="tick(row, $any($event.target).checked)" />
                            }
                          </td>
                        }
                        <td class="mono">{{ row.code }}</td>
                        <td>{{ row.term }}</td>
                        <td><span class="badge" [class.badge-success]="row.status === 'MAPPED'" [class.badge-warning]="row.status === 'SUGGESTED'">{{ statusLabel(row.status) }}</span></td>
                        <td>
                          @for (target of row.targets; track target.code) {
                            <div [class.suggestion]="row.status === 'SUGGESTED'">
                              {{ target.term }} <span class="meta mono">{{ target.code }}</span>
                              @if (target.relationship !== 'EQUIVALENT') {
                                <span class="meta">&nbsp;({{ relationshipLabel(target.relationship) }})</span>
                              }
                            </div>
                          }
                        </td>
                      </tr>
                      @if (row.id === selectedId()) {
                        <!-- Under its own row, so the table keeps the full width of the page. -->
                        <tr class="detail">
                          <td [attr.colspan]="counts().SUGGESTED > 0 ? 5 : 4">
                            <app-map-row-detail [row]="row" [direction]="map.direction" [branch]="map.branch" [localCodes]="localCodes()" [saving]="saving()" [error]="rowError()"
                                                (update)="save(row, $event)" (remove)="deleteRow(row)" (details)="shownConcept.set($event)" (refused)="rowError.set($event)" />
                          </td>
                        </tr>
                      }
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
        }

        @if (addingConcepts()) {
          <app-map-add-concepts [branch]="map.branch" [inList]="codes()" (add)="addConcepts($event)" (details)="shownConcept.set($event)"
                                (importList)="addingConcepts.set(false); importing.set(true)" (closed)="addingConcepts.set(false)" />
        }
        @if (importing()) {
          <app-identifier-import [branch]="map.branch" [inList]="codes()" [maxMembers]="room()" (added)="addConcepts($event)" (closed)="importing.set(false)" />
        }
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

    .bulk p {
      margin: 0;
    }

    .bulk .actions {
      align-items: center;
      margin-top: var(--space-3);
    }

    .tick {
      width: 1%;
      cursor: default;
    }

    .tabs {
      display: flex;
      gap: var(--space-2);
      border-bottom: 1px solid var(--border-subtle);
    }

    .tabs button {
      padding: var(--space-2) var(--space-4);
      margin-bottom: -1px;
      font: inherit;
      font-weight: var(--fw-semibold);
      color: var(--text-muted);
      cursor: pointer;
      background: none;
      border: 0;
      border-bottom: 3px solid transparent;
    }

    .tabs button:hover {
      color: var(--color-primary);
    }

    .tabs button.current {
      color: var(--color-primary);
      border-bottom-color: var(--color-primary);
    }

    .workbench {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
    }

    .map-table {
      width: 100%;
    }

    .map-table th {
      vertical-align: top;
    }

    /* A heading that sorts: it reads as the heading it is, with an arrow for the order. */
    .sort {
      display: flex;
      gap: var(--space-2);
      align-items: baseline;
      padding: 0;
      font: inherit;
      font-weight: inherit;
      color: inherit;
      cursor: pointer;
      background: none;
      border: 0;
    }

    .column-filter {
      display: block;
      width: 100%;
      min-width: 6rem;
      margin-top: var(--space-2);
      font-weight: 400;
    }

    .detail > td {
      padding: var(--space-3) var(--space-4) var(--space-5);
      background: var(--surface-inset);
    }

    .map-table tbody tr:not(.detail) {
      cursor: pointer;
    }

    .map-table tbody tr.selected {
      background: var(--surface-inset);
    }

    .suggestion {
      font-style: italic;
      color: var(--text-muted);
    }
  `,
})
export class MapPage implements OnInit {
  private readonly api = inject(MapApi);
  private readonly router = inject(Router);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly id = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));

  protected readonly statuses: RowStatus[] = ['UNMAPPED', 'SUGGESTED', 'MAPPED', 'NO_MAP'];

  protected readonly map = signal<CodeMap | null>(null);
  protected readonly fromSnomed = computed(() => this.map()?.direction === 'FROM_SNOMED');
  protected readonly words = computed(() => WORDS[this.map()?.direction ?? 'TO_SNOMED']);
  protected readonly relationships = computed(() => relationshipsFor(this.map()?.direction ?? 'TO_SNOMED'));
  /** The codes targets are chosen from, in a map from SNOMED CT. Empty otherwise. */
  protected readonly localCodes = signal<SourceRow[]>([]);
  /** Each local code with the concepts mapped to it, in a map from SNOMED CT. */
  protected readonly localLines = computed(() => localCodeLines(this.localCodes(), this.rows()));
  protected readonly unmappedLocalCodes = computed(() => this.localLines().filter((line) => line.mappedFrom.length === 0).length);
  /** Which view of a map from SNOMED CT is showing: the concepts being mapped, or the local codes. */
  protected readonly tab = signal<'concepts' | 'local'>('concepts');
  /** True while the dialog for searching for concepts to add is open, in a map from SNOMED CT. */
  protected readonly addingConcepts = signal(false);
  /** True while the dialog for importing concepts is open. */
  protected readonly importing = signal(false);
  private readonly rows = signal<MapRow[]>([]);
  protected readonly loadError = signal<string | null>(null);
  protected readonly pageError = signal<string | null>(null);

  protected readonly statusFilter = signal<StatusFilter>('ALL');
  protected readonly columns: Column[] = ['code', 'term', 'status', 'target'];
  protected readonly filters = signal<Record<TextColumn, string>>(NO_FILTERS);
  /** The column the table is sorted by, or null for the order the codes were given in. */
  protected readonly sort = signal<{ column: Column; descending: boolean } | null>(null);
  protected readonly filtered = computed(() => this.statusFilter() !== 'ALL' || Object.values(this.filters()).some((text) => text.trim() !== ''));
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

  /** The suggested rows ticked to be accepted or dismissed together. */
  protected readonly checked = signal<ReadonlySet<number>>(new Set());
  protected readonly none: ReadonlySet<number> = new Set();
  /** True while ticked rows are being accepted or dismissed. */
  protected readonly deciding = signal(false);
  /** What the last accept or dismiss of ticked rows did. */
  protected readonly bulkResult = signal<string | null>(null);

  /** True while the form for adding local codes is open, in either direction of map. */
  protected readonly adding = signal(false);
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
  /** How many more rows the map can take: local codes in a map to SNOMED CT, concepts in a map from it. */
  protected readonly room = computed(() => MAX_MAP_ROWS - this.rows().length);
  /** How many more local codes a map from SNOMED CT can take. */
  protected readonly localRoom = computed(() => MAX_MAP_ROWS - this.localCodes().length);
  /** Rows whose term has not been looked for on the other side of the map. */
  protected readonly unchecked = computed(() => this.rows().filter((row) => row.status === 'UNMAPPED' && !row.termChecked));
  /** Ticked rows that are still suggestions. A row decided some other way drops out by itself. */
  protected readonly ticked = computed(() => this.rows().filter((row) => row.status === 'SUGGESTED' && this.checked().has(row.id)));
  protected readonly tickedWithSeveral = computed(() => this.ticked().filter((row) => row.targets.length > 1).length);

  protected readonly shown = computed(() => {
    const status = this.statusFilter();
    const filters = this.filters();
    const matching = this.rows().filter(
      (row) =>
        (status === 'ALL' || row.status === status) &&
        hasWords(row.code, filters.code) &&
        hasWords(row.term, filters.term) &&
        hasWords(row.targets.map((target) => `${target.code} ${target.term}`).join(' '), filters.target),
    );
    const sort = this.sort();
    if (sort === null) {
      return matching;
    }
    const direction = sort.descending ? -1 : 1;
    // Copied before sorting, so the order the codes were given in is still there to go back to.
    return [...matching].sort((a, b) => direction * this.compare(a, b, sort.column));
  });
  /** The suggested rows the filters let through, on every page: what the box in the table heading ticks. */
  protected readonly suggestedShown = computed(() => this.shown().filter((row) => row.status === 'SUGGESTED'));
  protected readonly allTicked = computed(() => this.suggestedShown().length > 0 && this.suggestedShown().every((row) => this.checked().has(row.id)));
  protected readonly someTicked = computed(() => !this.allTicked() && this.suggestedShown().some((row) => this.checked().has(row.id)));
  protected readonly pageCount = computed(() => Math.ceil(this.shown().length / PAGE_SIZE));
  protected readonly pageRows = computed(() => this.shown().slice(this.page() * PAGE_SIZE, (this.page() + 1) * PAGE_SIZE));

  ngOnInit(): void {
    forkJoin({ map: this.api.get(this.id), rows: this.api.rows(this.id), localCodes: this.api.localCodes(this.id) }).subscribe({
      next: ({ map, rows, localCodes }) => {
        this.map.set(map);
        this.rows.set(rows);
        this.localCodes.set(localCodes);
        // New codes are term matched straight away, without being asked.
        this.matchTerms();
      },
      error: (error: unknown) => this.loadError.set(describeMapError(error)),
    });
  }

  /** Changes the view of a map from SNOMED CT. A half filled form for adding local codes does not follow. */
  protected show(tab: 'concepts' | 'local'): void {
    this.tab.set(tab);
    this.adding.set(false);
  }

  protected statusLabel(status: RowStatus): string {
    return STATUS_LABELS[status];
  }

  protected relationshipLabel(relationship: Relationship): string {
    return this.relationships().find((option) => option.value === relationship)?.label ?? relationship;
  }

  protected downloadUrl(format: 'CSV' | 'TSV'): string {
    return mapDownloadUrl(this.id, format);
  }

  protected setStatusFilter(status: StatusFilter): void {
    this.statusFilter.set(status);
    this.page.set(0);
  }

  protected setFilter(column: TextColumn, text: string): void {
    this.filters.update((filters) => ({ ...filters, [column]: text }));
    this.page.set(0);
  }

  protected clearFilters(): void {
    this.filters.set(NO_FILTERS);
    this.setStatusFilter('ALL');
  }

  protected heading(column: Column): string {
    return column === 'code' ? this.words().source : column === 'target' ? this.words().target : column === 'term' ? 'Term' : 'Status';
  }

  /** First click sorts a column upwards, the second downwards, the third puts the original order back. */
  protected sortBy(column: Column): void {
    const sort = this.sort();
    if (sort?.column !== column) {
      this.sort.set({ column, descending: false });
    } else {
      this.sort.set(sort.descending ? null : { column, descending: true });
    }
    this.page.set(0);
  }

  protected ariaSort(column: Column): 'ascending' | 'descending' | null {
    const sort = this.sort();
    return sort?.column !== column ? null : sort.descending ? 'descending' : 'ascending';
  }

  private compare(a: MapRow, b: MapRow, column: Column): number {
    if (column === 'status') {
      return this.statuses.indexOf(a.status) - this.statuses.indexOf(b.status);
    }
    if (column === 'target') {
      return (a.targets[0]?.term ?? '').localeCompare(b.targets[0]?.term ?? '', undefined, { sensitivity: 'base' });
    }
    // Numeric, so D2 comes before D10 and concept identifiers sort as numbers.
    return a[column].localeCompare(b[column], undefined, { numeric: true, sensitivity: 'base' });
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

  protected tick(row: MapRow, on: boolean): void {
    this.checked.update((checked) => {
      const next = new Set(checked);
      if (on) {
        next.add(row.id);
      } else {
        next.delete(row.id);
      }
      return next;
    });
  }

  /** Ticks or unticks every suggested row the filters let through, not only those on this page. */
  protected tickAll(on: boolean): void {
    this.checked.update((checked) => {
      const next = new Set(checked);
      for (const row of this.suggestedShown()) {
        if (on) {
          next.add(row.id);
        } else {
          next.delete(row.id);
        }
      }
      return next;
    });
  }

  protected acceptTicked(): void {
    this.decide(this.api.acceptSuggestions(this.id, this.ticked().map((row) => row.id)), (after) => {
      const accepted = after.filter((row) => row.status === 'MAPPED').length;
      const left = after.filter((row) => row.status === 'SUGGESTED').length;
      const leftOver = left === 0 ? '' : ` ${left} left to choose by hand, as ${left === 1 ? 'it has' : 'they have'} several suggestions.`;
      return `Accepted ${accepted} ${accepted === 1 ? 'suggestion' : 'suggestions'}.${leftOver}`;
    });
  }

  protected dismissTicked(): void {
    this.decide(this.api.dismissSuggestions(this.id, this.ticked().map((row) => row.id)), (after) => {
      const dismissed = after.filter((row) => row.status === 'UNMAPPED').length;
      return `Dismissed the suggestions of ${dismissed} ${dismissed === 1 ? this.words().row : this.words().rows}.`;
    });
  }

  private decide(request: Observable<MapRow[]>, describe: (after: MapRow[]) => string): void {
    this.deciding.set(true);
    this.pageError.set(null);
    this.bulkResult.set(null);
    request.subscribe({
      next: (after) => {
        this.replaceRows(after);
        this.checked.set(this.none);
        this.deciding.set(false);
        this.bulkResult.set(describe(after));
      },
      error: (error: unknown) => {
        this.deciding.set(false);
        this.pageError.set(describeMapError(error));
      },
    });
  }

  /** Adds concepts from the import dialog as new rows, in a map from SNOMED CT. */
  protected addConcepts(concepts: Concept[]): void {
    this.pageError.set(null);
    this.api.addConcepts(this.id, concepts.map((concept) => concept.conceptId)).subscribe({
      next: () => this.reloadAndMatch(),
      error: (error: unknown) => this.pageError.set(describeMapError(error)),
    });
  }

  private reloadAndMatch(): void {
    forkJoin({ rows: this.api.rows(this.id), localCodes: this.api.localCodes(this.id) }).subscribe({
      next: ({ rows, localCodes }) => {
        this.rows.set(rows);
        this.localCodes.set(localCodes);
        this.addingRows.set(false);
        this.adding.set(false);
        this.matchTerms();
      },
      error: (error: unknown) => this.addFailed(error),
    });
  }

  protected deleteRow(row: MapRow): void {
    if (!confirm(`Remove ${row.code} ${row.term} from the map? Its targets go with it.`)) {
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

  protected addRows(rows: SourceRow[]): void {
    if (rows.length === 0) {
      return;
    }
    this.addingRows.set(true);
    this.pageError.set(null);
    this.api.addLocalCodes(this.id, rows).subscribe({
      next: () => this.reloadAndMatch(),
      error: (error: unknown) => this.addFailed(error),
    });
  }

  private addFailed(error: unknown): void {
    this.addingRows.set(false);
    this.pageError.set(describeMapError(error));
  }

  /** Opens a row's detail, or closes it if it is the one already open. */
  protected toggle(row: MapRow): void {
    this.selectedId.set(this.selectedId() === row.id ? null : row.id);
  }

  /** Mapping is repetitive work, so the common decisions can be made without leaving the keyboard. */
  protected onRowKey(event: KeyboardEvent, row: MapRow): void {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const rows = this.pageRows();
      const next = rows[rows.findIndex((candidate) => candidate.id === row.id) + (event.key === 'ArrowDown' ? 1 : -1)];
      if (next) {
        this.selectedId.set(next.id);
        this.host.nativeElement.querySelector<HTMLElement>(`tr[data-row="${next.id}"]`)?.focus();
      }
    } else if (event.key === ' ') {
      // Stops the page scrolling, which is what Space does otherwise.
      event.preventDefault();
      this.toggle(row);
    } else if (event.key === 'Escape') {
      this.selectedId.set(null);
    } else if (event.key === 'Enter' && row.status === 'SUGGESTED' && row.targets.length === 1 && !this.saving()) {
      event.preventDefault();
      this.save(row, acceptUpdate(row, row.targets[0]));
    } else if (event.key.toLowerCase() === 'x' && row.status === 'SUGGESTED') {
      event.preventDefault();
      this.tick(row, !this.checked().has(row.id));
    } else if (event.key.toLowerCase() === 'n' && row.status !== 'NO_MAP' && !this.saving()) {
      event.preventDefault();
      this.save(row, noMapUpdate(row));
    }
  }

  protected save(row: MapRow, update: RowUpdate): void {
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
