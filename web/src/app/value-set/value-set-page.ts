import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import {
  ExpansionPage,
  UpgradeAvailability,
  ValueSet,
  ValueSetApi,
  ValueSetUpgrade,
  ValueSetVersion,
  describeError,
  upgradeReportUrl,
} from '../core/value-set-api';

import { ConceptDetails } from './concept-details';
import { UpgradeDialog, describeChanges } from './upgrade-dialog';

const PAGE_SIZE = 50;

@Component({
  selector: 'app-value-set-page',
  imports: [RouterLink, DatePipe, ConceptDetails, UpgradeDialog],
  styles: `
    .facts {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2) var(--space-6);
      margin: 0;

      div {
        display: flex;
        flex-direction: column;
      }

      dt {
        font-size: var(--fs-caption);
        font-weight: var(--fw-semibold);
        color: var(--text-muted);
        text-transform: uppercase;
        letter-spacing: 0.04em;
      }

      dd {
        margin: 0;
        font-weight: var(--fw-semibold);
        color: var(--snomed-navy);
      }
    }
  `,
  template: `
    <main class="page">
      <p><a routerLink="/">Back to the library</a></p>

      @if (error(); as message) {
        <div class="error-panel" role="alert">
          <h1>This value set could not be loaded</h1>
          <p>{{ message }}</p>
        </div>
      } @else if (valueSet(); as set) {
        <div class="card-heading">
          <h1>{{ set.name }}</h1>
          <span class="badge" [class.badge-success]="set.latestVersion !== null">
            {{ set.latestVersion !== null ? 'Published · v' + set.latestVersion : 'Draft' }}
          </span>
          @if (set.unpublishedChanges) {
            <span class="badge">Unpublished changes</span>
          }
          @if (set.deprecated || set.allVersionsDeprecated) {
            <span class="badge badge-warning">Deprecated</span>
          }
        </div>
        @if (set.deprecated) {
          <div class="warning-panel" role="status">
            <h2>This value set is deprecated</h2>
            <p>{{ set.deprecationReason }}</p>
          </div>
        } @else if (versionInUseDeprecated()) {
          <div class="warning-panel" role="status">
            <h2>Every published version of this value set is deprecated</h2>
            <p>The newest one is shown. See the version history for the reasons.</p>
          </div>
        }
        <dl class="facts" aria-label="What this value set is built on">
          <div>
            <dt>Code system</dt>
            <dd>{{ codeSystemName() }} <span class="mono">({{ set.codeSystem }})</span></dd>
          </div>
          <div>
            <dt>Release</dt>
            <dd>
              @if (set.codeSystemVersion) {
                {{ set.codeSystemVersion }}
              } @else {
                Live branch, not a release
              }
            </dd>
          </div>
        </dl>
        <p class="lead">{{ set.description }}</p>
        @if (set.tags.length > 0) {
          <ul class="tags" aria-label="Tags">
            @for (tag of set.tags; track tag) {
              <li class="tag">{{ tag }}</li>
            }
          </ul>
        }

        @if (downloadTarget(); as target) {
          <div class="actions">
            <a class="button button-secondary" [href]="downloadUrl(target, false)" download>
              Download {{ downloadLabel(target) }} (TSV)
            </a>
            <a class="button button-secondary" [href]="downloadUrl(target, true)" download>
              Download {{ downloadLabel(target) }} (FHIR JSON)
            </a>
            <span class="meta">
              Downloads what is shown below. TSV in the Global Patient Set layout: SNOMED CT ID, FSN, PT and
              active flag, in US English. FHIR R4 ValueSet with the definition and the concept list as its expansion.
            </span>
          </div>
        }

        @if (set.ownedByMe && upgradeAvailability()?.available) {
          <div class="warning-panel" role="status">
            <h2>A newer release of {{ set.codeSystem }} is available</h2>
            <p>
              This value set is built on
              {{ set.codeSystemVersion ? 'release ' + set.codeSystemVersion : 'the live branch, not a release' }}.
              Release {{ upgradeAvailability()?.latestVersion }} is out. See what upgrading would change before
              anything is saved.
            </p>
            <div class="actions">
              <button type="button" class="button button-primary" (click)="upgrading.set(true)">
                Upgrade to {{ upgradeAvailability()?.latestVersion }}
              </button>
            </div>
          </div>
        }

        @if (set.ownedByMe) {
          @if (actionError(); as message) {
            <div class="error-panel" role="alert">
              <h2>{{ message.title }}</h2>
              <p>{{ message.detail }}</p>
            </div>
          }
          <div class="actions">
            @switch (mode()) {
              @case ('confirmDelete') {
                @if (set.latestVersion === null) {
                  <span>Delete this value set? This cannot be undone.</span>
                } @else {
                  <div class="warning-panel grow" role="status">
                    <h2>Delete a published value set?</h2>
                    <p>
                      Every version is deprecated, but this value set was published
                      @if (set.visibility === 'COMMUNITY') { and shared with the community }
                      and may already be in use or referred to by others. Deleting it removes
                      every version and its concept lists for everyone. This cannot be undone.
                    </p>
                  </div>
                }
                <button type="button" class="button button-danger" [disabled]="busy()" (click)="delete()">
                  Yes, delete
                </button>
                <button type="button" class="button button-secondary" (click)="mode.set('view')">Keep it</button>
              }
              @case ('publish') {
                <div class="field grow">
                  <label for="publish-note">What changed in version {{ nextVersion() }}? (optional)</label>
                  <input id="publish-note" type="text" maxlength="500" autocomplete="off" #note />
                  <p class="meta">
                    Publishing stores the concepts this value set has now as version {{ nextVersion() }}.
                    That is what other people see until you publish again.
                  </p>
                </div>
                <button type="button" class="button button-primary" [disabled]="busy()" (click)="publish(note.value)">
                  Publish version {{ nextVersion() }}
                </button>
                <button type="button" class="button button-secondary" (click)="mode.set('view')">Cancel</button>
              }
              @case ('confirmDiscard') {
                <span>Discard the changes made since version {{ set.latestVersion }}? This cannot be undone.</span>
                <button type="button" class="button button-danger" [disabled]="busy()" (click)="discardChanges()">
                  Yes, discard
                </button>
                <button type="button" class="button button-secondary" (click)="mode.set('view')">Keep them</button>
              }
              @case ('deprecate') {
                <div class="field grow">
                  <label for="deprecation-reason">Why should {{ deprecationLabel() }} no longer be used?</label>
                  <input id="deprecation-reason" type="text" maxlength="500" autocomplete="off" #reason />
                  <p class="meta">
                    It stays visible, marked as deprecated with this reason, and can be restored later.
                    @if (deprecationTarget() !== 'set') {
                      Other people are shown the newest version that is not deprecated.
                    }
                  </p>
                </div>
                <button type="button" class="button button-danger" [disabled]="busy()" (click)="deprecate(reason.value)">
                  Deprecate {{ deprecationLabel() }}
                </button>
                <button type="button" class="button button-secondary" (click)="mode.set('view')">Cancel</button>
              }
              @default {
                @if (canPublish()) {
                  <button type="button" class="button button-primary" (click)="open('publish')">Publish</button>
                }
                <a class="button button-secondary" [routerLink]="['/value-sets', set.id, 'edit']">Edit</a>
                @if (set.unpublishedChanges) {
                  <button type="button" class="button button-secondary" (click)="open('confirmDiscard')">
                    Discard changes
                  </button>
                }
                @if (set.latestVersion === null) {
                  <button type="button" class="button button-secondary" (click)="open('confirmDelete')">Delete</button>
                } @else if (set.deprecated) {
                  <button type="button" class="button button-secondary" [disabled]="busy()" (click)="restore('set')">
                    Restore value set
                  </button>
                } @else {
                  <button type="button" class="button button-secondary" (click)="startDeprecating('set')">
                    Deprecate value set
                  </button>
                }
                @if (set.allVersionsDeprecated) {
                  <button type="button" class="button button-danger" (click)="open('confirmDelete')">Delete</button>
                }
              }
            }
          </div>
        }

        <div class="columns">
          <section aria-labelledby="definition-heading">
            <h2 id="definition-heading" class="overline">Definition</h2>
            @if (set.type === 'MANUAL') {
              <p>A list of concepts picked by hand.</p>
              <p class="meta">
                {{ definitionLabel() }} Code system {{ set.codeSystem }}, {{ releaseLabel(definition().branch) }}.
                Terms are as they were in that release when each concept was added or upgraded.
              </p>
            } @else {
              <pre class="ecl ecl-block">{{ definition().ecl }}</pre>
              <p class="meta">
                {{ definitionLabel() }} Code system {{ set.codeSystem }}, {{ releaseLabel(definition().branch) }}.
              </p>
            }

            <h2 id="versions-heading" class="overline">Versions</h2>
            @if (versions(); as list) {
              @if (list.length === 0) {
                <p class="muted">This value set has not been published yet.</p>
              } @else {
                <ul class="version-list" aria-labelledby="versions-heading">
                  @if (set.unpublishedChanges) {
                    <li>
                      <button type="button" class="version" [attr.aria-pressed]="selectedVersion() === null" (click)="select(null)">
                        <span class="version-name">Working draft</span>
                        <span class="meta">Not published yet</span>
                      </button>
                    </li>
                  }
                  @for (version of list; track version.number) {
                    <li>
                      <button type="button" class="version" [attr.aria-pressed]="isShown(version)" (click)="select(version.number)">
                        <span class="version-name">Version {{ version.number }}</span>
                        <span class="meta">
                          {{ version.publishedAt | date: 'd MMM y' }} · {{ version.conceptCount }} concepts when published
                        </span>
                        @if (version.note) {
                          <span>{{ version.note }}</span>
                        }
                        @if (!version.frozen) {
                          <span class="meta">Concept list not stored; shown as Snowstorm evaluates it now.</span>
                        }
                        @if (version.deprecated) {
                          <span><span class="badge badge-warning">Deprecated</span> {{ version.deprecationReason }}</span>
                        }
                      </button>
                      @if (set.ownedByMe) {
                        @if (version.deprecated) {
                          <button type="button" class="link-button" [disabled]="busy()" (click)="restore(version.number)">
                            Restore version {{ version.number }}
                          </button>
                        } @else {
                          <button type="button" class="link-button" (click)="startDeprecating(version.number)">
                            Deprecate version {{ version.number }}
                          </button>
                        }
                      }
                    </li>
                  }
                </ul>
              }
            } @else if (versionsError()) {
              <p class="muted" role="alert">The version history could not be loaded.</p>
            } @else {
              <p class="muted" role="status">Loading versions…</p>
            }

            @if (upgrades(); as list) {
              @if (list.length > 0) {
                <h2 id="upgrades-heading" class="overline">Release upgrades</h2>
                <ul class="version-list" aria-labelledby="upgrades-heading">
                  @for (upgrade of list; track upgrade.id) {
                    <li>
                      <span class="version-name">
                        {{ upgrade.fromVersion ?? 'Live branch' }} → {{ upgrade.toVersion }}
                      </span>
                      <span class="meta">
                        {{ upgrade.upgradedAt | date: 'd MMM y' }} ·
                        {{ upgrade.publishedVersion !== null ? 'published as version ' + upgrade.publishedVersion : 'saved as a draft' }}
                      </span>
                      <span class="meta">{{ describeChanges(upgrade.changeCounts) }}</span>
                      <a class="link-button" [href]="reportUrl(upgrade)" download>Download change report (CSV)</a>
                    </li>
                  }
                </ul>
              }
            }
          </section>

          <section aria-labelledby="expansion-heading" class="wide">
            <h2 id="expansion-heading" class="overline">Expansion</h2>
            @if (expansionError(); as message) {
              <div class="error-panel" role="alert">
                <h3>The expansion could not be loaded</h3>
                <p>{{ message }}</p>
                <button type="button" class="button button-secondary" (click)="loadExpansion(offset())">
                  Try again
                </button>
              </div>
            } @else if (expansion(); as page) {
              <p class="meta">{{ rangeLabel() }}</p>
              <p class="meta">
                Preferred terms are the US English ones where a concept has one. The tag after each term is its language.
              </p>
              <div class="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Concept ID</th>
                      <th scope="col">Preferred term</th>
                      <th scope="col">Fully specified name</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (concept of page.items; track concept.conceptId) {
                      <tr>
                        <td class="mono">{{ concept.conceptId }}</td>
                        <td>
                          <button type="button" class="link-button" (click)="shownConcept.set(concept.conceptId)">
                            {{ concept.preferredTerm }}
                          </button>
                          @if (concept.language) {
                            <span class="lang" [attr.title]="'Preferred term language: ' + concept.language">{{ concept.language }}</span>
                          }
                          @if (concept.active === false) {
                            <span class="badge badge-warning" title="Kept in the list after this release made it inactive">Inactive</span>
                          }
                        </td>
                        <td>{{ concept.fullySpecifiedName }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
              <div class="pager">
                <button type="button" class="button button-secondary" [disabled]="!hasPrevious()" (click)="previous()">
                  Previous
                </button>
                <button type="button" class="button button-secondary" [disabled]="!hasNext()" (click)="next()">
                  Next
                </button>
              </div>
            } @else {
              <p class="muted" role="status">
                {{ set.type === 'MANUAL' ? 'Loading concepts…' : 'Expanding against Snowstorm…' }}
              </p>
            }
          </section>
        </div>
        @if (upgrading()) {
          <app-upgrade-dialog
            [valueSetId]="set.id"
            [canPublish]="!set.deprecated"
            [unpublishedChanges]="set.unpublishedChanges"
            [nextVersion]="nextVersion()"
            (upgraded)="load(true)"
            (closed)="upgrading.set(false)"
          />
        }
        @if (shownConcept(); as conceptId) {
          <app-concept-details [conceptId]="conceptId" [branch]="set.branch" (closed)="shownConcept.set(null)" />
        }
      } @else {
        <p class="muted" role="status">Loading value set…</p>
      }
    </main>
  `,
})
export class ValueSetPage implements OnInit {
  private readonly api = inject(ValueSetApi);
  private readonly router = inject(Router);
  private readonly id = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));

  protected readonly valueSet = signal<ValueSet | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly versions = signal<ValueSetVersion[] | null>(null);
  protected readonly versionsError = signal(false);
  /** The version being looked at, or null for what the value set shows by default. */
  protected readonly selectedVersion = signal<number | null>(null);
  protected readonly expansion = signal<ExpansionPage | null>(null);
  protected readonly expansionError = signal<string | null>(null);
  protected readonly offset = signal(0);

  /** Null until known; only asked for when the signed-in person owns the set. */
  protected readonly upgradeAvailability = signal<UpgradeAvailability | null>(null);
  protected readonly upgrades = signal<ValueSetUpgrade[] | null>(null);
  /** Display names of code systems, by short name. Empty until loaded, or if Snowstorm cannot say. */
  private readonly codeSystemNames = signal<Record<string, string>>({});
  protected readonly codeSystemName = computed(() => {
    const shortName = this.valueSet()?.codeSystem ?? '';
    return this.codeSystemNames()[shortName] ?? shortName;
  });
  protected readonly upgrading = signal(false);
  protected readonly describeChanges = describeChanges;

  /** The concept whose details are open, if any. */
  protected readonly shownConcept = signal<string | null>(null);

  protected readonly mode = signal<'view' | 'publish' | 'confirmDelete' | 'confirmDiscard' | 'deprecate'>('view');
  /** What the deprecation form is about: the whole value set, or one version number. */
  protected readonly deprecationTarget = signal<'set' | number>('set');
  protected readonly deprecationLabel = computed(() => {
    const target = this.deprecationTarget();
    return target === 'set' ? 'this value set' : `version ${target}`;
  });
  protected readonly versionInUseDeprecated = computed(() => {
    const inUse = this.versions()?.find((version) => version.number === this.valueSet()?.latestVersion);
    return inUse?.deprecated ?? false;
  });
  protected readonly busy = signal(false);
  protected readonly actionError = signal<{ title: string; detail: string } | null>(null);

  protected readonly canPublish = computed(() => {
    const set = this.valueSet();
    return set !== null && !set.deprecated && (set.latestVersion === null || set.unpublishedChanges);
  });
  protected readonly nextVersion = computed(() => (this.valueSet()?.latestVersion ?? 0) + 1);

  /** The ECL and branch on screen: a chosen version, or the value set's current definition. */
  protected readonly definition = computed(() => {
    const chosen = this.versions()?.find((version) => version.number === this.selectedVersion());
    return chosen ?? this.valueSet() ?? { ecl: '', branch: '' };
  });
  protected readonly definitionLabel = computed(() => {
    const set = this.valueSet();
    const shown = this.selectedVersion() ?? (set?.unpublishedChanges ? null : set?.latestVersion ?? null);
    if (shown !== null) {
      return `Version ${shown}.`;
    }
    return set?.latestVersion === null ? 'Draft, not published yet.' : 'Working draft, not published yet.';
  });

  protected readonly hasPrevious = computed(() => this.offset() > 0);
  protected readonly hasNext = computed(() => {
    const page = this.expansion();
    return page !== null && page.offset + page.items.length < page.total;
  });
  protected readonly rangeLabel = computed(() => {
    const page = this.expansion();
    if (page === null || page.total === 0) {
      return this.valueSet()?.type === 'MANUAL' ? 'No concepts have been added.' : 'No concepts match this definition.';
    }
    return `Showing ${page.offset + 1} to ${page.offset + page.items.length} of ${page.total} concepts`;
  });

  ngOnInit(): void {
    this.load(true);
    // Only for the display name: the short name is shown instead if this fails.
    this.api.codeSystems().subscribe({
      next: (codeSystems) =>
        this.codeSystemNames.set(Object.fromEntries(codeSystems.map((codeSystem) => [codeSystem.shortName, codeSystem.name]))),
      error: () => undefined,
    });
  }

  /**
   * What the download buttons fetch: the version being looked at. For the owner that may be the
   * working draft. Null when there is nothing to download yet.
   */
  protected readonly downloadTarget = computed<number | 'draft' | null>(() => {
    const set = this.valueSet();
    if (set === null) {
      return null;
    }
    const chosen = this.selectedVersion();
    if (chosen !== null) {
      return chosen;
    }
    if (set.ownedByMe && (set.unpublishedChanges || set.latestVersion === null)) {
      return 'draft';
    }
    return set.latestVersion;
  });

  protected downloadUrl(target: number | 'draft', fhir: boolean): string {
    const format = fhir ? '&format=fhir' : '';
    return `/api/value-sets/${this.id}/download?version=${target}${format}`;
  }

  protected downloadLabel(target: number | 'draft'): string {
    return target === 'draft' ? 'working draft' : `version ${target}`;
  }

  protected releaseLabel(branch: string): string {
    const release = /\/(\d{4}-\d{2}-\d{2})$/.exec(branch)?.[1];
    return release ? `release ${release}` : `live branch ${branch}`;
  }

  protected reportUrl(upgrade: ValueSetUpgrade): string {
    return upgradeReportUrl(this.id, upgrade.id);
  }

  protected isShown(version: ValueSetVersion): boolean {
    const selected = this.selectedVersion();
    if (selected !== null) {
      return selected === version.number;
    }
    const set = this.valueSet();
    return set !== null && !set.unpublishedChanges && set.latestVersion === version.number;
  }

  protected select(version: number | null): void {
    this.selectedVersion.set(version);
    this.loadExpansion(0);
  }

  protected open(mode: 'publish' | 'confirmDelete' | 'confirmDiscard'): void {
    this.actionError.set(null);
    this.mode.set(mode);
  }

  protected loadExpansion(offset: number): void {
    this.offset.set(offset);
    this.expansion.set(null);
    this.expansionError.set(null);
    const version = this.selectedVersion();
    const request =
      version === null
        ? this.api.expansion(this.id, offset, PAGE_SIZE)
        : this.api.versionExpansion(this.id, version, offset, PAGE_SIZE);
    request.subscribe({
      next: (page) => this.expansion.set(page),
      error: (error: unknown) => this.expansionError.set(describeError(error)),
    });
  }

  protected previous(): void {
    this.loadExpansion(Math.max(0, this.offset() - PAGE_SIZE));
  }

  protected next(): void {
    this.loadExpansion(this.offset() + PAGE_SIZE);
  }

  protected publish(note: string): void {
    this.busy.set(true);
    this.api.publish(this.id, note.trim()).subscribe({
      next: () => {
        this.busy.set(false);
        this.mode.set('view');
        this.load();
      },
      error: (error: unknown) => this.failAction('The value set could not be published', error),
    });
  }

  protected startDeprecating(target: 'set' | number): void {
    this.actionError.set(null);
    this.deprecationTarget.set(target);
    this.mode.set('deprecate');
  }

  protected deprecate(reason: string): void {
    if (reason.trim() === '') {
      this.actionError.set({ title: 'A reason is needed', detail: 'Say why it should no longer be used.' });
      return;
    }
    const target = this.deprecationTarget();
    const request =
      target === 'set' ? this.api.deprecate(this.id, reason.trim()) : this.api.deprecateVersion(this.id, target, reason.trim());
    this.run(request, 'It could not be deprecated');
  }

  protected restore(target: 'set' | number): void {
    const request = target === 'set' ? this.api.restore(this.id) : this.api.restoreVersion(this.id, target);
    this.run(request, 'It could not be restored');
  }

  protected discardChanges(): void {
    this.run(this.api.discardChanges(this.id), 'The changes could not be discarded');
  }

  /** Runs an owner action, then reloads the page content so every part reflects the result. */
  private run(request: Observable<unknown>, failureTitle: string): void {
    this.busy.set(true);
    this.actionError.set(null);
    request.subscribe({
      next: () => {
        this.busy.set(false);
        this.mode.set('view');
        this.load();
      },
      error: (error: unknown) => this.failAction(failureTitle, error),
    });
  }

  protected delete(): void {
    this.busy.set(true);
    this.api.delete(this.id).subscribe({
      next: () => void this.router.navigate(['/']),
      error: (error: unknown) => this.failAction('The value set could not be deleted', error),
    });
  }

  private failAction(title: string, error: unknown): void {
    this.busy.set(false);
    this.mode.set('view');
    this.actionError.set({ title, detail: describeError(error) });
  }

  /**
   * @param withUpgrades also refresh the release information, which changes only on an upgrade,
   *        so other actions do not ask Snowstorm for it again
   */
  protected load(withUpgrades = false): void {
    this.selectedVersion.set(null);
    this.api.get(this.id).subscribe({
      next: (set) => {
        this.valueSet.set(set);
        this.loadExpansion(0);
        if (withUpgrades && set.ownedByMe) {
          // Only a hint: if Snowstorm cannot say, the page works as before without the button.
          this.api.upgradeAvailability(this.id).subscribe({
            next: (availability) => this.upgradeAvailability.set(availability),
            error: () => this.upgradeAvailability.set(null),
          });
        }
      },
      error: (error: unknown) => this.error.set(describeError(error)),
    });
    this.versionsError.set(false);
    this.api.versions(this.id).subscribe({
      next: (versions) => this.versions.set(versions),
      error: () => this.versionsError.set(true),
    });
    if (withUpgrades) {
      this.api.upgrades(this.id).subscribe({
        next: (upgrades) => this.upgrades.set(upgrades),
        error: () => this.upgrades.set(null),
      });
    }
  }
}
