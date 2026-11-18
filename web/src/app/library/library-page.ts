import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Session } from '../core/session';
import { ConceptSearchHit, ValueSet, ValueSetApi, describeError } from '../core/value-set-api';
import { LibraryHero } from './library-hero';
import { LibrarySearch, LibrarySearchResult } from './library-search';
import { StepsTip } from './steps-tip';

@Component({
  selector: 'app-library-page',
  imports: [RouterLink, DatePipe, LibraryHero, StepsTip, LibrarySearch],
  template: `
    @if (!signedIn()) {
      <app-library-hero />
    }
    <main class="page" id="library">
      <div class="card-heading">
        @if (signedIn()) {
          <h1 class="grow-heading">Library</h1>
          <a class="button button-primary" routerLink="/value-sets/new">New value set</a>
        } @else {
          <!-- The hero holds the page's h1, so the library is a section of the page here. -->
          <h2 class="grow-heading">Published value sets</h2>
        }
      </div>
      @if (signedIn()) {
        <app-steps-tip />
      }

      @if (error(); as message) {
        <div class="error-panel" role="alert">
          <h2>The library could not be loaded</h2>
          <p>{{ message }}</p>
          <button type="button" class="button button-secondary" (click)="load()">Try again</button>
        </div>
      } @else if (valueSets(); as sets) {
        <app-library-search [signedIn]="signedIn()" (results)="search.set($event)" />

        @if (deprecatedCount() > 0) {
          <label class="checkbox">
            <input type="checkbox" [checked]="hideDeprecated()" (change)="hideDeprecated.set($any($event.target).checked)" />
            <span>Hide deprecated value sets ({{ deprecatedCount() }})</span>
          </label>
        }

        <p class="meta" role="status">{{ countLabel() }}</p>
        @if (search(); as applied) {
          @if (applied.mode === 'concepts' && applied.truncated) {
            <p class="search-note">
              These words match so many concepts that only the first 10,000 in each release were looked for,
              so other sets may contain matches too. Add a word to narrow the search.
            </p>
          }
          @if (applied.mode === 'concepts' && applied.storedTermsOnly) {
            <p class="search-note">
              The terminology server could not be reached, so only the preferred terms and fully specified
              names stored with each set were searched, not synonyms.
            </p>
          }
        }

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
                          {{ set.latestVersion !== null ? (set.latestVersionLabel ?? 'v' + set.latestVersion) : 'Draft' }}
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
                      @if (conceptHits().get(set.id); as hit) {
                        <p class="meta matches">
                          Contains {{ describeMatches(hit) }}
                        </p>
                      }
                    </td>
                    <td>
                      <ul class="tags">
                        @for (tag of set.tags; track tag) {
                          <li>
                            <button type="button" class="tag tag-button" (click)="searchBox()?.searchTag(tag)" [attr.aria-label]="'Show sets tagged ' + tag">
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
                      <td>
                        <span class="owner-cell">
                          @if (set.ownerAvatarUrl) {
                            <img class="owner-avatar" [src]="set.ownerAvatarUrl" alt="" />
                          }
                          {{ set.ownedByMe ? 'You' : set.ownerName }}
                        </span>
                      </td>
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

  protected readonly valueSets = signal<ValueSet[] | null>(null);
  protected readonly error = signal<string | null>(null);
  /** The search applied, or null to list every set. */
  protected readonly search = signal<LibrarySearchResult>(null);
  protected readonly searchBox = viewChild(LibrarySearch);
  /** Matching concepts by value set id, while a concept search is applied. */
  protected readonly conceptHits = computed(() => {
    const search = this.search();
    return new Map(search?.mode === 'concepts' ? search.hits.map((hit) => [hit.valueSetId, hit]) : []);
  });
  /** Display names of editions, by code system short name. Empty until loaded, or if Snowstorm cannot say. */
  private readonly editionNames = signal<Record<string, string>>({});

  /** Deprecated value sets are listed and marked, so people can see they are not for new use. */
  protected readonly hideDeprecated = signal(false);
  protected readonly deprecatedCount = computed(() => (this.valueSets() ?? []).filter((set) => this.isDeprecated(set)).length);
  private readonly listed = computed(() =>
    (this.valueSets() ?? []).filter((set) => !this.hideDeprecated() || !this.isDeprecated(set)),
  );

  /**
   * The sets to list: for a set search, those whose name, description or tags contain every word;
   * for a concept search, those the server found, in the library's order.
   */
  protected readonly shown = computed(() => {
    const search = this.search();
    if (search === null) {
      return this.listed();
    }
    if (search.mode === 'concepts') {
      const hits = this.conceptHits();
      return this.listed().filter((set) => hits.has(set.id));
    }
    const words = search.query.toLowerCase().split(/\s+/).filter((word) => word !== '');
    return this.listed().filter((set) => {
      const searchable = [set.name, set.description, ...set.tags].join(' ').toLowerCase();
      return words.every((word) => searchable.includes(word));
    });
  });

  protected readonly countLabel = computed(() => {
    const total = this.listed().length;
    if (total === 0) {
      return 'No value sets yet.';
    }
    const search = this.search();
    if (search === null) {
      return total === 1 ? '1 value set' : `${total} value sets`;
    }
    const found = this.shown().length;
    if (search.mode === 'concepts') {
      return found === 0
        ? `No value sets contain a concept matching "${search.query}".`
        : `${found} of ${total} value sets contain a concept matching "${search.query}".`;
    }
    return found === 0 ? `None of the ${total} value sets match "${search.query}".` : `${found} of ${total} value sets match "${search.query}".`;
  });

  /** "Allergic asthma, Childhood asthma and 4 more". */
  protected describeMatches(hit: ConceptSearchHit): string {
    const terms = hit.examples.map((example) => example.term);
    const more = hit.matchCount - terms.length;
    if (more > 0) {
      return `${terms.join(', ')} and ${more} more`;
    }
    return terms.length > 1 ? `${terms.slice(0, -1).join(', ')} and ${terms[terms.length - 1]}` : terms[0];
  }

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
