import { Component, input, output, signal } from '@angular/core';
import { SourceRow } from '../core/map-api';
import { CodeListInput } from './code-list-input';

/**
 * The form for adding local codes to a map, pasted or read from a file. Whoever shows it decides
 * when it closes, so it goes away once the codes are saved and stays if saving fails.
 */
@Component({
  selector: 'app-map-add-codes',
  imports: [CodeListInput],
  template: `
    <h2>{{ heading() }}</h2>
    <app-code-list-input [existing]="existing()" [room]="room()" (rowsChange)="rows.set($event)" />
    <div class="actions">
      <button type="button" class="button button-primary" [disabled]="!rows()?.length || busy()" (click)="add.emit(rows()!)">
        {{ busy() ? 'Adding…' : 'Add to the map' }}
      </button>
      <button type="button" class="button button-secondary" (click)="cancelled.emit()">Cancel</button>
    </div>
  `,
  styles: `
    :host {
      display: block;
      padding: var(--space-5);
      background: var(--surface-card);
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-md);
    }
  `,
})
export class MapAddCodes {
  readonly heading = input.required<string>();
  /** The local codes the map already has, so none is added twice. */
  readonly existing = input.required<ReadonlySet<string>>();
  /** How many more local codes the map can take. */
  readonly room = input.required<number>();
  /** True while the codes are being saved. */
  readonly busy = input(false);
  readonly add = output<SourceRow[]>();
  readonly cancelled = output<void>();

  protected readonly rows = signal<SourceRow[] | null>(null);
}
