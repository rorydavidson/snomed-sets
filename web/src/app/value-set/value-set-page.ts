import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ExpansionPage, ValueSet, ValueSetApi, ValueSetVersion, describeError } from '../core/value-set-api';

const PAGE_SIZE = 50;

@Component({
  selector: 'app-value-set-page',
  imports: [RouterLink, DatePipe],
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
        </div>
        <p class="lead">{{ set.description }}</p>

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
                <span>Delete this value set? This cannot be undone.</span>
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
                    Publishing freezes this definition as version {{ nextVersion() }}. Other people see it
                    until you publish again.
                  </p>
                </div>
                <button type="button" class="button button-primary" [disabled]="busy()" (click)="publish(note.value)">
                  Publish version {{ nextVersion() }}
                </button>
                <button type="button" class="button button-secondary" (click)="mode.set('view')">Cancel</button>
              }
              @default {
                @if (canPublish()) {
                  <button type="button" class="button button-primary" (click)="open('publish')">Publish</button>
                }
                <a class="button button-secondary" [routerLink]="['/value-sets', set.id, 'edit']">Edit</a>
                <button type="button" class="button button-secondary" (click)="open('confirmDelete')">Delete</button>
              }
            }
          </div>
        }

        <div class="columns">
          <section aria-labelledby="definition-heading">
            <h2 id="definition-heading" class="overline">Definition</h2>
            <pre class="ecl ecl-block">{{ definition().ecl }}</pre>
            <p class="meta">{{ definitionLabel() }} Evaluated against {{ definition().branch }}.</p>

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
                      </button>
                    </li>
                  }
                </ul>
              }
            } @else if (versionsError()) {
              <p class="muted" role="alert">The version history could not be loaded.</p>
            } @else {
              <p class="muted" role="status">Loading versions…</p>
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
                        <td>{{ concept.preferredTerm }}</td>
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
              <p class="muted" role="status">Expanding against Snowstorm…</p>
            }
          </section>
        </div>
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

  protected readonly mode = signal<'view' | 'publish' | 'confirmDelete'>('view');
  protected readonly busy = signal(false);
  protected readonly actionError = signal<{ title: string; detail: string } | null>(null);

  protected readonly canPublish = computed(() => {
    const set = this.valueSet();
    return set !== null && (set.latestVersion === null || set.unpublishedChanges);
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
      return 'No concepts match this definition.';
    }
    return `Showing ${page.offset + 1} to ${page.offset + page.items.length} of ${page.total} concepts`;
  });

  ngOnInit(): void {
    this.load();
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

  protected open(mode: 'publish' | 'confirmDelete'): void {
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

  private load(): void {
    this.selectedVersion.set(null);
    this.api.get(this.id).subscribe({
      next: (set) => {
        this.valueSet.set(set);
        this.loadExpansion(0);
      },
      error: (error: unknown) => this.error.set(describeError(error)),
    });
    this.versionsError.set(false);
    this.api.versions(this.id).subscribe({
      next: (versions) => this.versions.set(versions),
      error: () => this.versionsError.set(true),
    });
  }
}
