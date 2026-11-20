import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CodeMap, MapApi, describeMapError } from '../core/map-api';
import { ValueSetApi } from '../core/value-set-api';
import { LibraryViews } from '../library/library-views';

/** The maps view of the library. Maps are private for now, so it lists only the person's own. */
@Component({
  selector: 'app-maps-page',
  imports: [RouterLink, DatePipe, DecimalPipe, LibraryViews],
  template: `
    <main class="page">
      <div class="card-heading">
        <h1 class="grow-heading">Library</h1>
        <a class="button button-primary" routerLink="/maps/new">New map</a>
      </div>
      <app-library-views />

      @if (error(); as message) {
        <div class="error-panel" role="alert">
          <h2>Your maps could not be loaded</h2>
          <p>{{ message }}</p>
          <button type="button" class="button button-secondary" (click)="load()">Try again</button>
        </div>
      } @else if (maps(); as list) {
        <p class="meta" role="status">
          {{ list.length === 0 ? 'No maps yet.' : list.length === 1 ? '1 map.' : list.length + ' maps.' }}
          Maps are private to you for now: nobody else can see them.
        </p>
        @if (list.length > 0) {
          <div class="table-scroll">
            <table class="library-table">
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Maps</th>
                  <th scope="col">Edition</th>
                  <th scope="col">Release</th>
                  <th scope="col" class="numeric" title="Local codes, or concepts in a map from SNOMED CT">Rows</th>
                  <th scope="col" class="numeric">Done</th>
                  <th scope="col">Updated</th>
                </tr>
              </thead>
              <tbody>
                @for (map of list; track map.id) {
                  <tr>
                    <td>
                      <div class="set-heading">
                        <a class="set-name" [routerLink]="['/maps', map.id]">{{ map.name }}</a>
                        <span class="badge">Private</span>
                      </div>
                      @if (map.description) {
                        <p class="meta one-line" [title]="map.description">{{ map.description }}</p>
                      }
                    </td>
                    <td>{{ map.direction === 'FROM_SNOMED' ? 'SNOMED CT to ' + map.localSystemName : map.localSystemName + ' to SNOMED CT' }}</td>
                    <td [title]="map.codeSystem">{{ editionNames()[map.codeSystem] ?? map.codeSystem }}</td>
                    <td class="no-wrap">
                      @if (map.codeSystemVersion) {
                        {{ map.codeSystemVersion }}
                      } @else {
                        <span class="muted" title="Built on the live branch, not a release">Live branch</span>
                      }
                    </td>
                    <td class="numeric">{{ map.counts.total | number }}</td>
                    <td class="numeric" title="Codes that are mapped or marked as having no map">
                      {{ map.counts.mapped + map.counts.noMap | number }}
                    </td>
                    <td class="no-wrap">{{ map.updatedAt | date: 'd MMM y' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      } @else {
        <p class="muted" role="status">Loading maps…</p>
      }
    </main>
  `,
})
export class MapsPage implements OnInit {
  private readonly api = inject(MapApi);
  private readonly valueSets = inject(ValueSetApi);

  protected readonly maps = signal<CodeMap[] | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Display names of editions, by code system short name. Empty until loaded, or if Snowstorm cannot say. */
  protected readonly editionNames = signal<Record<string, string>>({});

  ngOnInit(): void {
    this.load();
    // Only for display names: the short name is shown instead if this fails.
    this.valueSets.codeSystems().subscribe({
      next: (codeSystems) =>
        this.editionNames.set(Object.fromEntries(codeSystems.map((codeSystem) => [codeSystem.shortName, codeSystem.name]))),
      error: () => undefined,
    });
  }

  protected load(): void {
    this.error.set(null);
    this.maps.set(null);
    this.api.list().subscribe({
      next: (maps) => this.maps.set(maps),
      error: (error: unknown) => this.error.set(describeMapError(error)),
    });
  }
}
