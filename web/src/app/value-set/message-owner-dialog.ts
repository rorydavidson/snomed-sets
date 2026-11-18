import { Component, ElementRef, afterNextRender, inject, input, output, signal, viewChild } from '@angular/core';
import { ValueSetApi, describeError } from '../core/value-set-api';

const MIN_LENGTH = 10;
const MAX_LENGTH = 2000;

/**
 * Writes a plain-text message to a value set's owner, sent by email. The owner's address is never
 * shown; the sender is told that theirs will be, so the owner can reply.
 */
@Component({
  selector: 'app-message-owner-dialog',
  template: `
    <dialog #dialog class="message" aria-labelledby="message-title" (close)="closed.emit()">
      @if (sent()) {
        <div class="message-body">
          <h2 id="message-title">Message sent</h2>
          <p>
            {{ ownerName() }} will get your message by email. If they reply, it will come to the email address
            on your account.
          </p>
          <div class="actions">
            <button type="button" class="button button-primary" (click)="close()">Close</button>
          </div>
        </div>
      } @else {
        <form class="message-body form" (submit)="send($event)">
          <h2 id="message-title">Message {{ ownerName() }}</h2>
          <p class="muted">About "{{ valueSetName() }}"</p>
          <div class="field">
            <label for="owner-message">Your message</label>
            <textarea id="owner-message" rows="7" [attr.maxlength]="maxLength" required
                      [value]="message()" (input)="message.set($any($event.target).value)"
                      aria-describedby="owner-message-note"></textarea>
            <span class="meta">{{ message().trim().length }} of {{ maxLength }} characters</span>
          </div>
          <p id="owner-message-note" class="note">
            Your name and the email address on your account are sent with the message, so
            {{ ownerName() }} can reply. Their address is not shown to you.
          </p>
          @if (error(); as text) {
            <p class="field-error" role="alert">{{ text }}</p>
          }
          <div class="actions">
            <button type="submit" class="button button-primary" [disabled]="sending() || tooShort()">
              {{ sending() ? 'Sending…' : 'Send message' }}
            </button>
            <button type="button" class="button button-secondary" (click)="close()">Cancel</button>
          </div>
        </form>
      }
    </dialog>
  `,
  styles: `
    .message {
      width: min(560px, calc(100vw - 2 * var(--space-4)));
      padding: 0;
      color: var(--text-body);
      background: var(--surface-card);
      border: 0;
      border-radius: var(--radius-md);
      box-shadow: 0 24px 64px rgb(0 56 101 / 35%);

      &::backdrop {
        background: rgb(0 56 101 / 55%);
      }
    }

    .message-body {
      display: flex;
      flex-direction: column;
      gap: var(--space-4);
      padding: var(--space-6);
    }

    h2 {
      font-size: var(--fs-h4);
    }

    .note {
      padding: var(--space-3) var(--space-4);
      font-size: var(--fs-body-sm);
      background: var(--surface-subtle);
      border-left: 3px solid var(--snomed-cyan);
    }
  `,
})
export class MessageOwnerDialog {
  private readonly api = inject(ValueSetApi);

  readonly valueSetId = input.required<number>();
  readonly valueSetName = input.required<string>();
  readonly ownerName = input.required<string>();
  readonly closed = output<void>();

  protected readonly maxLength = MAX_LENGTH;
  protected readonly message = signal('');
  protected readonly sending = signal(false);
  protected readonly sent = signal(false);
  protected readonly error = signal<string | null>(null);

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

  protected tooShort(): boolean {
    return this.message().trim().length < MIN_LENGTH;
  }

  protected send(event: Event): void {
    event.preventDefault();
    if (this.tooShort()) {
      this.error.set(`Write at least ${MIN_LENGTH} characters.`);
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api.messageOwner(this.valueSetId(), this.message().trim()).subscribe({
      next: () => {
        this.sending.set(false);
        this.sent.set(true);
      },
      error: (error: unknown) => {
        this.sending.set(false);
        this.error.set(describeError(error));
      },
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
