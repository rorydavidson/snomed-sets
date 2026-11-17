import { Component, computed, input, model, signal } from '@angular/core';

const MAX_TAGS = 10;
// The same rule the backend applies: letters, digits, spaces, hyphens and underscores, at most 40 characters.
const TAG_PATTERN = /^[\p{L}\p{N}][\p{L}\p{N} _-]{0,39}$/u;

/** Edits the tags of a value set: type a new one, or pick one that is already in use. */
@Component({
  selector: 'app-tag-input',
  template: `
    <div class="field">
      <label for="tag-entry">Tags</label>
      @if (tags().length > 0) {
        <ul class="tags" aria-label="Tags on this value set">
          @for (tag of tags(); track tag) {
            <li class="tag">
              {{ tag }}
              <button type="button" class="tag-remove" (click)="remove(tag)" [attr.aria-label]="'Remove tag ' + tag"><svg class="icon" viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M4 4l8 8M12 4l-8 8" /></svg></button>
            </li>
          }
        </ul>
      }
      <div class="actions">
        <input
          id="tag-entry"
          type="text"
          class="grow-input"
          list="known-tags"
          maxlength="40"
          autocomplete="off"
          #entry
          (keydown.enter)="$event.preventDefault(); add(entry)"
        />
        <datalist id="known-tags">
          @for (tag of suggestions(); track tag) {
            <option [value]="tag"></option>
          }
        </datalist>
        <button type="button" class="button button-secondary" (click)="add(entry)">Add tag</button>
      </div>
      @if (error(); as message) {
        <p class="field-error" role="alert">{{ message }}</p>
      } @else {
        <p class="meta">Type a new tag or choose one already in use. Up to ten.</p>
      }
    </div>
  `,
})
export class TagInput {
  readonly tags = model.required<string[]>();
  /** Tags already in use elsewhere, offered as suggestions. */
  readonly known = input<string[]>([]);

  protected readonly error = signal<string | null>(null);
  protected readonly suggestions = computed(() => this.known().filter((tag) => !this.tags().includes(tag)));

  protected add(entry: HTMLInputElement): void {
    const tag = entry.value.trim().replace(/\s+/g, ' ').toLowerCase();
    if (tag === '') {
      return;
    }
    if (!TAG_PATTERN.test(tag)) {
      this.error.set('A tag can use letters, digits, spaces, hyphens and underscores.');
      return;
    }
    if (!this.tags().includes(tag)) {
      if (this.tags().length >= MAX_TAGS) {
        this.error.set('A value set can have at most ten tags.');
        return;
      }
      this.tags.set([...this.tags(), tag].sort());
    }
    this.error.set(null);
    entry.value = '';
  }

  protected remove(tag: string): void {
    this.tags.set(this.tags().filter((existing) => existing !== tag));
    this.error.set(null);
  }
}
