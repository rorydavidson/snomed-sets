import { Component, ElementRef, computed, inject, input, signal } from '@angular/core';

/**
 * A button that copies a fixed link to a value set, or to one of its versions, and shows the link
 * so it can also be copied by hand when the browser does not allow copying.
 */
@Component({
  selector: 'app-share-link',
  template: `
    <button type="button" class="button button-secondary button-small" [attr.aria-expanded]="open()" (click)="share()">
      {{ label() }}
    </button>
    @if (open()) {
      <div class="share-panel">
        <label class="visually-hidden" [for]="fieldId">Link to {{ what() }}</label>
        <input #field [id]="fieldId" type="text" readonly [value]="url()" (focus)="field.select()" />
        <button type="button" class="button button-secondary button-small" (click)="copy()">Copy</button>
        <button type="button" class="link-button" (click)="open.set(false)">Close</button>
        <span class="meta" role="status">{{ status() }}</span>
      </div>
    }
  `,
  styles: `
    /* The panel floats under the button, so opening it does not move the page around. */
    :host {
      position: relative;
      display: inline-flex;
    }

    .share-panel {
      position: absolute;
      top: calc(100% + var(--space-1));
      left: 0;
      /* Above the page, but under the sticky top bar. */
      z-index: 10;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
      width: max-content;
      max-width: min(480px, 90vw);
      padding: var(--space-3);
      background: var(--surface-card);
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-md);
      box-shadow: var(--shadow-md);

      [role='status'] {
        flex-basis: 100%;
      }

      input {
        width: min(34ch, 70vw);
        padding: var(--space-1) var(--space-2);
        font-family: var(--font-mono);
        font-size: var(--fs-caption);
        color: var(--text-body);
        background: var(--surface-subtle);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-sm);
      }
    }

    :host(.align-end) .share-panel {
      right: 0;
      left: auto;
    }
  `,
  host: {
    '[class.align-end]': 'alignEnd()',
    '(document:keydown.escape)': 'open.set(false)',
    '(document:click)': 'closeIfOutside($event)',
  },
})
export class ShareLink {
  /** The path within this app, for example /value-sets/7/versions/2. */
  readonly path = input.required<string>();
  /** What the link opens, read out with the link field, for example "version 2". */
  readonly what = input.required<string>();
  readonly label = input('Share');
  /** Opens the panel towards the left, for a button at the right-hand end of a row. */
  readonly alignEnd = input(false);

  private static nextId = 0;
  protected readonly fieldId = `share-link-${ShareLink.nextId++}`;
  protected readonly open = signal(false);
  protected readonly status = signal('');
  protected readonly url = computed(() => window.location.origin + this.path());

  private readonly host = inject(ElementRef<HTMLElement>);

  /** A click anywhere else, including on another share button, closes this panel. */
  protected closeIfOutside(event: Event): void {
    if (!this.host.nativeElement.contains(event.target as Node)) {
      this.open.set(false);
    }
  }

  protected share(): void {
    if (this.open()) {
      this.open.set(false);
      return;
    }
    this.open.set(true);
    this.copy();
  }

  protected copy(): void {
    if (!navigator.clipboard?.writeText) {
      this.status.set('Select the link and copy it.');
      return;
    }
    navigator.clipboard.writeText(this.url()).then(
      () => this.status.set('Link copied.'),
      () => this.status.set('Select the link and copy it.'),
    );
  }
}
