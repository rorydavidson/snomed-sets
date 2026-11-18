import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, catchError, debounceTime, distinctUntilChanged, filter, map, of, startWith, switchMap } from 'rxjs';
import {
  CodeSystem,
  CodeSystemVersion,
  Concept,
  EclPrefill,
  ExpansionPage,
  ValueSet,
  ValueSetApi,
  ValueSetDraft,
  describeError,
} from '../core/value-set-api';
import { ProfileApi } from '../core/profile-api';
import { Session } from '../core/session';
import { ConceptDetails } from './concept-details';
import { ConceptPicker } from './concept-picker';
import { IdentifierImport } from './identifier-import';
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

/** Where checking the identifier has got to. */
type IdentifierCheckState = {
  status: 'idle' | 'checking' | 'found' | 'error';
  sctid: string;
  /** The release it was checked against. */
  branch?: string;
  term?: string;
  message?: string;
};

@Component({
  selector: 'app-value-set-editor-page',
  imports: [ReactiveFormsModule, RouterLink, ConceptPicker, TagInput, ConceptDetails, IdentifierImport],
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

              <div class="field">
                <label for="sctid">SNOMED CT identifier (optional)</label>
                <div class="actions">
                  <input id="sctid" type="text" class="mono grow-input" inputmode="numeric" maxlength="18"
                         formControlName="sctid" (blur)="checkIdentifier()" aria-describedby="sctid-hint sctid-result" />
                  <button type="button" class="button button-secondary"
                          [disabled]="identifierCheck().status === 'checking'" (click)="checkIdentifier()">Check</button>
                </div>
                <p id="sctid-hint" class="meta">
                  A reference set concept that identifies this set, also used in its share link. It must be in the
                  set's release and not identify another set.
                  @if (originalSctid()) {
                    Changing it stops links shared with {{ originalSctid() }} from working.
                  }
                </p>
                <div id="sctid-result">
                  @switch (identifierCheck().status) {
                    @case ('checking') {
                      <p class="meta" role="status">Checking {{ identifierCheck().sctid }}…</p>
                    }
                    @case ('found') {
                      <p class="identifier-found" role="status">
                        <span class="mono">{{ identifierCheck().sctid }}</span> {{ identifierCheck().term }}
                      </p>
                    }
                    @case ('error') {
                      <p class="field-error" role="alert">{{ identifierCheck().message }}</p>
                    }
                  }
                </div>
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
                  <label class="checkbox">
                    <input type="radio" formControlName="type" value="IMPORT" />
                    <span>By importing a list of identifiers or an ECL expression</span>
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
                      <option [value]="codeSystem.shortName">{{ codeSystem.name }}</option>
                    }
                  </select>
                  <p class="meta">This cannot be changed after the value set is created.</p>
                } @else {
                  <p id="code-system">{{ form.controls.codeSystem.value }}</p>
                  <p class="meta">Fixed when the value set was created.</p>
                }
              </div>

              <div class="field">
                <label for="code-system-version">Release</label>
                @if (id === null) {
                  @if (versions(); as list) {
                    @if (list.length > 0) {
                      <select id="code-system-version" formControlName="codeSystemVersion">
                        @for (release of list; track release.version) {
                          <option [value]="release.version">{{ release.version }}{{ $first ? ' (latest)' : '' }}</option>
                        }
                      </select>
                      <p class="meta">
                        Choose an earlier release to build against SNOMED CT as it was then. The value set can be
                        upgraded to a newer release later.
                      </p>
                    } @else if (versionsError()) {
                      <p id="code-system-version" role="alert">
                        The releases could not be loaded, so the latest release will be used.
                      </p>
                    } @else {
                      <p id="code-system-version">This code system has no release yet, so its latest content is used.</p>
                    }
                  } @else {
                    <p id="code-system-version" class="muted" role="status">Loading releases…</p>
                  }
                } @else {
                  <p id="code-system-version">{{ editingRelease() ?? 'Live branch, not a release' }}</p>
                  <p class="meta">Move it to a newer release with Upgrade on the value set page.</p>
                }
              </div>

              <app-tag-input [(tags)]="tags" [known]="knownTags()" />

              <div class="field">
                <label class="checkbox">
                  <input type="checkbox" formControlName="shared" />
                  <span>Share with the community</span>
                </label>
                <p class="meta">Everyone who signs in will see this value set, and who made it.</p>
              </div>

              <fieldset class="field">
                <legend>Shown as made by</legend>
                @if (organisation(); as organisation) {
                  <label class="checkbox">
                    <input type="radio" formControlName="showOrganisation" [value]="false" />
                    <span>Me{{ myName() ? ', ' + myName() : '' }}</span>
                  </label>
                  <label class="checkbox">
                    <input type="radio" formControlName="showOrganisation" [value]="true" />
                    <span>My organisation, {{ organisation }}</span>
                  </label>
                } @else {
                  <p class="meta">
                    You{{ myName() ? ', ' + myName() : '' }}. To show your organisation instead, add it in your
                    <a routerLink="/profile">profile settings</a>.
                  </p>
                }
              </fieldset>

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
              <div class="actions">
                <h2 class="overline">Find concepts</h2>
                <button type="button" class="button button-secondary button-small" (click)="importing.set(true)">
                  Import concepts
                </button>
              </div>
              <app-concept-picker [branch]="branch()" [inList]="memberIds()" (add)="addMembers($event)" (details)="shownConcept.set($event)" />

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
                          <td>
                            <button type="button" class="link-button" (click)="shownConcept.set(concept.conceptId)">
                              {{ concept.preferredTerm }}
                            </button>
                            @if (concept.language) {
                              <span class="lang" [attr.title]="'Preferred term language: ' + concept.language">{{ concept.language }}</span>
                            }
                            @if (concept.active === false) {
                              <span class="badge badge-warning" title="Kept in the list after this release made it inactive">Inactive</span>
                            }
                          </td>
                          <td>
                            <button
                              type="button"
                              class="icon-button"
                              (click)="removeMember(concept.conceptId)"
                              [attr.aria-label]="'Remove ' + concept.preferredTerm"
                              title="Remove from the list"
                            >
                              <svg class="icon" viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M4 4l8 8M12 4l-8 8" /></svg>
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
                              <td>
                                <button type="button" class="link-button" (click)="shownConcept.set(concept.conceptId)">
                                  {{ concept.preferredTerm }}
                                </button>
                                @if (concept.language) {
                                  <span class="lang" [attr.title]="'Preferred term language: ' + concept.language">{{ concept.language }}</span>
                                }
                              </td>
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
        @if (importing()) {
          <app-identifier-import
            [branch]="branch()"
            [inList]="memberIds()"
            [maxMembers]="maxMembers"
            (added)="addMembers($event)"
            (closed)="importing.set(false)"
          />
        }
        @if (shownConcept(); as conceptId) {
          <app-concept-details [conceptId]="conceptId" [branch]="branch()" (closed)="shownConcept.set(null)" />
        }
      } @else {
        <p class="muted" role="status">Loading value set…</p>
      }
    </main>
  `,
})
export class ValueSetEditorPage implements OnInit {
  private readonly api = inject(ValueSetApi);
  private readonly profiles = inject(ProfileApi);
  private readonly session = inject(Session);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute).snapshot;
  private readonly idParam = this.route.paramMap.get('id');
  /** Set when a new ECL value set starts from an expression suggested for a hand-picked list. */
  private readonly prefill = this.idParam === null ? eclPrefillFrom(history.state) : null;
  /** Set when the set page's Import concepts button led here, to open the import straight away. */
  private readonly startWithImport = this.route.queryParamMap?.get('import') === 'concepts';

  /** Null when creating a new value set. */
  protected readonly id = this.idParam === null ? null : Number(this.idParam);
  protected readonly backLink = this.id === null ? ['/'] : ['/value-sets', this.id];

  protected readonly form = inject(NonNullableFormBuilder).group({
    /** IMPORT is a hand-picked list that starts from an imported list of identifiers. */
    type: ['ECL' as ValueSet['type'] | 'IMPORT'],
    name: ['', [Validators.required, Validators.pattern(/\S/)]],
    description: [''],
    ecl: ['', [Validators.required, Validators.pattern(/\S/)]],
    codeSystem: ['SNOMEDCT'],
    /** Empty for a code system with no releases listed, which then uses its latest content. */
    codeSystemVersion: [''],
    shared: [false],
    /** Only offered when the owner has an organisation in their profile. */
    showOrganisation: [false],
    /** Empty for no identifier. */
    sctid: [''],
  });

  /** The result of checking the identifier against the backend, shown under the field. */
  protected readonly identifierCheck = signal<IdentifierCheckState>({ status: 'idle', sctid: '' });
  /** The identifier the set had when editing started, so changing it can be warned about. */
  protected readonly originalSctid = signal<string | null>(null);

  /** The owner's organisation from their profile, or null if they have none or it could not be loaded. */
  protected readonly organisation = signal<string | null>(null);
  protected readonly myName = computed(() => {
    const state = this.session.state();
    return state.status === 'signedIn' ? state.name : null;
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
  /** True while the dialog for importing identifiers is open. */
  protected readonly importing = signal(false);
  protected readonly maxMembers = MAX_MEMBERS;
  /** The concept whose details are open, if any. */
  protected readonly shownConcept = signal<string | null>(null);
  protected readonly tags = signal<string[]>([]);
  protected readonly knownTags = signal<string[]>([]);
  protected readonly codeSystems = signal<CodeSystem[]>([]);
  /** The releases of the chosen code system, newest first. Null while they load. */
  protected readonly versions = signal<CodeSystemVersion[] | null>(null);
  protected readonly versionsError = signal(false);
  /** When editing, the release the value set is on, fixed apart from by an upgrade. */
  protected readonly editingRelease = signal<string | null>(null);
  /** The Snowstorm branch of the chosen code system, used for the preview and for concept search. */
  protected readonly branch = signal('MAIN');

  constructor() {
    const { ecl, codeSystem, codeSystemVersion, type } = this.form.controls;
    type.valueChanges.pipe(takeUntilDestroyed()).subscribe((chosen) => this.useType(chosen));
    // Choosing a code system loads its releases and picks the latest; the preview and concept
    // search then run against the chosen release's branch.
    codeSystem.valueChanges
      .pipe(
        filter(() => this.id === null),
        switchMap((chosen) => {
          this.versions.set(null);
          this.versionsError.set(false);
          return this.api.codeSystemVersions(chosen).pipe(
            map((versions) => ({ chosen, versions, failed: false })),
            catchError(() => of({ chosen, versions: [] as CodeSystemVersion[], failed: true })),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe(({ chosen, versions, failed }) => {
        const match = this.codeSystems().find((candidate) => candidate.shortName === chosen);
        this.versionsError.set(failed);
        this.versions.set(versions);
        if (versions.length > 0) {
          // A suggestion was worked out against one release, so the new set starts on that one.
          const wanted = this.prefill?.codeSystem === chosen ? this.prefill.codeSystemVersion : null;
          const release = versions.find((candidate) => candidate.version === wanted) ?? versions[0];
          codeSystemVersion.setValue(release.version);
        } else {
          codeSystemVersion.setValue('');
          this.branch.set(match?.latestVersionBranch ?? match?.branchPath ?? 'MAIN');
        }
      });
    codeSystemVersion.valueChanges.pipe(takeUntilDestroyed()).subscribe((chosen) => {
      const release = this.versions()?.find((candidate) => candidate.version === chosen);
      if (release) {
        this.branch.set(release.branchPath);
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
    // Only to offer the organisation; without it the set is simply shown with the owner's name.
    this.profiles.get().subscribe({ next: (profile) => this.organisation.set(profile.organisation), error: () => undefined });
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

  /**
   * Asks the backend whether the identifier can be used, and shows the concept it names or why not.
   * The same checks run again when the set is saved.
   */
  protected checkIdentifier(): void {
    const sctid = this.form.controls.sctid.value.trim();
    const current = this.identifierCheck();
    if (sctid === '') {
      this.identifierCheck.set({ status: 'idle', sctid: '' });
      return;
    }
    // Already checked against this release: nothing to ask again.
    if (current.sctid === sctid && current.branch === this.branch() && current.status !== 'idle') {
      return;
    }
    const branch = this.branch();
    this.identifierCheck.set({ status: 'checking', sctid, branch });
    this.api.checkIdentifier(sctid, branch, this.id).subscribe({
      next: (concept) => this.identifierCheck.set({ status: 'found', sctid, branch, term: concept.fullySpecifiedName }),
      error: (error: unknown) => this.identifierCheck.set({ status: 'error', sctid, branch, message: describeError(error) }),
    });
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const draft: ValueSetDraft = {
      type: value.type === 'ECL' ? 'ECL' : 'MANUAL',
      codeSystem: value.codeSystem,
      name: value.name.trim(),
      description: value.description.trim(),
      ecl: this.manual() ? '' : value.ecl.trim(),
      visibility: value.shared ? 'COMMUNITY' : 'PRIVATE',
      tags: this.tags(),
      // Without an organisation the choice is not offered, and the owner's name is shown.
      showOrganisation: this.organisation() !== null && value.showOrganisation,
      sctid: value.sctid.trim(),
      conceptIds: this.manual() ? this.members().map((concept) => concept.conceptId) : [],
    };
    if (this.id === null) {
      draft.codeSystemVersion = value.codeSystemVersion === '' ? null : value.codeSystemVersion;
    }
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
    const prefill = this.prefill;
    const preferred =
      codeSystems.find((candidate) => candidate.shortName === prefill?.codeSystem) ??
      codeSystems.find((candidate) => candidate.shortName === 'SNOMEDCT') ??
      codeSystems[0];
    if (prefill) {
      this.form.patchValue({ type: 'ECL', name: prefill.name, description: prefill.description, ecl: prefill.ecl });
    }
    this.form.controls.codeSystem.setValue(preferred.shortName);
    this.loaded.set(true);
  }

  private startEditing(set: ValueSet): void {
    if (!set.ownedByMe) {
      this.loadError.set('Only the owner can edit this value set.');
      return;
    }
    this.branch.set(set.branch);
    this.editingRelease.set(set.codeSystemVersion);
    this.tags.set(set.tags);
    this.form.setValue({
      type: set.type,
      codeSystem: set.codeSystem,
      codeSystemVersion: set.codeSystemVersion ?? '',
      name: set.name,
      description: set.description,
      ecl: set.ecl,
      shared: set.visibility === 'COMMUNITY',
      showOrganisation: set.showOrganisation,
      sctid: set.sctid ?? '',
    });
    this.originalSctid.set(set.sctid);
    if (set.sctid) {
      this.identifierCheck.set({ status: 'found', sctid: set.sctid, term: set.sctidTerm ?? '' });
    }
    if (set.type === 'ECL') {
      this.loaded.set(true);
      return;
    }
    this.api.members(set.id).subscribe({
      next: (members) => {
        this.members.set(members);
        this.loaded.set(true);
        this.importing.set(this.startWithImport);
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
    const outcome =
      skipped === 0 ? `Added ${added}.` : `Added ${added}. ${skipped} already in the list or listed twice.`;
    // Changes to a saved list only last once saved, which is easy to miss after an import.
    this.memberMessage.set(this.id === null || added === 0 ? outcome : `${outcome} Save to keep the changes.`);
  }

  protected removeMember(conceptId: string): void {
    this.members.set(this.members().filter((concept) => concept.conceptId !== conceptId));
    this.memberMessage.set(null);
  }

  protected removeAllMembers(): void {
    this.members.set([]);
    this.memberMessage.set(null);
  }

  private useType(type: ValueSet['type'] | 'IMPORT'): void {
    this.manual.set(type !== 'ECL');
    if (type === 'IMPORT') {
      this.importing.set(true);
    }
    // A disabled field is left out of validation, so a hand-picked set does not need ECL.
    const { ecl } = this.form.controls;
    if (type !== 'ECL') {
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

/** The suggestion passed in the navigation state, checked as it could be anything a page put there. */
function eclPrefillFrom(state: unknown): EclPrefill | null {
  if (typeof state !== 'object' || state === null || !('eclPrefill' in state)) {
    return null;
  }
  const value = (state as { eclPrefill: unknown }).eclPrefill;
  if (typeof value !== 'object' || value === null) {
    return null;
  }
  const { name, description, ecl, codeSystem, codeSystemVersion } = value as Record<string, unknown>;
  if (typeof name !== 'string' || typeof description !== 'string' || typeof ecl !== 'string' || typeof codeSystem !== 'string') {
    return null;
  }
  return {
    name,
    description,
    ecl,
    codeSystem,
    codeSystemVersion: typeof codeSystemVersion === 'string' ? codeSystemVersion : null,
  };
}
