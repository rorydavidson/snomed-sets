import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { catchError, of, switchMap, tap } from 'rxjs';
import { Direction, MAX_MAP_ROWS, MapApi, SourceRow, describeMapError } from '../core/map-api';
import { CodeSystem, CodeSystemVersion, Concept, ValueSetApi, describeError } from '../core/value-set-api';
import { ConceptDetails } from '../value-set/concept-details';
import { ConceptPicker } from '../value-set/concept-picker';
import { IdentifierImport } from '../value-set/identifier-import';
import { CodeListInput } from './code-list-input';

/**
 * Starts a map: its direction, the release it is built on, the local codes, and for a map from
 * SNOMED CT the concepts to map. The mapping itself is done on the map's own page.
 */
@Component({
  selector: 'app-map-new-page',
  imports: [ReactiveFormsModule, RouterLink, CodeListInput, ConceptPicker, ConceptDetails, IdentifierImport],
  template: `
    <main class="page">
      <p><a routerLink="/maps">Back to your maps</a></p>

      @if (loadError(); as message) {
        <div class="error-panel" role="alert">
          <h1>A map cannot be created right now</h1>
          <p>{{ message }}</p>
        </div>
      } @else if (loaded()) {
        <h1>New map</h1>
        <p>
          A map links the codes of a local list with SNOMED CT concepts, in one direction. It is built on one release,
          and is private to you.
        </p>

        <form class="form" [formGroup]="form" (ngSubmit)="create()" novalidate>
          <div class="columns">
            <section>
              <fieldset class="field">
                <legend>Which way does it map?</legend>
                <label class="checkbox">
                  <input type="radio" formControlName="direction" value="TO_SNOMED" />
                  <span>To SNOMED CT: each local code is given SNOMED CT concepts</span>
                </label>
                <label class="checkbox">
                  <input type="radio" formControlName="direction" value="FROM_SNOMED" />
                  <span>From SNOMED CT: each concept is given local codes</span>
                </label>
                <p class="meta">This cannot be changed after the map is created.</p>
              </fieldset>

              <div class="field">
                <label for="name">Name</label>
                <input id="name" type="text" formControlName="name" maxlength="200" autocomplete="off" />
                @if (showError('name')) {
                  <p class="field-error">Give the map a name.</p>
                }
              </div>

              <div class="field">
                <label for="description">Description</label>
                <textarea id="description" formControlName="description" rows="3" maxlength="2000"></textarea>
              </div>

              <div class="field">
                <label for="local-name">Name of the local code list</label>
                <input id="local-name" type="text" formControlName="localSystemName" maxlength="200" autocomplete="off" />
                <p class="meta">For example "Ward diagnosis picklist".</p>
                @if (showError('localSystemName')) {
                  <p class="field-error">Say what the local codes are.</p>
                }
              </div>

              <div class="field">
                <label for="local-version">Version of the local code list (optional)</label>
                <input id="local-version" type="text" formControlName="localSystemVersion" maxlength="100" autocomplete="off" />
              </div>

              <div class="field">
                <label for="local-uri">Identifier of the local code system (optional)</label>
                <input id="local-uri" type="text" class="mono" formControlName="localSystemUri" maxlength="500" autocomplete="off" />
                <p class="meta">A URI, if the code system has one. It is stored as typed and never opened.</p>
              </div>

              <div class="field">
                <label for="code-system">Code system</label>
                <select id="code-system" formControlName="codeSystem">
                  @for (codeSystem of codeSystems(); track codeSystem.shortName) {
                    <option [value]="codeSystem.shortName">{{ codeSystem.name }}</option>
                  }
                </select>
              </div>

              <div class="field">
                <label for="code-system-version">Release</label>
                @if (versions(); as list) {
                  @if (list.length > 0) {
                    <select id="code-system-version" formControlName="codeSystemVersion">
                      @for (release of list; track release.version) {
                        <option [value]="release.version">{{ release.version }}{{ $first ? ' (latest)' : '' }}</option>
                      }
                    </select>
                  } @else if (versionsError()) {
                    <p id="code-system-version" class="field-error">The releases could not be loaded, so the latest release will be used.</p>
                  } @else {
                    <p id="code-system-version">This code system has no release yet, so its latest content is used.</p>
                  }
                } @else {
                  <p id="code-system-version" class="muted" role="status">Loading releases…</p>
                }
                <p class="meta">The code system and release cannot be changed here afterwards.</p>
              </div>
            </section>

            <section class="wide">
              <h2>Local codes</h2>
              <p>
                {{ fromSnomed() ? 'The codes that concepts are mapped to. Targets are chosen from this list.' : 'The codes to map to SNOMED CT.' }}
              </p>
              <app-code-list-input (rowsChange)="rows.set($event)" />
              <p class="meta">You can start with none and add codes later.</p>

              @if (fromSnomed()) {
                <h2>SNOMED CT concepts to map</h2>
                <p>Search for concepts, or import a list of identifiers or everything an ECL expression matches.</p>
                <div class="actions">
                  <button type="button" class="button button-secondary" (click)="importing.set(true)">Import concepts</button>
                  <span>{{ concepts().length }} {{ concepts().length === 1 ? 'concept' : 'concepts' }}</span>
                  @if (concepts().length > 0) {
                    <button type="button" class="link-button" (click)="concepts.set([])">Remove all</button>
                  }
                </div>
                @if (conceptMessage(); as message) {
                  <p class="meta" role="status">{{ message }}</p>
                }
                <app-concept-picker [branch]="branch()" [inList]="conceptIds()" (add)="addConcepts($event)" (details)="shownConcept.set($event)" />
                @if (concepts().length > 0) {
                  <div class="table-scroll table-tall">
                    <table>
                      <thead>
                        <tr><th scope="col">Concept</th><th scope="col">Identifier</th><th scope="col"><span class="visually-hidden">Remove</span></th></tr>
                      </thead>
                      <tbody>
                        @for (concept of concepts(); track concept.conceptId) {
                          <tr>
                            <td>{{ concept.preferredTerm }}</td>
                            <td class="mono">{{ concept.conceptId }}</td>
                            <td>
                              <button type="button" class="link-button" (click)="removeConcept(concept.conceptId)" [attr.aria-label]="'Remove ' + concept.preferredTerm">Remove</button>
                            </td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  </div>
                }
                <p class="meta">You can start with none and add concepts later.</p>
              }
            </section>
          </div>

          @if (saveError(); as message) {
            <p class="field-error" role="alert">{{ message }}</p>
          }
          <div class="actions">
            <button type="submit" class="button button-primary" [disabled]="saving() || rows() === null">
              {{ saving() ? 'Creating…' : 'Create map' }}
            </button>
            <a class="button button-secondary" routerLink="/maps">Cancel</a>
          </div>
        </form>
        @if (importing()) {
          <app-identifier-import [branch]="branch()" [inList]="conceptIds()" (added)="addConcepts($event)" (closed)="importing.set(false)" />
        }
        @if (shownConcept(); as conceptId) {
          <app-concept-details [conceptId]="conceptId" [branch]="branch()" (closed)="shownConcept.set(null)" />
        }
      } @else {
        <p class="muted" role="status">Loading…</p>
      }
    </main>
  `,
})
export class MapNewPage implements OnInit {
  private readonly api = inject(MapApi);
  private readonly valueSets = inject(ValueSetApi);
  private readonly router = inject(Router);

