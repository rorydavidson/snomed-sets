import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, catchError, debounceTime, distinctUntilChanged, map, of, startWith, switchMap } from 'rxjs';
import { CodeSystem, Concept, ExpansionPage, ValueSet, ValueSetApi, ValueSetDraft, describeError } from '../core/value-set-api';
import { ConceptPicker } from './concept-picker';
import { TagInput } from './tag-input';

const PREVIEW_SIZE = 20;
const PREVIEW_DELAY_MS = 500;
// The same limit the backend applies to a hand-picked list.
const MAX_MEMBERS = 2000;

type Preview =
  | { status: 'empty' }
  | { status: 'loading' }
  | { status: 'ready'; page: ExpansionPage }
  | { status: 'error'; message: string };

@Component({
  selector: 'app-value-set-editor-page',
  imports: [ReactiveFormsModule, RouterLink, ConceptPicker, TagInput],
  template: `
    <main class="page">
      <p><a [routerLink]="backLink">{{ id === null ? 'Back to the library' : 'Back to the value set' }}</a></p>

      @if (loadError(); as message) {
        <div class="error-panel" role="alert">
          <h1>{{ id === null ? 'A value set cannot be created right now' : 'This value set cannot be edited' }}</h1>
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

              @if (id === null) {
                <fieldset class="field">
                  <legend>How is it defined?</legend>
                  <label class="checkbox">
                    <input type="radio" formControlName="type" value="ECL" />
                    <span>By an ECL expression</span>
                  </label>
                  <label class="checkbox">
                    <input type="radio" formControlName="type" value="MANUAL" />
                    <span>By a list of concepts I pick</span>
                  </label>
                  <p class="meta">This cannot be changed after the value set is created.</p>
                </fieldset>
              }

              @if (!manual()) {
                <div class="field">
                  <label for="ecl">Definition (ECL)</label>
                  <textarea id="ecl" class="mono" formControlName="ecl" rows="6" maxlength="4000" spellcheck="false"></textarea>
                  @if (showError('ecl')) {
                    <p class="field-error">Enter an ECL expression, for example &lt;&lt; 195967001 |Asthma|.</p>
                  }
                  <p class="meta">Identifiers typed without a term get their term added when you save.</p>
                </div>
              }

              <div class="field">
                <label for="code-system">Code system</label>
                @if (id === null) {
                  <select id="code-system" formControlName="codeSystem">
                    @for (codeSystem of codeSystems(); track codeSystem.shortName) {
                      <option [value]="codeSystem.shortName">{{ describe(codeSystem) }}</option>
                    }
                  </select>
                  <p class="meta">This cannot be changed after the value set is created.</p>
                } @else {
                  <p id="code-system">{{ form.controls.codeSystem.value }}</p>
                  <p class="meta">Fixed when the value set was created.</p>
                }
              </div>

              <app-tag-input [(tags)]="tags" [known]="knownTags()" />

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

          @if (manual()) {
            <section class="wide" aria-labelledby="members-heading">
              <h2 class="overline">Find concepts</h2>
              <app-concept-picker [branch]="branch()" [inList]="memberIds()" (add)="addMembers($event)" />

              <h2 id="members-heading" class="overline">Concepts in this value set</h2>
              @if (memberMessage(); as message) {
                <p class="meta" role="status">{{ message }}</p>
              }
              @if (members().length === 0) {
                <p class="muted">Nothing has been added yet.</p>
              } @else {
                <div class="actions">
                  <span>{{ members().length }} {{ members().length === 1 ? 'concept' : 'concepts' }}</span>
                  <button type="button" class="button button-secondary" (click)="removeAllMembers()">Remove all</button>
                </div>
                <div class="table-scroll table-tall">
                  <table>
                    <thead>
                      <tr>
                        <th scope="col">Concept ID</th>
                        <th scope="col">Preferred term</th>
                        <th scope="col"><span class="visually-hidden">Remove</span></th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (concept of members(); track concept.conceptId) {
                        <tr>
                          <td class="mono">{{ concept.conceptId }}</td>
                          <td>{{ concept.preferredTerm }}</td>
                          <td>
                            <button type="button" class="link-button" (click)="removeMember(concept.conceptId)" [attr.aria-label]="'Remove ' + concept.preferredTerm">
                              Remove
                            </button>
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            </section>
          } @else {
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
          }
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
    type: ['ECL' as ValueSet['type']],
    name: ['', [Validators.required, Validators.pattern(/\S/)]],
    description: [''],
    ecl: ['', [Validators.required, Validators.pattern(/\S/)]],
    codeSystem: ['SNOMEDCT'],
    shared: [false],
  });

  protected readonly loaded = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly preview = signal<Preview>({ status: 'empty' });

  /** True when the value set is a hand-picked list of concepts instead of an ECL expression. */
  protected readonly manual = signal(false);
  protected readonly members = signal<Concept[]>([]);
  protected readonly memberIds = computed<ReadonlySet<string>>(() => new Set(this.members().map((c) => c.conceptId)));
  protected readonly memberMessage = signal<string | null>(null);
  protected readonly tags = signal<string[]>([]);
  protected readonly knownTags = signal<string[]>([]);
  protected readonly codeSystems = signal<CodeSystem[]>([]);
  /** The Snowstorm branch of the chosen code system, used for the preview and for concept search. */
  protected readonly branch = signal('MAIN');

  constructor() {
    const { ecl, codeSystem, type } = this.form.controls;
    type.valueChanges.pipe(takeUntilDestroyed()).subscribe((chosen) => this.useType(chosen));
    codeSystem.valueChanges.pipe(takeUntilDestroyed()).subscribe((chosen) => {
      const match = this.codeSystems().find((candidate) => candidate.shortName === chosen);
      if (match) {
        this.branch.set(match.branchPath);
      }
    });
    // Wait for a pause in typing, and drop the answer to an earlier definition if a newer one is pending.
    this.form.valueChanges
      .pipe(
        debounceTime(PREVIEW_DELAY_MS),
        map(() => ({ ecl: ecl.value.trim(), branch: this.branch(), valid: ecl.valid })),
        distinctUntilChanged((a, b) => a.ecl === b.ecl && a.branch === b.branch),
        switchMap((definition) => (definition.valid ? this.expand(definition.ecl, definition.branch) : of<Preview>({ status: 'empty' }))),
        takeUntilDestroyed(),
      )
      .subscribe((preview) => this.preview.set(preview));
  }

  ngOnInit(): void {
    // Suggestions only: the form works without them, so a failure here is not shown.
    this.api.tags().subscribe({ next: (tags) => this.knownTags.set(tags), error: () => undefined });
    if (this.id === null) {
      this.api.codeSystems().subscribe({
        next: (codeSystems) => this.startCreating(codeSystems),
        error: (error: unknown) => this.loadError.set(describeError(error)),
      });
      return;
    }
    this.api.get(this.id).subscribe({
      next: (set) => this.startEditing(set),
      error: (error: unknown) => this.loadError.set(describeError(error)),
    });
  }

  protected describe(codeSystem: CodeSystem): string {
    const version = codeSystem.latestVersion ? `, version ${codeSystem.latestVersion}` : '';
    return codeSystem.name === codeSystem.shortName
      ? `${codeSystem.shortName}${version}`
      : `${codeSystem.name} (${codeSystem.shortName}${version})`;
  }

  protected showError(control: 'name' | 'ecl'): boolean {
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
      type: value.type,
      codeSystem: value.codeSystem,
      name: value.name.trim(),
      description: value.description.trim(),
      ecl: this.manual() ? '' : value.ecl.trim(),
      visibility: value.shared ? 'COMMUNITY' : 'PRIVATE',
      tags: this.tags(),
      conceptIds: this.manual() ? this.members().map((concept) => concept.conceptId) : [],
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

  private startCreating(codeSystems: CodeSystem[]): void {
    if (codeSystems.length === 0) {
      this.loadError.set('Snowstorm has no code systems to build a value set against.');
      return;
    }
    this.codeSystems.set(codeSystems);
    const preferred = codeSystems.find((candidate) => candidate.shortName === 'SNOMEDCT') ?? codeSystems[0];
    this.form.controls.codeSystem.setValue(preferred.shortName);
    this.loaded.set(true);
  }

  private startEditing(set: ValueSet): void {
    if (!set.ownedByMe) {
      this.loadError.set('Only the owner can edit this value set.');
      return;
    }
    this.branch.set(set.branch);
    this.tags.set(set.tags);
    this.form.setValue({
      type: set.type,
      codeSystem: set.codeSystem,
      name: set.name,
      description: set.description,
      ecl: set.ecl,
      shared: set.visibility === 'COMMUNITY',
    });
    if (set.type === 'ECL') {
      this.loaded.set(true);
      return;
    }
    this.api.members(set.id).subscribe({
      next: (members) => {
        this.members.set(members);
        this.loaded.set(true);
      },
      error: (error: unknown) => this.loadError.set(describeError(error)),
    });
  }

  protected addMembers(concepts: Concept[]): void {
    const byId = new Map(this.members().map((concept) => [concept.conceptId, concept]));
    const before = byId.size;
    concepts.forEach((concept) => byId.set(concept.conceptId, concept));
    if (byId.size > MAX_MEMBERS) {
      this.memberMessage.set(`Nothing was added: a value set can hold at most ${MAX_MEMBERS} concepts.`);
      return;
    }
    const added = byId.size - before;
    const skipped = concepts.length - added;
    this.members.set([...byId.values()]);
    this.memberMessage.set(
      skipped === 0 ? `Added ${added}.` : `Added ${added}. ${skipped} already in the list or listed twice.`,
    );
  }

  protected removeMember(conceptId: string): void {
    this.members.set(this.members().filter((concept) => concept.conceptId !== conceptId));
    this.memberMessage.set(null);
  }

  protected removeAllMembers(): void {
    this.members.set([]);
    this.memberMessage.set(null);
  }

  private useType(type: ValueSet['type']): void {
    this.manual.set(type === 'MANUAL');
    // A disabled field is left out of validation, so a hand-picked set does not need ECL.
    const { ecl } = this.form.controls;
    if (type === 'MANUAL') {
      ecl.disable();
    } else {
      ecl.enable();
    }
  }

  private expand(ecl: string, branch: string): Observable<Preview> {
    return this.api.preview(ecl, branch, PREVIEW_SIZE).pipe(
      map((page): Preview => ({ status: 'ready', page })),
      catchError((error: unknown) => of<Preview>({ status: 'error', message: describeError(error) })),
      startWith<Preview>({ status: 'loading' }),
    );
  }
}
