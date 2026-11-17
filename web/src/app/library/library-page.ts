import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SIGN_IN_URL, Session } from '../core/session';
import { ValueSet, ValueSetApi, describeError } from '../core/value-set-api';

@Component({
  selector: 'app-library-page',
  imports: [RouterLink, DatePipe],
  template: `
    <main class="page">
      <div class="card-heading">
        <h1 class="grow-heading">Library</h1>
        @if (signedIn()) {
          <a class="button button-primary" routerLink="/value-sets/new">New value set</a>
        }
      </div>
      @if (!signedIn()) {
        <p class="lead">
          Published SNOMED CT value sets shared with the community. Anyone can view them and download
          them in the Global Patient Set layout.
          <a [href]="signInUrl">Sign in</a> with your SNOMED International account to build and share your own.
        </p>
      }

      @if (error(); as message) {
        <div class="error-panel" role="alert">
          <h2>The library could not be loaded</h2>
          <p>{{ message }}</p>
          <button type="button" class="button button-secondary" (click)="load()">Try again</button>
        </div>
      } @else if (valueSets(); as sets) {
        <div class="filters">
          <div class="field">
            <label for="library-search">Search by name or tag</label>
            <input id="library-search" type="text" autocomplete="off" [value]="query()" (input)="query.set($any($event.target).value)" />
          </div>
          <div class="field">
            <label for="concept-search-placeholder">Find sets containing a concept</label>
            <input id="concept-search-placeholder" type="text" disabled placeholder="Coming soon" aria-describedby="concept-search-note" />
            <span id="concept-search-note" class="visually-hidden">This search is not available yet.</span>
          </div>
        </div>

        @if (deprecatedCount() > 0) {
          <label class="checkbox">
            <input type="checkbox" [checked]="hideDeprecated()" (change)="hideDeprecated.set($any($event.target).checked)" />
            <span>Hide deprecated value sets ({{ deprecatedCount() }})</span>
          </label>
        }

        <p class="meta" role="status">{{ countLabel() }}</p>

        @if (shown().length > 0) {
          <div class="table-scroll">
            <table class="library-table">
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Tags</th>
                  <th scope="col">Defined by</th>
                  <th scope="col">Edition</th>
                  <th scope="col">Release</th>
                  @if (signedIn()) {
                    <th scope="col">Owner</th>
                  }
                  <th scope="col">Updated</th>
                </tr>
              </thead>
              <tbody>
                @for (set of shown(); track set.id) {
                  <tr [class.row-deprecated]="isDeprecated(set)">
                    <td>
                      <div class="set-heading">
                        <a class="set-name" [routerLink]="['/value-sets', set.id]">{{ set.name }}</a>
                        <span class="badge" [class.badge-success]="set.latestVersion !== null">
                          {{ set.latestVersion !== null ? 'v' + set.latestVersion : 'Draft' }}
                        </span>
                        @if (set.visibility === 'PRIVATE') {
                          <span class="badge">Private</span>
                        }
                        @if (isDeprecated(set)) {
                          <span class="badge badge-warning" [title]="set.deprecationReason ?? 'Every version is deprecated'">Deprecated</span>
                        }
                      </div>
                      @if (set.description) {
                        <p class="meta one-line" [title]="set.description">{{ set.description }}</p>
                      }
                    </td>
                    <td>
                      <ul class="tags">
                        @for (tag of set.tags; track tag) {
                          <li>
                            <button type="button" class="tag tag-button" (click)="query.set(tag)" [attr.aria-label]="'Show sets tagged ' + tag">
                              {{ tag }}
                            </button>
                          </li>
                        }
                      </ul>
                    </td>
                    <td>{{ set.type === 'MANUAL' ? 'Picked list' : 'ECL' }}</td>
                    <td [title]="set.codeSystem">{{ editionName(set) }}</td>
                    <td class="no-wrap">
                      @if (set.codeSystemVersion) {
                        {{ set.codeSystemVersion }}
                      } @else {
                        <span class="muted" title="Built on the live branch, not a release">Live branch</span>
                      }
                    </td>
                    @if (signedIn()) {
                      <td>{{ set.ownedByMe ? 'You' : set.ownerName }}</td>
                    }
                    <td class="no-wrap">{{ set.updatedAt | date: 'd MMM y' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      } @else {
        <p class="muted" role="status">Loading value sets…</p>
      }
    </main>
  `,
})
export class LibraryPage implements OnInit {
  private readonly api = inject(ValueSetApi);
  private readonly session = inject(Session);

  protected readonly signedIn = computed(() => this.session.state().status === 'signedIn');
  protected readonly signInUrl = SIGN_IN_URL;

  protected readonly valueSets = signal<ValueSet[] | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly query = signal('');
  /** Display names of editions, by code system short name. Empty until loaded, or if Snowstorm cannot say. */
  private readonly editionNames = signal<Record<string, string>>({});

  /** Deprecated value sets are listed and marked, so people can see they are not for new use. */
  protected readonly hideDeprecated = signal(false);
  protected readonly deprecatedCount = computed(() => (this.valueSets() ?? []).filter((set) => this.isDeprecated(set)).length);
  private readonly listed = computed(() =>
    (this.valueSets() ?? []).filter((set) => !this.hideDeprecated() || !this.isDeprecated(set)),
  );

  /** Value sets whose name or one of whose tags contains every word that was typed. */
  protected readonly shown = computed(() => {
    const words = this.query().toLowerCase().split(/\s+/).filter((word) => word !== '');
    return this.listed().filter((set) => {
      const searchable = [set.name, ...set.tags].join(' ').toLowerCase();
      return words.every((word) => searchable.includes(word));
    });
  });

  protected readonly countLabel = computed(() => {
    const total = this.listed().length;
    if (total === 0) {
      return 'No value sets yet.';
    }
    if (this.query().trim() === '') {
      return total === 1 ? '1 value set' : `${total} value sets`;
    }
    return this.shown().length === 0 ? `None of the ${total} value sets match.` : `${this.shown().length} of ${total} value sets`;
  });

  /** Deprecated as a whole, or with nothing left in use because every version is deprecated. */
  protected isDeprecated(set: ValueSet): boolean {
    return set.deprecated || set.allVersionsDeprecated;
  }

  ngOnInit(): void {
    this.load();
    // Only for display names: the short name is shown instead if this fails.
    this.api.codeSystems().subscribe({
      next: (codeSystems) =>
        this.editionNames.set(Object.fromEntries(codeSystems.map((codeSystem) => [codeSystem.shortName, codeSystem.name]))),
      error: () => undefined,
    });
  }

  protected editionName(set: ValueSet): string {
    return this.editionNames()[set.codeSystem] ?? set.codeSystem;
  }

  protected load(): void {
    this.error.set(null);
    this.valueSets.set(null);
    this.api.list().subscribe({
      next: (sets) => this.valueSets.set(sets),
      error: (error: unknown) => this.error.set(describeError(error)),
    });
  }
}
