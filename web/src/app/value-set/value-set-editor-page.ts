import { Component, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, catchError, debounceTime, distinctUntilChanged, map, of, startWith, switchMap } from 'rxjs';
import { ExpansionPage, ValueSet, ValueSetApi, ValueSetDraft, describeError } from '../core/value-set-api';

const PREVIEW_SIZE = 20;
const PREVIEW_DELAY_MS = 500;
// The same shape the backend accepts for a Snowstorm branch path.
const BRANCH_PATTERN = /^MAIN(\/[A-Za-z0-9][A-Za-z0-9_.-]*)*$/;

type Preview =
  | { status: 'empty' }
  | { status: 'loading' }
  | { status: 'ready'; page: ExpansionPage }
  | { status: 'error'; message: string };

@Component({
  selector: 'app-value-set-editor-page',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <main class="page">
      <p><a [routerLink]="backLink">{{ id === null ? 'Back to the library' : 'Back to the value set' }}</a></p>

      @if (loadError(); as message) {
        <div class="error-panel" role="alert">
          <h1>This value set cannot be edited</h1>
          <p>{{ message }}</p>
        </div>
      } @else if (loaded()) {
        <h1>{{ id === null ? 'New value set' : 'Edit value set' }}</h1>

        <div class="columns">
          <section>
            <form class="form" [formGroup]="form" (ngSubmit)="save()" novalidate>
              <div class="field">
                <label for="name">Name</label>
                <input id="name" type="text" formControlName="name" maxlength="200" autocomplete="off" />
                @if (showError('name')) {
                  <p class="field-error">Give the value set a name.</p>
                }
              </div>

              <div class="field">
                <label for="description">Description</label>
                <textarea id="description" formControlName="description" rows="3" maxlength="2000"></textarea>
              </div>

              <div class="field">
                <label for="ecl">Definition (ECL)</label>
                <textarea id="ecl" class="mono" formControlName="ecl" rows="6" maxlength="4000" spellcheck="false"></textarea>
                @if (showError('ecl')) {
                  <p class="field-error">Enter an ECL expression, for example &lt;&lt; 195967001 |Asthma|.</p>
                }
              </div>

              <div class="field">
                <label for="branch">Snowstorm branch</label>
                <input id="branch" type="text" class="mono" formControlName="branch" maxlength="200" autocomplete="off" />
                @if (showError('branch')) {
                  <p class="field-error">Enter a branch path that starts with MAIN, for example MAIN or MAIN/SNOMEDCT-GB.</p>
                }
              </div>

              <div class="field">
                <label class="checkbox">
                  <input type="checkbox" formControlName="shared" />
                  <span>Share with the community</span>
                </label>
                <p class="meta">Everyone who signs in will see this value set, with your name as its owner.</p>
              </div>

              @if (saveError(); as message) {
                <div class="error-panel" role="alert">
                  <h2>The value set could not be saved</h2>
                  <p>{{ message }}</p>
                </div>
              }

              <div class="actions">
                <button type="submit" class="button button-primary" [disabled]="saving()">
                  {{ saving() ? 'Saving…' : 'Save' }}
                </button>
                <a class="button button-secondary" [routerLink]="backLink">Cancel</a>
              </div>
            </form>
          </section>

          <section class="wide" aria-labelledby="preview-heading">
            <h2 id="preview-heading" class="overline">Preview</h2>
            @switch (preview().status) {
              @case ('empty') {
                <p class="muted">Enter a definition to see the concepts it matches.</p>
              }
              @case ('loading') {
                <p class="muted" role="status">Expanding against Snowstorm…</p>
              }
              @case ('error') {
                <div class="error-panel" role="alert">
                  <h3>The definition could not be expanded</h3>
                  <p>{{ previewMessage() }}</p>
                  <p class="meta">You can still save it and come back to it later.</p>
                </div>
              }
              @case ('ready') {
                @if (previewPage(); as page) {
                  <p class="meta" role="status">{{ previewLabel(page) }}</p>
                  @if (page.items.length > 0) {
                    <div class="table-scroll">
                      <table>
                        <thead>
                          <tr>
                            <th scope="col">Concept ID</th>
                            <th scope="col">Preferred term</th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (concept of page.items; track concept.conceptId) {
                            <tr>
                              <td class="mono">{{ concept.conceptId }}</td>
                              <td>{{ concept.preferredTerm }}</td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    </div>
                  }
                }
              }
            }
          </section>
        </div>
      } @else {
        <p class="muted" role="status">Loading value set…</p>
      }
    </main>
  `,
})
export class ValueSetEditorPage implements OnInit {
  private readonly api = inject(ValueSetApi);
  private readonly router = inject(Router);
  private readonly idParam = inject(ActivatedRoute).snapshot.paramMap.get('id');

  /** Null when creating a new value set. */
  protected readonly id = this.idParam === null ? null : Number(this.idParam);
  protected readonly backLink = this.id === null ? ['/'] : ['/value-sets', this.id];

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.pattern(/\S/)]],
    description: [''],
    ecl: ['', [Validators.required, Validators.pattern(/\S/)]],
    branch: ['MAIN', [Validators.required, Validators.pattern(BRANCH_PATTERN)]],
    shared: [false],
  });

  protected readonly loaded = signal(this.id === null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly preview = signal<Preview>({ status: 'empty' });

  constructor() {
    const { ecl, branch } = this.form.controls;
    // Wait for a pause in typing, and drop the answer to an earlier definition if a newer one is pending.
    this.form.valueChanges
      .pipe(
        debounceTime(PREVIEW_DELAY_MS),
        map(() => ({ ecl: ecl.value.trim(), branch: branch.value.trim(), valid: ecl.valid && branch.valid })),
        distinctUntilChanged((a, b) => a.ecl === b.ecl && a.branch === b.branch),
        switchMap((definition) => (definition.valid ? this.expand(definition.ecl, definition.branch) : of<Preview>({ status: 'empty' }))),
        takeUntilDestroyed(),
      )
      .subscribe((preview) => this.preview.set(preview));
  }

  ngOnInit(): void {
    if (this.id === null) {
      return;
    }
    this.api.get(this.id).subscribe({
      next: (set) => this.startEditing(set),
      error: (error: unknown) => this.loadError.set(describeError(error)),
    });
  }

  protected showError(control: 'name' | 'ecl' | 'branch'): boolean {
    const field = this.form.controls[control];
    return field.invalid && field.touched;
  }

  protected previewMessage(): string {
    const preview = this.preview();
    return preview.status === 'error' ? preview.message : '';
  }

  protected previewPage(): ExpansionPage | null {
    const preview = this.preview();
    return preview.status === 'ready' ? preview.page : null;
  }

  protected previewLabel(page: ExpansionPage): string {
    if (page.total === 0) {
      return 'No concepts match this definition.';
    }
    if (page.total <= page.items.length) {
      return `${page.total} concepts match.`;
    }
    return `${page.total} concepts match. Showing the first ${page.items.length}.`;
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const draft: ValueSetDraft = {
      name: value.name.trim(),
      description: value.description.trim(),
      ecl: value.ecl.trim(),
      branch: value.branch.trim(),
      visibility: value.shared ? 'COMMUNITY' : 'PRIVATE',
    };
    this.saving.set(true);
    this.saveError.set(null);
    const request = this.id === null ? this.api.create(draft) : this.api.update(this.id, draft);
    request.subscribe({
      next: (saved) => void this.router.navigate(['/value-sets', saved.id]),
      error: (error: unknown) => {
        this.saving.set(false);
        this.saveError.set(describeError(error));
      },
    });
  }

  private startEditing(set: ValueSet): void {
    if (!set.ownedByMe) {
      this.loadError.set('Only the owner can edit this value set.');
      return;
    }
    this.form.setValue({
      name: set.name,
      description: set.description,
      ecl: set.ecl,
      branch: set.branch,
      shared: set.visibility === 'COMMUNITY',
    });
    this.loaded.set(true);
  }

  private expand(ecl: string, branch: string): Observable<Preview> {
    return this.api.preview(ecl, branch, PREVIEW_SIZE).pipe(
      map((page): Preview => ({ status: 'ready', page })),
      catchError((error: unknown) => of<Preview>({ status: 'error', message: describeError(error) })),
      startWith<Preview>({ status: 'loading' }),
    );
  }
}