  protected readonly form = inject(NonNullableFormBuilder).group({
    direction: ['TO_SNOMED' as Direction],
    name: ['', [Validators.required, Validators.pattern(/\S/)]],
    description: [''],
    localSystemName: ['', [Validators.required, Validators.pattern(/\S/)]],
    localSystemVersion: [''],
    localSystemUri: [''],
    codeSystem: ['SNOMEDCT'],
    /** Empty for a code system with no releases listed, which then uses its latest content. */
    codeSystemVersion: [''],
  });

  protected readonly loaded = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly codeSystems = signal<CodeSystem[]>([]);
  /** The releases of the chosen code system, newest first. Null while they load. */
  protected readonly versions = signal<CodeSystemVersion[] | null>(null);
  protected readonly versionsError = signal(false);
  /** The local codes to start with, or null while the list cannot be used as it is. */
  protected readonly rows = signal<SourceRow[] | null>([]);

  protected readonly fromSnomed = signal(false);
  /** The Snowstorm branch of the chosen release, which concepts are searched and checked on. */
  protected readonly branch = signal('MAIN');
  /** The concepts to map, for a map from SNOMED CT. */
  protected readonly concepts = signal<Concept[]>([]);
  protected readonly conceptIds = computed<ReadonlySet<string>>(() => new Set(this.concepts().map((concept) => concept.conceptId)));
  protected readonly conceptMessage = signal<string | null>(null);
  protected readonly importing = signal(false);
  /** The concept whose details are open, if any. */
  protected readonly shownConcept = signal<string | null>(null);

