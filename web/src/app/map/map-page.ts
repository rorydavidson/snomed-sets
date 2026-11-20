import { DecimalPipe } from '@angular/common';
import { Component, ElementRef, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, forkJoin } from 'rxjs';
import {
  CodeMap,
  MATCH_CHUNK,
  MAX_MAP_ROWS,
  MAX_TARGETS,
  MapApi,
  MapRow,
  MapTarget,
  Relationship,
  RowStatus,
  RowUpdate,
  SourceRow,
  describeMapError,
  mapDownloadUrl,
  relationshipsFor,
} from '../core/map-api';
import { Concept } from '../core/value-set-api';
import { ConceptDetails } from '../value-set/concept-details';
import { ConceptPicker } from '../value-set/concept-picker';
import { IdentifierImport } from '../value-set/identifier-import';
import { CodeListInput } from './code-list-input';

const PAGE_SIZE = 50;
/** Snowstorm refuses shorter search terms, so the server does not look for them. */
const SHORTEST_SEARCH = 3;

type StatusFilter = RowStatus | 'ALL';

/** How many local codes are listed to choose from at once. The filter narrows a longer list. */
const LOCAL_CODES_SHOWN = 50;
/** The list of local codes nothing is mapped to scrolls, so it can show more before asking for a filter. */
const UNUSED_CODES_SHOWN = 500;

