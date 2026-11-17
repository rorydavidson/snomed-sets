import {
  Component,
  ElementRef,
  OnInit,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import {
  UpgradeChangeType,
  UpgradePreview,
  ValueSetApi,
  ValueSetUpgrade,
  describeError,
  upgradeReportUrl,
} from '../core/value-set-api';

/** Long lists are cut short in the dialog; the downloaded report always has every line. */
const ROWS_SHOWN = 200;
/** Inactive concepts shown at once; search or filter to reach the rest. Bulk actions cover them all. */
const GROUPS_SHOWN = 300;
/** Below this many inactive concepts the search and filter are not worth the space. */
const FILTER_FROM = 8;

type InactiveMember = UpgradePreview['inactivated'][number];
type Filter = 'all' | 'replaced' | 'unreplaced' | 'kept';

/** One table row: a suggested replacement, or an inactive concept with none. */
interface ReviewRow {
  member: InactiveMember;
  replacement: InactiveMember['replacements'][number] | null;
  /** The first row of its inactive concept, which carries that concept's details. */
  first: boolean;
}

type State =
  | { status: 'loading' }
  | { status: 'error'; message: string; preview: UpgradePreview | null }
  | { status: 'review'; preview: UpgradePreview }
  | { status: 'saving'; preview: UpgradePreview }
  | { status: 'done'; upgrade: ValueSetUpgrade };

/** How each kind of change is described: once, then more than once. */
const CHANGE_LABELS: Record<UpgradeChangeType, [string, string]> = {
  ADDED: ['added', 'added'],
  REMOVED: ['removed', 'removed'],
  INACTIVATED: ['inactive and taken out', 'inactive and taken out'],
  INACTIVE_KEPT: ['inactive but kept', 'inactive but kept'],
  REPLACEMENT_ADDED: ['replacement added', 'replacements added'],
  REPLACEMENT_DECLINED: ['replacement declined', 'replacements declined'],
  TERM_CHANGED: ['term changed', 'terms changed'],
};

/** A one-line summary of an upgrade's report, for example "3 added, 1 removed". */
export function describeChanges(counts: ValueSetUpgrade['changeCounts']): string {
  const parts = (Object.keys(CHANGE_LABELS) as UpgradeChangeType[])
    .filter((type) => (counts[type] ?? 0) > 0)
    .map((type) => {
      const count = counts[type] ?? 0;
      return `${count} ${CHANGE_LABELS[type][count === 1 ? 0 : 1]}`;
    });
  return parts.length === 0 ? 'No concepts changed' : parts.join(', ');
}

/** REPLACED_BY becomes "Replaced by". */
export function humanise(code: string | null): string {
  if (!code) {
    return '';
  }
  const words = code.toLowerCase().replaceAll('_', ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function key(inactiveConceptId: string, replacementConceptId: string): string {
  return `${inactiveConceptId}>${replacementConceptId}`;
}

/** Suggestions that can be accepted: those not already in the list. */
function selectable(member: InactiveMember): InactiveMember['replacements'] {
  return member.replacements.filter((replacement) => !replacement.alreadyInSet);
}

/**
 * Moves a value set onto the latest release of its code system. It first shows what would
 * change, saving nothing; the owner can decline any suggested replacement and choose to keep
 * the result as a draft or publish it. Afterwards the change report can be downloaded.
 */
@Component({
  selector: 'app-upgrade-dialog',
  styles: `
    .upgrade {
      width: min(960px, calc(100vw - 2 * var(--space-4)));
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

    /* Header and footer stay put; only the changes in between scroll. */
    .upgrade-frame {
      display: flex;
      flex-direction: column;
      max-height: calc(100vh - 2 * var(--space-6));
    }

    .upgrade-head,
    .upgrade-foot {
      flex: none;
      padding: var(--space-4) var(--space-6);
    }

    .upgrade-head {
      border-bottom: 1px solid var(--border-subtle);
    }

    .upgrade-foot {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
      background: var(--surface-subtle);
      border-top: 1px solid var(--border-subtle);
    }

    .upgrade-body {
      display: flex;
      flex: 1 1 auto;
      flex-direction: column;
      gap: var(--space-3);
      min-height: 0;
      padding: var(--space-4) var(--space-6);
      overflow-y: auto;
    }

    .stats {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-1) var(--space-4);
      margin: 0;
      padding: 0;
      list-style: none;

      strong {
        color: var(--snomed-navy);
      }
    }

    .toolbar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);

      input[type='search'],
      select {
        padding: var(--space-1) var(--space-2);
        font: inherit;
        border: 1px solid var(--border-default);
        border-radius: var(--radius-sm);
      }

      input[type='search'] {
        flex: 1 1 200px;
      }
    }

    .button-small {
      padding: var(--space-1) var(--space-3);
      font-size: var(--fs-body-sm);
    }

    .review {
      max-height: 45vh;
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
        z-index: 1;
        background: var(--surface-subtle);
      }

      th,
      td {
        padding: var(--space-1) var(--space-2);
        text-align: left;
        vertical-align: top;
      }

      tr.group-start td {
        border-top: 1px solid var(--border-subtle);
      }

      .arrow {
        width: 1.5rem;
        color: var(--text-muted);
        text-align: center;
      }

      td.removed {
        width: 45%;
        color: var(--text-muted);
      }

      td.removed.kept {
        color: var(--text-body);
        background: #fff8db;
        box-shadow: inset 3px 0 0 var(--warning);
      }

      td.candidate {
        background: var(--surface-subtle);
      }

      td.candidate.chosen {
        background: #e6f5f1;
        box-shadow: inset 3px 0 0 var(--success);
      }

      .candidate-pick {
        display: flex;
        align-items: flex-start;
        gap: var(--space-2);
        cursor: pointer;

        input {
          margin-top: 0.2em;
        }
      }

      .sub {
        display: block;
        font-size: var(--fs-caption);
        color: var(--text-muted);
      }
    }

    .association {
      display: inline-block;
      margin-right: var(--space-1);
      padding: 0 var(--space-1);
      font-size: var(--fs-caption);
      color: var(--snomed-navy);
      white-space: nowrap;
      background: var(--neutral-100);
      border-radius: var(--radius-sm);
    }

    .save-mode {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-1) var(--space-4);

      label {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        min-height: 32px;
        cursor: pointer;
      }
    }

    .list-scroll {
      max-height: 320px;
      overflow-y: auto;
    }

    fieldset {
      margin: 0;
      padding: 0;
      border: 0;
    }

    legend {
      float: left;
      margin-right: var(--space-2);
      font-weight: var(--fw-semibold);
      color: var(--snomed-navy);
    }
  `,
  template: `
    <dialog #dialog class="upgrade" aria-labelledby="upgrade-title" (close)="closed.emit()">
      <div class="upgrade-frame">
        @switch (state().status) {
          @case ('loading') {
            <div class="upgrade-head">
              <h2 id="upgrade-title">Checking what the new release changes…</h2>
              <p class="muted" role="status">
                Comparing the value set against both releases in Snowstorm.
              </p>
            </div>
            <div class="upgrade-foot">
              <div class="actions">
                <button type="button" class="button button-secondary" (click)="close()">
                  Cancel
                </button>
              </div>
            </div>
          }
          @case ('done') {
            @if (done(); as upgrade) {
              <div class="upgrade-head">
                <h2 id="upgrade-title">Upgraded to {{ upgrade.toVersion }}</h2>
              </div>
              <div class="upgrade-body">
                <p>
                  @if (upgrade.publishedVersion !== null) {
                    Published as version {{ upgrade.publishedVersion }}.
                  } @else {
                    Saved as a draft. Publish it when you are ready for others to use it.
                  }
                </p>
                <p class="meta">{{ summary(upgrade) }}</p>
                <p class="meta">The report stays available from the value set's upgrade history.</p>
              </div>
              <div class="upgrade-foot">
                <div class="actions">
                  <a class="button button-primary" [href]="reportUrl(upgrade)" download
                    >Download change report (CSV)</a
                  >
                  <button type="button" class="button button-secondary" (click)="close()">
                    Close
                  </button>
                </div>
              </div>
            }
          }
          @default {
            <div class="upgrade-head">
              @if (preview(); as change) {
                <h2 id="upgrade-title">
                  Upgrade to {{ change.codeSystem }} {{ change.toVersion }}
                </h2>
                <p class="meta">
                  From
                  {{
                    change.fromVersion
                      ? 'release ' + change.fromVersion
                      : 'the live branch ' + change.fromBranch
                  }}
                  to {{ change.toBranch }}. Nothing is saved until you choose Upgrade.
                </p>
              } @else {
                <h2 id="upgrade-title">The upgrade could not be prepared</h2>
              }
            </div>

            <div class="upgrade-body">
              @if (preview(); as change) {
                @if (change.type === 'ECL') {
                  <p>
                    The definition matches {{ change.conceptCountBefore }} concepts now and
                    {{ change.conceptCountBefore + change.added.length - change.removed.length }} in
                    the new release.
                  </p>
                  @if (change.added.length > 0) {
                    <details>
                      <summary>{{ change.added.length }} concepts added</summary>
                      <div class="review">
                        <table>
                          <tbody>
                            @for (
                              concept of change.added.slice(0, rowsShown);
                              track concept.conceptId
                            ) {
                              <tr>
                                <td class="mono">{{ concept.conceptId }}</td>
                                <td>{{ concept.preferredTerm }}</td>
                              </tr>
                            }
                          </tbody>
                        </table>
                      </div>
                      @if (change.added.length > rowsShown) {
                        <p class="meta">
                          And {{ change.added.length - rowsShown }} more, all listed in the report.
                        </p>
                      }
                    </details>
                  }
                  @if (change.removed.length > 0) {
                    <details>
                      <summary>{{ change.removed.length }} concepts removed</summary>
                      <div class="review">
                        <table>
                          <tbody>
                            @for (
                              concept of change.removed.slice(0, rowsShown);
                              track concept.conceptId
                            ) {
                              <tr>
                                <td class="mono">{{ concept.conceptId }}</td>
                                <td>{{ concept.preferredTerm }}</td>
                              </tr>
                            }
                          </tbody>
                        </table>
                      </div>
                      @if (change.removed.length > rowsShown) {
                        <p class="meta">
                          And {{ change.removed.length - rowsShown }} more, all listed in the
                          report.
                        </p>
                      }
                    </details>
                  }
                } @else if (change.inactivated.length === 0) {
                  <p>Every concept in the list is still active in the new release.</p>
                } @else {
                  <p>
                    {{ change.inactivated.length }} of {{ change.conceptCountBefore }} concepts are
                    inactive in the new release. On the left, ticked concepts are removed; untick
                    one to keep it in the list, flagged as inactive. On the right are the
                    replacements suggested by the historical association reference sets; ticked ones
                    are added.
                  </p>
                  <ul class="stats" aria-label="Summary">
                    <li>
                      <strong>{{ removedCount() }}</strong> will be removed
                    </li>
                    <li>
                      <strong>{{ change.inactivated.length - removedCount() }}</strong> kept as
                      inactive
                    </li>
                    <li>
                      <strong>{{ suggestionCount() }}</strong> suggested replacements
                    </li>
                    <li>
                      <strong>{{ acceptedCount() }}</strong> replacements will be added
                    </li>
                    <li>
                      <strong>{{ unreplacedCount() }}</strong> with no replacement
                    </li>
                  </ul>

                  <div class="toolbar">
                    @if (change.inactivated.length >= filterFrom) {
                      <label class="visually-hidden" for="upgrade-search"
                        >Search inactive concepts and replacements</label
                      >
                      <input
                        id="upgrade-search"
                        type="search"
                        placeholder="Search by term or concept ID"
                        autocomplete="off"
                        [value]="search()"
                        (input)="search.set($any($event.target).value)"
                      />
                      <label class="visually-hidden" for="upgrade-filter">Show</label>
                      <select
                        id="upgrade-filter"
                        [value]="filter()"
                        (change)="filter.set($any($event.target).value)"
                      >
                        <option value="all">All inactive concepts</option>
                        <option value="replaced">With a replacement</option>
                        <option value="unreplaced">With no replacement</option>
                        <option value="kept">Kept as inactive</option>
                      </select>
                    }
                    @if (change.inactivated.length > 1) {
                      <button
                        type="button"
                        class="button button-secondary button-small"
                        [disabled]="saving()"
                        (click)="setAllRemoved(true)"
                      >
                        Remove all inactive{{
                          filtered().length < change.inactivated.length ? ' shown' : ''
                        }}
                      </button>
                      <button
                        type="button"
                        class="button button-secondary button-small"
                        [disabled]="saving()"
                        (click)="setAllRemoved(false)"
                      >
                        Keep all inactive{{
                          filtered().length < change.inactivated.length ? ' shown' : ''
                        }}
                      </button>
                    }
                    @if (suggestionCount() > 1) {
                      <button
                        type="button"
                        class="button button-secondary button-small"
                        [disabled]="saving()"
                        (click)="setAll(true)"
                      >
                        Add all replacements{{
                          filtered().length < change.inactivated.length ? ' shown' : ''
                        }}
                      </button>
                      <button
                        type="button"
                        class="button button-secondary button-small"
                        [disabled]="saving()"
                        (click)="setAll(false)"
                      >
                        Add no replacements{{
                          filtered().length < change.inactivated.length ? ' shown' : ''
                        }}
                      </button>
                    }
                  </div>

                  @if (filtered().length === 0) {
                    <p class="muted">No inactive concepts match.</p>
                  } @else {
                    <div class="review">
                      <table aria-label="Inactive concepts and the replacements to add">
                        <thead>
                          <tr>
                            <th scope="col">Remove inactive concept?</th>
                            <th scope="col" class="arrow">
                              <span class="visually-hidden">replaced by</span>
                            </th>
                            <th scope="col">Add as replacement?</th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (
                            row of rows();
                            track row.member.conceptId + '>' + (row.replacement?.conceptId ?? '')
                          ) {
                            <tr [class.group-start]="row.first">
                              <td
                                class="removed"
                                [class.kept]="row.first && !isRemoved(row.member.conceptId)"
                              >
                                @if (row.first) {
                                  <label class="candidate-pick">
                                    <input
                                      type="checkbox"
                                      [attr.aria-label]="'Remove ' + row.member.term"
                                      [checked]="isRemoved(row.member.conceptId)"
                                      [disabled]="saving()"
                                      (change)="toggleRemoved(row.member.conceptId)"
                                    />
                                    <span>
                                      {{ row.member.term }}
                                      <span class="sub">
                                        <span class="mono">{{ row.member.conceptId }}</span>
                                        @if (row.member.reason) {
                                          · {{ humanise(row.member.reason) }}
                                        }
                                        ·
                                        {{
                                          isRemoved(row.member.conceptId)
                                            ? 'will be removed'
                                            : 'kept, flagged inactive'
                                        }}
                                      </span>
                                    </span>
                                  </label>
                                }
                              </td>
                              <td class="arrow" aria-hidden="true">→</td>
                              @if (row.replacement; as replacement) {
                                @if (replacement.alreadyInSet) {
                                  <td>
                                    <span class="association">{{
                                      humanise(replacement.association)
                                    }}</span>
                                    {{ replacement.term }}
                                    <span class="sub"
                                      ><span class="mono">{{ replacement.conceptId }}</span> ·
                                      already in the list</span
                                    >
                                  </td>
                                } @else {
                                  <td
                                    class="candidate"
                                    [class.chosen]="
                                      isAccepted(row.member.conceptId, replacement.conceptId)
                                    "
                                  >
                                    <label class="candidate-pick">
                                      <input
                                        type="checkbox"
                                        [checked]="
                                          isAccepted(row.member.conceptId, replacement.conceptId)
                                        "
                                        [disabled]="saving()"
                                        (change)="
                                          toggle(row.member.conceptId, replacement.conceptId)
                                        "
                                      />
                                      <span>
                                        <span class="association">{{
                                          humanise(replacement.association)
                                        }}</span>
                                        {{ replacement.term }}
                                        <span class="sub">
                                          <span class="mono">{{ replacement.conceptId }}</span> ·
                                          {{
                                            isAccepted(row.member.conceptId, replacement.conceptId)
                                              ? 'will be added'
                                              : 'not added'
                                          }}
                                        </span>
                                      </span>
                                    </label>
                                  </td>
                                }
                              } @else {
                                <td><span class="muted">No replacement suggested</span></td>
                              }
                            </tr>
                          }
                        </tbody>
                      </table>
                    </div>
                    @if (filtered().length > groupsShown) {
                      <p class="meta">
                        Showing the first {{ groupsShown }} of {{ filtered().length }}. Search to
                        find others; accept and decline all cover every one.
                      </p>
                    }
                  }
                }

                @if (change.termChanges.length > 0) {
                  <details>
                    <summary>{{ change.termChanges.length }} preferred terms changed</summary>
                    <div class="review">
                      <table>
                        <thead>
                          <tr>
                            <th scope="col">Concept ID</th>
                            <th scope="col">Was</th>
                            <th scope="col">Now</th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (
                            term of change.termChanges.slice(0, rowsShown);
                            track term.conceptId
                          ) {
                            <tr>
                              <td class="mono">{{ term.conceptId }}</td>
                              <td>{{ term.oldTerm }}</td>
                              <td>{{ term.newTerm }}</td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    </div>
                  </details>
                }
                @if (unchanged()) {
                  <p>
                    No concepts change. Only the release the value set is built on moves forward.
                  </p>
                }
              }

              @if (errorMessage(); as message) {
                <div class="error-panel" role="alert">
                  <h3>
                    {{
                      preview()
                        ? 'The upgrade could not be saved'
                        : 'The upgrade could not be prepared'
                    }}
                  </h3>
                  <p>{{ message }}</p>
                </div>
              }
            </div>

            <div class="upgrade-foot">
              @if (preview()) {
                <fieldset class="save-mode">
                  <legend>Save as</legend>
                  <label>
                    <input
                      type="radio"
                      name="save-mode"
                      [checked]="!publish()"
                      (change)="publish.set(false)"
                    />
                    <span>A draft, to publish later</span>
                  </label>
                  <label>
                    <input
                      type="radio"
                      name="save-mode"
                      [checked]="publish()"
                      [disabled]="!canPublish()"
                      (change)="publish.set(true)"
                    />
                    <span>Version {{ nextVersion() }}, published now</span>
                  </label>
                </fieldset>
                @if (!canPublish()) {
                  <p class="meta">
                    This value set is deprecated, so it cannot be published until it is restored.
                  </p>
                }
                @if (publish()) {
                  <div class="field">
                    <label for="upgrade-note"
                      >What changed in version {{ nextVersion() }}? (optional)</label
                    >
                    <input
                      id="upgrade-note"
                      type="text"
                      maxlength="500"
                      autocomplete="off"
                      [value]="note()"
                      (input)="note.set($any($event.target).value)"
                    />
                  </div>
                  @if (unpublishedChanges()) {
                    <p class="meta" role="status">
                      Your other unpublished edits are published in the same version.
                    </p>
                  }
                }
              }
              <div class="actions">
                @if (preview()) {
                  <button
                    type="button"
                    class="button button-primary"
                    [disabled]="saving()"
                    (click)="save()"
                  >
                    {{ saving() ? 'Upgrading…' : 'Upgrade' }}
                  </button>
                }
                <button type="button" class="button button-secondary" (click)="close()">
                  Cancel
                </button>
              </div>
            </div>
          }
        }
      </div>
    </dialog>
  `,
})
export class UpgradeDialog implements OnInit {
  private readonly api = inject(ValueSetApi);

  readonly valueSetId = input.required<number>();
  /** False for a deprecated value set, which can be upgraded only as a draft. */
  readonly canPublish = input(true);
  readonly unpublishedChanges = input(false);
  readonly nextVersion = input.required<number>();
  readonly closed = output<void>();
  /** Emitted once the upgrade is saved, so the page can show the new content. */
  readonly upgraded = output<ValueSetUpgrade>();

  protected readonly rowsShown = ROWS_SHOWN;
  protected readonly groupsShown = GROUPS_SHOWN;
  protected readonly filterFrom = FILTER_FROM;
  protected readonly search = signal('');
  protected readonly filter = signal<Filter>('all');
  protected readonly humanise = humanise;
  protected readonly state = signal<State>({ status: 'loading' });
  protected readonly publish = signal(false);
  protected readonly note = signal('');
  /** Accepted replacements, keyed by inactive concept and replacement. Every suggestion starts accepted. */
  private readonly accepted = signal(new Set<string>());
  /**
   * Inactive concepts to remove. All start removed, except those an earlier upgrade kept; any
   * the owner unticks stay in the list, flagged as inactive.
   */
  private readonly removed = signal(new Set<string>());

  protected readonly preview = computed(() => {
    const state = this.state();
    return state.status === 'review' || state.status === 'saving' || state.status === 'error'
      ? state.preview
      : null;
  });
  protected readonly done = computed(() => {
    const state = this.state();
    return state.status === 'done' ? state.upgrade : null;
  });
  protected readonly errorMessage = computed(() => {
    const state = this.state();
    return state.status === 'error' ? state.message : null;
  });
  protected readonly saving = computed(() => this.state().status === 'saving');

  /** The inactive concepts that match the search and filter. */
  protected readonly filtered = computed(() => {
    const words = this.search()
      .toLowerCase()
      .split(/\s+/)
      .filter((word) => word !== '');
    const filter = this.filter();
    return (this.preview()?.inactivated ?? []).filter((member) => {
      const replaced = selectable(member).length > 0;
      if (
        (filter === 'replaced' && !replaced) ||
        (filter === 'unreplaced' && replaced) ||
        (filter === 'kept' && this.removed().has(member.conceptId))
      ) {
        return false;
      }
      const searchable = [
        member.conceptId,
        member.term,
        ...member.replacements.flatMap((r) => [r.conceptId, r.term]),
      ]
        .join(' ')
        .toLowerCase();
      return words.every((word) => searchable.includes(word));
    });
  });

  /** Table rows for the shown inactive concepts: one per suggestion, or one saying there is none. */
  protected readonly rows = computed<ReviewRow[]>(() =>
    this.filtered()
      .slice(0, GROUPS_SHOWN)
      .flatMap((member): ReviewRow[] =>
        member.replacements.length === 0
          ? [{ member, replacement: null, first: true }]
          : member.replacements.map((replacement, index) => ({
              member,
              replacement,
              first: index === 0,
            })),
      ),
  );

  protected readonly suggestionCount = computed(() =>
    (this.preview()?.inactivated ?? []).reduce(
      (total, member) => total + selectable(member).length,
      0,
    ),
  );
  protected readonly acceptedCount = computed(() => this.accepted().size);
  protected readonly removedCount = computed(() => this.removed().size);
  protected readonly unreplacedCount = computed(
    () =>
      (this.preview()?.inactivated ?? []).filter((member) => selectable(member).length === 0)
        .length,
  );

  protected readonly unchanged = computed(() => {
    const preview = this.preview();
    return (
      preview !== null &&
      preview.added.length === 0 &&
      preview.removed.length === 0 &&
      preview.inactivated.length === 0 &&
      preview.termChanges.length === 0
    );
  });

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
    this.api.upgradePreview(this.valueSetId()).subscribe({
      next: (preview) => {
        const all = preview.inactivated.flatMap((member) =>
          selectable(member).map((replacement) => key(member.conceptId, replacement.conceptId)),
        );
        this.accepted.set(new Set(all));
        this.removed.set(
          new Set(
            preview.inactivated
              .filter((member) => !member.keptBefore)
              .map((member) => member.conceptId),
          ),
        );
        this.state.set({ status: 'review', preview });
      },
      error: (error: unknown) =>
        this.state.set({ status: 'error', message: describeError(error), preview: null }),
    });
  }

  protected isAccepted(inactiveConceptId: string, replacementConceptId: string): boolean {
    return this.accepted().has(key(inactiveConceptId, replacementConceptId));
  }

  protected toggle(inactiveConceptId: string, replacementConceptId: string): void {
    const next = new Set(this.accepted());
    const toggled = key(inactiveConceptId, replacementConceptId);
    if (!next.delete(toggled)) {
      next.add(toggled);
    }
    this.accepted.set(next);
  }

  protected isRemoved(conceptId: string): boolean {
    return this.removed().has(conceptId);
  }

  protected toggleRemoved(conceptId: string): void {
    const next = new Set(this.removed());
    if (!next.delete(conceptId)) {
      next.add(conceptId);
    }
    this.removed.set(next);
  }

  /** Removes or keeps every inactive concept that matches the search and filter. */
  protected setAllRemoved(remove: boolean): void {
    const next = new Set(this.removed());
    for (const member of this.filtered()) {
      if (remove) {
        next.add(member.conceptId);
      } else {
        next.delete(member.conceptId);
      }
    }
    this.removed.set(next);
  }

  /** Accepts or declines every suggestion for the inactive concepts that match the search and filter. */
  protected setAll(accept: boolean): void {
    const next = new Set(this.accepted());
    for (const member of this.filtered()) {
      for (const replacement of selectable(member)) {
        const toggled = key(member.conceptId, replacement.conceptId);
        if (accept) {
          next.add(toggled);
        } else {
          next.delete(toggled);
        }
      }
    }
    this.accepted.set(next);
  }

  protected save(): void {
    const preview = this.preview();
    if (preview === null) {
      return;
    }
    this.state.set({ status: 'saving', preview });
    const acceptedReplacements = [...this.accepted()].map((accepted) => {
      const [inactiveConceptId, replacementConceptId] = accepted.split('>');
      return { inactiveConceptId, replacementConceptId };
    });
    this.api
      .upgrade(this.valueSetId(), {
        targetVersion: preview.toVersion,
        acceptedReplacements,
        keptInactiveConceptIds: (preview.inactivated ?? [])
          .map((member) => member.conceptId)
          .filter((conceptId) => !this.removed().has(conceptId)),
        publish: this.publish() && this.canPublish(),
        note: this.note().trim(),
      })
      .subscribe({
        next: ({ upgrade }) => {
          this.state.set({ status: 'done', upgrade });
          this.upgraded.emit(upgrade);
        },
        error: (error: unknown) =>
          this.state.set({ status: 'error', message: describeError(error), preview }),
      });
  }

  protected summary(upgrade: ValueSetUpgrade): string {
    return describeChanges(upgrade.changeCounts);
  }

  protected reportUrl(upgrade: ValueSetUpgrade): string {
    return upgradeReportUrl(this.valueSetId(), upgrade.id);
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