  constructor() {
    const { codeSystem, codeSystemVersion, direction } = this.form.controls;
    direction.valueChanges.pipe(takeUntilDestroyed()).subscribe((chosen) => this.fromSnomed.set(chosen === 'FROM_SNOMED'));
    // Choosing a code system loads its releases and picks the latest.
    codeSystem.valueChanges
      .pipe(
        tap(() => {
          this.versions.set(null);
          this.versionsError.set(false);
        }),
        switchMap((chosen) =>
          this.valueSets.codeSystemVersions(chosen).pipe(
            catchError(() => {
              this.versionsError.set(true);
              return of<CodeSystemVersion[]>([]);
            }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((versions) => {
        this.versions.set(versions);
        codeSystemVersion.setValue(versions[0]?.version ?? '');
        if (versions.length === 0) {
          // No release to build on, so the code system's own branch is used.
          this.useBranch(this.codeSystems().find((candidate) => candidate.shortName === codeSystem.value)?.branchPath ?? 'MAIN');
        }
      });
    codeSystemVersion.valueChanges.pipe(takeUntilDestroyed()).subscribe((chosen) => {
      const release = this.versions()?.find((candidate) => candidate.version === chosen);
      if (release) {
        this.useBranch(release.branchPath);
      }
    });
  }

  ngOnInit(): void {
    this.valueSets.codeSystems().subscribe({
      next: (codeSystems) => {
        if (codeSystems.length === 0) {
          this.loadError.set('Snowstorm has no code systems to build a map against.');
          return;
        }
        this.codeSystems.set(codeSystems);
        const preferred = codeSystems.find((candidate) => candidate.shortName === 'SNOMEDCT') ?? codeSystems[0];
        this.form.controls.codeSystem.setValue(preferred.shortName);
        this.loaded.set(true);
      },
      error: (error: unknown) => this.loadError.set(describeError(error)),
    });
  }

  /** Concepts are picked on one release, so they are dropped when another is chosen. */
  private useBranch(branch: string): void {
    if (branch === this.branch()) {
      return;
    }
    this.branch.set(branch);
    if (this.concepts().length > 0) {
      this.concepts.set([]);
      this.conceptMessage.set('The concepts were removed, as they were chosen on another release. Add them again.');
    }
  }

  protected addConcepts(added: Concept[]): void {
    const byId = new Map(this.concepts().map((concept) => [concept.conceptId, concept]));
    const before = byId.size;
    added.forEach((concept) => byId.set(concept.conceptId, concept));
    if (byId.size > MAX_MAP_ROWS) {
      this.conceptMessage.set(`Nothing was added: a map can hold at most ${MAX_MAP_ROWS} concepts.`);
      return;
    }
    this.concepts.set([...byId.values()]);
    const skipped = added.length - (byId.size - before);
    this.conceptMessage.set(skipped === 0 ? `Added ${byId.size - before}.` : `Added ${byId.size - before}. ${skipped} already in the list or listed twice.`);
  }

  protected removeConcept(conceptId: string): void {
    this.concepts.set(this.concepts().filter((concept) => concept.conceptId !== conceptId));
    this.conceptMessage.set(null);
  }

  protected showError(control: 'name' | 'localSystemName'): boolean {
    const field = this.form.controls[control];
    return field.invalid && field.touched;
  }

  protected create(): void {
    const rows = this.rows();
    if (this.form.invalid || rows === null) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.saving.set(true);
    this.saveError.set(null);
    this.api
      .create({
        details: {
          name: value.name.trim(),
          description: value.description.trim(),
          localSystemName: value.localSystemName.trim(),
          localSystemUri: value.localSystemUri.trim() || null,
          localSystemVersion: value.localSystemVersion.trim() || null,
        },
        codeSystem: value.codeSystem,
        direction: value.direction,
        codeSystemVersion: value.codeSystemVersion === '' ? null : value.codeSystemVersion,
        rows,
        conceptIds: value.direction === 'FROM_SNOMED' ? this.concepts().map((concept) => concept.conceptId) : [],
      })
      .subscribe({
        next: (created) => void this.router.navigate(['/maps', created.id]),
        error: (error: unknown) => {
          this.saving.set(false);
          this.saveError.set(describeMapError(error));
        },
      });
  }
}
