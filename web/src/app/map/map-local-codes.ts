import { DecimalPipe } from '@angular/common';
import { Component, computed, input, model, output, signal } from '@angular/core';
import { MapRow, SourceRow } from '../core/map-api';
import { hasWords } from '../core/word-filter';
import { MapAddCodes } from './map-add-codes';

const PAGE_SIZE = 50;

export interface LocalCodeLine extends SourceRow {
  /** The concepts with this code as an accepted target. */
  mappedFrom: { code: string; term: string }[];
}

/**
 * Each local code with the concepts mapped to it. A code counts as mapped once a concept has it
 * as an accepted target; a suggestion nobody accepted does not count.
 */
export function localCodeLines(localCodes: SourceRow[], rows: MapRow[]): LocalCodeLine[] {
  const mappedFrom = new Map<string, { code: string; term: string }[]>();
  for (const row of rows) {
    if (row.status === 'MAPPED') {
      for (const target of row.targets) {
        mappedFrom.set(target.code, [...(mappedFrom.get(target.code) ?? []), { code: row.code, term: row.term }]);
      }
    }
  }
  return localCodes.map((local) => ({ ...local, mappedFrom: mappedFrom.get(local.code) ?? [] }));
}

/**
 * The local codes of a map from SNOMED CT, with the concepts mapped to each. The map itself is
 * worked on from the concept side, so this is where codes nothing maps to yet can be seen, and
 * where more local codes are added.
 */
@Component({
  selector: 'app-map-local-codes',
  imports: [DecimalPipe, MapAddCodes],
  template: `
    <div class="actions">
      <div class="field grow">
        <label for="local-code-filter">Find a local code or term</label>
        <input id="local-code-filter" type="text" autocomplete="off" [value]="filter()" (input)="setFilter($any($event.target).value)" />
      </div>
      <label class="check">
        <input id="only-unmapped" type="checkbox" [checked]="onlyUnmapped()" (change)="setOnlyUnmapped($any($event.target).checked)" />
        Only those with nothing mapped ({{ unmappedCount() | number }})
      </label>
      <button type="button" class="button button-secondary" [attr.aria-expanded]="adding()" (click)="adding.set(!adding())">Add local codes</button>
    </div>

    @if (adding()) {
      <app-map-add-codes heading="Add local codes" [existing]="existing()" [room]="room()" [busy]="busy()" (add)="add.emit($event)" (cancelled)="adding.set(false)" />
    }

    @if (lines().length === 0) {
      <p class="muted" role="status">This map has no local codes yet. Add some to have something to map to.</p>
    } @else {
      <p class="meta" role="status">
        {{ shown().length | number }} of {{ lines().length | number }} local {{ lines().length === 1 ? 'code' : 'codes' }}.
        A code counts as mapped once a concept has it as an accepted target; a suggestion does not count.
      </p>
      <div class="table-scroll">
        <table class="local-table">
          <thead>
            <tr>
              <th scope="col">Local code</th>
              <th scope="col">Term</th>
              <th scope="col">Mapped from</th>
            </tr>
          </thead>
          <tbody>
            @for (line of pageLines(); track line.code) {
              <tr [attr.data-local]="line.code">
                <td class="mono">{{ line.code }}</td>
                <td>{{ line.term }}</td>
                <td>
                  @for (concept of line.mappedFrom; track concept.code) {
                    <div>{{ concept.term }} <span class="meta mono">{{ concept.code }}</span></div>
                  } @empty {
                    <span class="badge badge-warning">Nothing yet</span>
                  }
                </td>
              </tr>
            } @empty {
              <tr>
                <td colspan="3" class="muted">No local codes match.</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      @if (pageCount() > 1) {
        <div class="actions">
          <button type="button" class="button button-secondary button-small" [disabled]="page() === 0" (click)="page.set(page() - 1)">Previous</button>
          <span class="meta">Page {{ page() + 1 }} of {{ pageCount() }}</span>
          <button type="button" class="button button-secondary button-small" [disabled]="page() >= pageCount() - 1" (click)="page.set(page() + 1)">Next</button>
        </div>
      }
    }
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
    }

    .actions {
      align-items: flex-end;
    }

    .check {
      display: flex;
      gap: var(--space-2);
      align-items: center;
      padding-bottom: var(--space-2);
    }

    .local-table {
      width: 100%;
    }
  `,
})
export class MapLocalCodes {
  readonly lines = input.required<LocalCodeLine[]>();
  /** How many more local codes the map can take. */
  readonly room = input.required<number>();
  /** True while added codes are being saved. */
  readonly busy = input(false);
  /** True while the form for adding local codes is open. Whoever saves them closes it. */
  readonly adding = model(false);
  readonly add = output<SourceRow[]>();

  protected readonly filter = signal('');
  protected readonly onlyUnmapped = signal(false);
  protected readonly page = signal(0);

  protected readonly existing = computed<ReadonlySet<string>>(() => new Set(this.lines().map((line) => line.code)));
  protected readonly unmappedCount = computed(() => this.lines().filter((line) => line.mappedFrom.length === 0).length);

  protected readonly shown = computed(() =>
    this.lines().filter((line) => (!this.onlyUnmapped() || line.mappedFrom.length === 0) && hasWords(`${line.code} ${line.term}`, this.filter())),
  );
  protected readonly pageCount = computed(() => Math.ceil(this.shown().length / PAGE_SIZE));
  protected readonly pageLines = computed(() => this.shown().slice(this.page() * PAGE_SIZE, (this.page() + 1) * PAGE_SIZE));

  protected setFilter(text: string): void {
    this.filter.set(text);
    this.page.set(0);
  }

  protected setOnlyUnmapped(on: boolean): void {
    this.onlyUnmapped.set(on);
    this.page.set(0);
  }
}