/** The words that differ between a map to SNOMED CT and a map from it. */
const WORDS = {
  TO_SNOMED: {
    rows: 'codes',
    row: 'code',
    source: 'Code',
    target: 'SNOMED CT',
    matching: 'Looking for each term in SNOMED CT',
    unchecked: 'not been looked for in SNOMED CT yet',
    suggested: 'a description with the same words',
    noMap: 'nothing in SNOMED CT fits',
    remove: 'Remove this code',
  },
  FROM_SNOMED: {
    rows: 'concepts',
    row: 'concept',
    source: 'SNOMED CT concept',
    target: 'Local code',
    matching: 'Looking for the names of each concept among the local terms',
    unchecked: 'not been looked for among the local terms yet',
    suggested: 'one of the names of this concept as its term',
    noMap: 'none of the local codes fits',
    remove: 'Remove this concept',
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
  imports: [RouterLink, DecimalPipe, ConceptPicker, ConceptDetails, CodeListInput, IdentifierImport],
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
          @if (fromSnomed()) {
            · {{ localCodes().length | number }} local {{ localCodes().length === 1 ? 'code' : 'codes' }} to choose from
          }
        </p>
        @if (fromSnomed() && localCodes().length > 0) {
          <p class="meta">
            @if (unusedLocalCodes().length === 0) {
              Every local code has a concept mapped to it.
            } @else {
              {{ unusedLocalCodes().length | number }} of the local codes {{ unusedLocalCodes().length === 1 ? 'has' : 'have' }} no concept mapped to {{ unusedLocalCodes().length === 1 ? 'it' : 'them' }} yet.
              <button type="button" class="link-button" [attr.aria-expanded]="showingUnused()" (click)="showingUnused.set(!showingUnused())">
                {{ showingUnused() ? 'Hide them' : 'Show them' }}
              </button>
            }
          </p>
        }

        <ul class="counts" aria-label="Progress">
          <li><strong>{{ counts().total | number }}</strong> {{ words().rows }}</li>
          <li><strong>{{ counts().MAPPED | number }}</strong> mapped</li>
          <li><strong>{{ counts().NO_MAP | number }}</strong> no map</li>
          <li><strong>{{ counts().SUGGESTED | number }}</strong> suggested</li>
          <li><strong>{{ counts().UNMAPPED | number }}</strong> unmapped</li>
        </ul>

        <div class="actions">
          @if (fromSnomed()) {
            <button type="button" class="button button-secondary" [attr.aria-expanded]="addingConcepts()" (click)="addingConcepts.set(!addingConcepts())">Add concepts</button>
          }
          <button type="button" class="button button-secondary" (click)="adding.set(!adding())">{{ fromSnomed() ? 'Add local codes' : 'Add codes' }}</button>
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

        @if (showingUnused() && unusedLocalCodes().length > 0) {
          <section class="panel" aria-labelledby="unused-heading">
            <h2 id="unused-heading">Local codes with nothing mapped to them</h2>
            <p class="meta">
              No concept in this map has one of these as an accepted target. A suggestion nobody has accepted does not
              count. To map to one, add a concept for it with Add concepts, then choose the code on that concept's row.
            </p>
            <div class="field">
              <label for="unused-filter">Find a local code or term</label>
              <input id="unused-filter" type="text" autocomplete="off" [value]="unusedFilter()" (input)="unusedFilter.set($any($event.target).value)" />
            </div>
            <p class="meta" role="status">{{ unusedLabel() }}</p>
            <ul class="pick-list unused">
              @for (local of unusedShown(); track local.code) {
                <li><span>{{ local.term }} <span class="meta mono">{{ local.code }}</span></span></li>
              }
            </ul>
            <div class="actions">
              <button type="button" class="button button-secondary" (click)="showingUnused.set(false)">Close</button>
            </div>
          </section>
        }

        @if (addingConcepts() && fromSnomed()) {
          <section class="panel" aria-labelledby="add-concepts-heading">
            <h2 id="add-concepts-heading">Add concepts</h2>
            <p class="meta">
              Search for concepts and add the ones to map. For many at once, import a list of identifiers or everything
              an ECL expression matches.
            </p>
            <app-concept-picker [branch]="map.branch" [inList]="codes()" (add)="addConcepts($event)" (details)="shownConcept.set($event)" />
            <div class="actions">
              <button type="button" class="button button-secondary" (click)="importing.set(true)">Import a list or ECL</button>
              <button type="button" class="button button-secondary" (click)="addingConcepts.set(false)">Close</button>
            </div>
          </section>
        }

        @if (adding()) {
          <section class="panel">
            <h2>{{ fromSnomed() ? 'Add local codes' : 'Add codes' }}</h2>
            <app-code-list-input [existing]="localCodeSet()" [room]="room()" (rowsChange)="newRows.set($event)" />
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
            <div class="actions">
              <div class="field">
                <label for="status-filter">Show</label>
                <select id="status-filter" (change)="setStatusFilter($any($event.target).value)">
                  <option value="ALL">All {{ words().rows }}</option>
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
              <p class="muted" role="status">{{ counts().total === 0 ? 'This map has no ' + words().rows + ' yet. Add some to start.' : 'No ' + words().rows + ' match.' }}</p>
            } @else {
              <p class="meta">
                Click a row to open its targets, and again to close them. Arrow keys move between rows, Space opens
                or closes one, Enter accepts a single suggestion, X ticks a suggestion and N marks a row as having no
                map.
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
                      <th scope="col">{{ words().source }}</th>
                      <th scope="col">Term</th>
                      <th scope="col">Status</th>
                      <th scope="col">{{ words().target }}</th>
                    </tr>
                  </thead>
                  <tbody>
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
                            <div class="panel">
              <div class="chosen">
              <h2><span class="mono">{{ row.code }}</span> {{ row.term }}</h2>
              @if (fromSnomed()) {
                <p class="meta">
                  {{ row.fullySpecifiedName }}
                  <button type="button" class="link-button" (click)="shownConcept.set(row.code)">Details</button>
                </p>
              }
              @if (rowError(); as message) {
                <p class="field-error" role="alert">{{ message }}</p>
              }

              @if (row.status === 'SUGGESTED') {
                <p>
                  {{ row.targets.length === 1 ? 'One ' + targetWord() + ' has' : row.targets.length + ' ' + targetWord() + 's have' }}
                  {{ words().suggested }}. Nothing is mapped until you accept one.
                </p>
              } @else if (row.status === 'NO_MAP') {
                <p>Marked as having no map: {{ words().noMap }}.</p>
              } @else if (row.targets.length === 0) {
                <p class="muted">{{ unmappedMessage(row) }}</p>
              }

              @if (row.targets.length > 0) {
                <ul class="targets">
                  @for (target of row.targets; track target.code) {
                    <li>
                      <div>
                        <strong>{{ target.term }}</strong>
                        <span class="meta mono">{{ target.code }}{{ target.fullySpecifiedName ? ' · ' + target.fullySpecifiedName : '' }}</span>
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
                            @for (option of relationships(); track option.value) {
                              <option [value]="option.value" [selected]="option.value === target.relationship">{{ option.label }}</option>
                            }
                          </select>
                          <button type="button" class="link-button" [disabled]="saving()" (click)="removeTarget(row, target)">Remove</button>
                        }
                        @if (!fromSnomed()) {
                          <button type="button" class="link-button" (click)="shownConcept.set(target.code)">Details</button>
                        }
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
                <button type="button" class="link-button" [disabled]="saving()" (click)="deleteRow(row)">{{ words().remove }}</button>
              </div>

              <div class="field">
                <label for="row-note">Note (optional)</label>
                <textarea id="row-note" rows="2" maxlength="500" [value]="row.note ?? ''" (change)="saveNote(row, $any($event.target).value)"></textarea>
              </div>

              </div>
              @if (row.status !== 'NO_MAP') {
                <div class="choose">
                @if (fromSnomed()) {
                  <h3>Choose a local code</h3>
                  @if (localCodes().length === 0) {
                    <p class="muted">This map has no local codes yet. Add some with Add local codes.</p>
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
                            <button type="button" class="button button-secondary button-small" [disabled]="saving()" (click)="addLocalTarget(row, local)"
                                    [attr.aria-label]="'Add ' + local.code + ' ' + local.term">Add</button>
                          }
                        </li>
                      }
                    </ul>
                  }
                } @else {
                  <h3>Choose a concept</h3>
                  <app-concept-picker [branch]="map.branch" [inList]="acceptedCodes()" (add)="addTargets(row, $event)" (details)="shownConcept.set($event)" />
                }
                </div>
              }
                            </div>
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

    .unused {
      max-height: 22rem;
      overflow-y: auto;
    }

    .panel {
      padding: var(--space-5);
      background: var(--surface-card);
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-md);
    }

    .workbench {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
    }

    .map-table {
      width: 100%;
    }

    /* The selected code's targets on the left, the concept search on the right. */
    .detail .panel {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-8);
      border-left: 4px solid var(--color-primary);
    }

    .detail .panel > div {
      display: flex;
      flex: 1 1 360px;
      flex-direction: column;
      gap: var(--space-3);
      min-width: 0;
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

  protected readonly map = signal<CodeMap | null>(null);
  protected readonly fromSnomed = computed(() => this.map()?.direction === 'FROM_SNOMED');
  protected readonly words = computed(() => WORDS[this.map()?.direction ?? 'TO_SNOMED']);
  /** What a target is called in a sentence. */
  protected readonly targetWord = computed(() => (this.fromSnomed() ? 'local code' : 'concept'));
  protected readonly relationships = computed(() => relationshipsFor(this.map()?.direction ?? 'TO_SNOMED'));
  /** The codes targets are chosen from, in a map from SNOMED CT. Empty otherwise. */
  protected readonly localCodes = signal<SourceRow[]>([]);
  protected readonly localFilter = signal('');
  protected readonly localCodesMatching = computed(() => {
    const words = this.localFilter().toLowerCase().split(/\s+/).filter((word) => word !== '');
    return this.localCodes().filter((local) => {
      const searchable = `${local.code} ${local.term}`.toLowerCase();
      return words.every((word) => searchable.includes(word));
    });
  });
  protected readonly localCodesShown = computed(() => this.localCodesMatching().slice(0, LOCAL_CODES_SHOWN));
  protected readonly localCodesLabel = computed(() => {
    const found = this.localCodesMatching().length;
    return found > LOCAL_CODES_SHOWN ? `${found} local codes. Showing the first ${LOCAL_CODES_SHOWN}; type to narrow them.` : `${found} local ${found === 1 ? 'code' : 'codes'}.`;
  });
  /** Local codes no row has as an accepted target, in a map from SNOMED CT. Suggestions do not count. */
  protected readonly unusedLocalCodes = computed(() => {
    const used = new Set<string>();
    for (const row of this.rows()) {
      if (row.status === 'MAPPED') {
        row.targets.forEach((target) => used.add(target.code));
      }
    }
    return this.localCodes().filter((local) => !used.has(local.code));
  });
  protected readonly showingUnused = signal(false);
  protected readonly unusedFilter = signal('');
  private readonly unusedMatching = computed(() => {
    const words = this.unusedFilter().toLowerCase().split(/\s+/).filter((word) => word !== '');
    return this.unusedLocalCodes().filter((local) => {
      const searchable = `${local.code} ${local.term}`.toLowerCase();
      return words.every((word) => searchable.includes(word));
    });
  });
  protected readonly unusedShown = computed(() => this.unusedMatching().slice(0, UNUSED_CODES_SHOWN));
  protected readonly unusedLabel = computed(() => {
    const found = this.unusedMatching().length;
    return found > UNUSED_CODES_SHOWN ? `${found} local codes. Showing the first ${UNUSED_CODES_SHOWN}; type to narrow them.` : `${found} local ${found === 1 ? 'code' : 'codes'}.`;
  });
  /** True while the panel for searching for concepts to add is open, in a map from SNOMED CT. */
  protected readonly addingConcepts = signal(false);
  /** True while the dialog for importing concepts is open. */
  protected readonly importing = signal(false);
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

  /** The suggested rows ticked to be accepted or dismissed together. */
  protected readonly checked = signal<ReadonlySet<number>>(new Set());
  protected readonly none: ReadonlySet<number> = new Set();
  /** True while ticked rows are being accepted or dismissed. */
  protected readonly deciding = signal(false);
  /** What the last accept or dismiss of ticked rows did. */
  protected readonly bulkResult = signal<string | null>(null);

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
  /** The local codes the map already has, wherever it keeps them, so none is added twice. */
  protected readonly localCodeSet = computed<ReadonlySet<string>>(() =>
    this.fromSnomed() ? new Set(this.localCodes().map((local) => local.code)) : this.codes(),
  );
  /** How many more local codes can be added, or in the import dialog how many more concepts. */
  protected readonly room = computed(() => MAX_MAP_ROWS - (this.adding() && this.fromSnomed() ? this.localCodes().length : this.rows().length));
  /** Rows whose term has not been looked for on the other side of the map. */
  protected readonly unchecked = computed(() => this.rows().filter((row) => row.status === 'UNMAPPED' && !row.termChecked));
  /** Ticked rows that are still suggestions. A row decided some other way drops out by itself. */
  protected readonly ticked = computed(() => this.rows().filter((row) => row.status === 'SUGGESTED' && this.checked().has(row.id)));
  protected readonly tickedWithSeveral = computed(() => this.ticked().filter((row) => row.targets.length > 1).length);

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
  /** The suggested rows the filters let through, on every page: what the box in the table heading ticks. */
  protected readonly suggestedShown = computed(() => this.shown().filter((row) => row.status === 'SUGGESTED'));
  protected readonly allTicked = computed(() => this.suggestedShown().length > 0 && this.suggestedShown().every((row) => this.checked().has(row.id)));
  protected readonly someTicked = computed(() => !this.allTicked() && this.suggestedShown().some((row) => this.checked().has(row.id)));
  protected readonly pageCount = computed(() => Math.ceil(this.shown().length / PAGE_SIZE));
  protected readonly pageRows = computed(() => this.shown().slice(this.page() * PAGE_SIZE, (this.page() + 1) * PAGE_SIZE));
  protected readonly selected = computed(() => this.rows().find((row) => row.id === this.selectedId()) ?? null);
  /** The selected row's targets, unless they are only suggestions. */
  protected readonly acceptedCodes = computed<ReadonlySet<string>>(() => {
    const row = this.selected();
    return new Set(row && row.status === 'MAPPED' ? row.targets.map((target) => target.code) : []);
  });

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

  /** Why an unmapped row has no suggestion. A term under three characters is never searched for. */
  protected unmappedMessage(row: MapRow): string {
    if (this.fromSnomed()) {
      return row.termChecked ? 'No local code has one of the names of this concept as its term. Choose one from the list.' : 'Not mapped yet.';
    }
    if (row.term.trim().length < SHORTEST_SEARCH) {
      return 'This term is too short to look for automatically. Search below to choose a concept.';
    }
    return row.termChecked ? 'No concept has exactly this term. Search below to choose one.' : 'Not mapped yet.';
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
    const have = new Set(kept.map((target) => target.code));
    const added = concepts
      .filter((concept) => !have.has(concept.conceptId))
      .map((concept) => ({ code: concept.conceptId, relationship: 'EQUIVALENT' as Relationship }));
    if (kept.length + added.length > MAX_TARGETS) {
      this.rowError.set(`A code can have at most ${MAX_TARGETS} targets.`);
      return;
    }
    if (added.length > 0) {
      this.save(row, { targets: [...kept, ...added], noMap: false, note: row.note });
    }
  }

  /** Adds a local code chosen from the list, in a map from SNOMED CT. */
  protected addLocalTarget(row: MapRow, local: SourceRow): void {
    const kept = row.status === 'MAPPED' ? row.targets.map(choice) : [];
    if (kept.length >= MAX_TARGETS) {
      this.rowError.set(`A concept can have at most ${MAX_TARGETS} targets.`);
      return;
    }
    this.save(row, { targets: [...kept, { code: local.code, relationship: 'EQUIVALENT' }], noMap: false, note: row.note });
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

  protected addRows(): void {
    const rows = this.newRows();
    if (!rows || rows.length === 0) {
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
      this.accept(row, row.targets[0]);
    } else if (event.key.toLowerCase() === 'x' && row.status === 'SUGGESTED') {
      event.preventDefault();
      this.tick(row, !this.checked().has(row.id));
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

function choice(target: MapTarget): { code: string; relationship: Relationship } {
  return { code: target.code, relationship: target.relationship };
}
