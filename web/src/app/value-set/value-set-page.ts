import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ExpansionPage, ValueSet, ValueSetApi, describeError } from '../core/value-set-api';

const PAGE_SIZE = 50;

@Component({
  selector: 'app-value-set-page',
  imports: [RouterLink],
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
          <span class="badge" [class.badge-success]="set.status === 'PUBLISHED'">
            {{ set.status === 'PUBLISHED' ? 'Published' : 'Draft' }}
          </span>
        </div>
        <p class="lead">{{ set.description }}</p>

        @if (set.ownedByMe) {
          @if (deleteError(); as message) {
            <div class="error-panel" role="alert">
              <h2>The value set could not be deleted</h2>
              <p>{{ message }}</p>
            </div>
          }
          <div class="actions">
            @if (confirmingDelete()) {
              <span>Delete this value set? This cannot be undone.</span>
              <button type="button" class="button button-danger" [disabled]="deleting()" (click)="delete()">
                Yes, delete
              </button>
              <button type="button" class="button button-secondary" (click)="confirmingDelete.set(false)">
                Keep it
              </button>
            } @else {
              <a class="button button-secondary" [routerLink]="['/value-sets', set.id, 'edit']">Edit</a>
              <button type="button" class="button button-secondary" (click)="confirmingDelete.set(true)">
                Delete
              </button>
            }
          </div>
        }

        <div class="columns">
          <section aria-labelledby="definition-heading">
            <h2 id="definition-heading" class="overline">Definition</h2>
            <pre class="ecl ecl-block">{{ set.ecl }}</pre>
            <p class="meta">Evaluated against {{ set.branch }}.</p>
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
  protected readonly expansion = signal<ExpansionPage | null>(null);
  protected readonly expansionError = signal<string | null>(null);
  protected readonly offset = signal(0);
  protected readonly confirmingDelete = signal(false);
  protected readonly deleting = signal(false);
  protected readonly deleteError = signal<string | null>(null);

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
    this.api.get(this.id).subscribe({
      next: (set) => {
        this.valueSet.set(set);
        this.loadExpansion(0);
      },
      error: (error: unknown) => this.error.set(describeError(error)),
    });
  }

  protected loadExpansion(offset: number): void {
    this.offset.set(offset);
    this.expansion.set(null);
    this.expansionError.set(null);
    this.api.expansion(this.id, offset, PAGE_SIZE).subscribe({
      next: (page) => this.expansion.set(page),
      error: (error: unknown) => this.expansionError.set(describeError(error)),
    });
  }

  protected delete(): void {
    this.deleting.set(true);
    this.deleteError.set(null);
    this.api.delete(this.id).subscribe({
      next: () => void this.router.navigate(['/']),
      error: (error: unknown) => {
        this.deleting.set(false);
        this.confirmingDelete.set(false);
        this.deleteError.set(describeError(error));
      },
    });
  }

  protected previous(): void {
    this.loadExpansion(Math.max(0, this.offset() - PAGE_SIZE));
  }

  protected next(): void {
    this.loadExpansion(this.offset() + PAGE_SIZE);
  }
}
