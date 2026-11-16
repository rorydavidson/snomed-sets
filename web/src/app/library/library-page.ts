import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ValueSet, ValueSetApi, describeError } from '../core/value-set-api';

@Component({
  selector: 'app-library-page',
  imports: [RouterLink],
  template: `
    <section class="hero">
      <div class="page">
        <p class="overline">Community library</p>
        <h1 class="display">shared value sets</h1>
        <p class="lead">Sets shared with the community, and your own.</p>
        <div class="actions">
          <a class="button button-primary" routerLink="/value-sets/new">New value set</a>
        </div>
      </div>
    </section>

    <main class="page">
      @if (error(); as message) {
        <div class="error-panel" role="alert">
          <h2>The library could not be loaded</h2>
          <p>{{ message }}</p>
          <button type="button" class="button button-secondary" (click)="load()">Try again</button>
        </div>
      } @else if (valueSets(); as sets) {
        @if (sets.length === 0) {
          <p class="muted">No value sets have been shared yet.</p>
        }
        <ul class="card-list">
          @for (set of sets; track set.id) {
            <li class="card">
              <div class="card-heading">
                <a class="card-title" [routerLink]="['/value-sets', set.id]">{{ set.name }}</a>
                @if (set.visibility === 'PRIVATE') {
                  <span class="badge">Private</span>
                }
                <span class="badge" [class.badge-success]="set.status === 'PUBLISHED'">
                  {{ set.status === 'PUBLISHED' ? 'Published' : 'Draft' }}
                </span>
              </div>
              <p>{{ set.description }}</p>
              <code class="ecl">{{ set.ecl }}</code>
              <p class="meta">{{ set.ownedByMe ? 'Yours' : set.ownerName }} · {{ set.branch }}</p>
            </li>
          }
        </ul>
      } @else {
        <p class="muted" role="status">Loading value sets…</p>
      }
    </main>
  `,
})
export class LibraryPage implements OnInit {
  private readonly api = inject(ValueSetApi);

  protected readonly valueSets = signal<ValueSet[] | null>(null);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
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
