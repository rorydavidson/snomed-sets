import { Component, ElementRef, afterNextRender, input, output, viewChild } from '@angular/core';
import { Concept } from '../core/value-set-api';
import { ConceptPicker } from '../value-set/concept-picker';

/**
 * A dialog for adding concepts to a map from SNOMED CT by searching for them. It stays open
 * after each addition, so several searches can be made in a row. A long list or an ECL
 * expression is imported from the dialog it hands over to.
 */
@Component({
  selector: 'app-map-add-concepts',
  imports: [ConceptPicker],
  template: `
    <dialog #dialog aria-labelledby="add-concepts-title" (close)="closed.emit()">
      <div class="frame">
        <div class="head">
          <h2 id="add-concepts-title">Add concepts</h2>
          <p class="meta">Search for concepts and add the ones to map. They join the map as new rows.</p>
        </div>
        <div class="body">
          <app-concept-picker [branch]="branch()" [inList]="inList()" (add)="add.emit($event)" (details)="details.emit($event)" />
        </div>
        <div class="foot">
          <button type="button" class="button button-primary" (click)="close()">Done</button>
          <button type="button" class="button button-secondary" (click)="importList.emit()">Import a list or ECL instead</button>
        </div>
      </div>
    </dialog>
  `,
  styles: `
    dialog {
      width: min(880px, calc(100vw - 2 * var(--space-4)));
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

    .frame {
      display: flex;
      flex-direction: column;
      max-height: calc(100vh - 2 * var(--space-6));
    }

    .head,
    .foot {
      flex: none;
      padding: var(--space-4) var(--space-6);
    }

    .head {
      border-bottom: 1px solid var(--border-subtle);
    }

    .foot {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      background: var(--surface-subtle);
      border-top: 1px solid var(--border-subtle);
    }

    .body {
      flex: 1 1 auto;
      min-height: 0;
      padding: var(--space-4) var(--space-6);
      overflow-y: auto;
    }
  `,
})
export class MapAddConcepts {
  /** The Snowstorm branch of the map's release. */
  readonly branch = input.required<string>();
  /** The concepts the map already has, so none is offered twice. */
  readonly inList = input.required<ReadonlySet<string>>();
  readonly add = output<Concept[]>();
  /** Asks for a concept's details to be shown. */
  readonly details = output<string>();
  /** Asks for the import of a list or an ECL expression in place of this dialog. */
  readonly importList = output<void>();
  readonly closed = output<void>();

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

  protected close(): void {
    const dialog = this.dialog().nativeElement;
    if (typeof dialog.close === 'function') {
      dialog.close();
    } else {
      this.closed.emit();
    }
  }
}
