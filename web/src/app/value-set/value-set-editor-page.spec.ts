import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { ValueSet } from '../core/value-set-api';
import { ValueSetEditorPage } from './value-set-editor-page';

const MINE: ValueSet = {
  id: 7,
  type: 'ECL',
  codeSystem: 'SNOMEDCT',
  name: 'Asthma diagnoses',
  description: 'All asthma disorders.',
  ecl: '<< 195967001',
  branch: 'MAIN',
  codeSystemVersion: null,
  status: 'DRAFT',
  visibility: 'PRIVATE',
  ownerName: 'Me',
  ownerIsOrganisation: false,
  ownerAvatarUrl: null,
  ownerContactable: false,
  ownedByMe: true,
  showOrganisation: false,
  sctid: null,
  sctidTerm: null,
  updatedAt: '2026-11-16T12:00:00Z',
  latestVersion: null,
  unpublishedChanges: false,
  tags: [],
  deprecated: false,
  allVersionsDeprecated: false,
  deprecationReason: null,
};

function type(page: HTMLElement, selector: string, value: string): void {
  const field = page.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
  field.value = value;
  field.dispatchEvent(new Event('input'));
}

function submit(page: HTMLElement): void {
  page.querySelector('form')!.dispatchEvent(new Event('submit'));
}

describe('ValueSetEditorPage', () => {
  let http: HttpTestingController;

  function create(
    params: Record<string, string>,
    query: Record<string, string> = {},
    organisation: string | null = null,
  ): ComponentFixture<ValueSetEditorPage> {
    TestBed.configureTestingModule({
      imports: [ValueSetEditorPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap(params), queryParamMap: convertToParamMap(query) } },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ValueSetEditorPage);
    fixture.detectChanges();
    http.expectOne('/api/tags').flush(['respiratory', 'registry']);
    http.expectOne('/api/profile').flush({ name: 'Me', organisation, contactable: false, accountEmail: null, avatarUrl: null });
    if (!('id' in params)) {
      // A new value set starts by asking which code systems Snowstorm has.
      http.expectOne('/api/code-systems').flush([
        { shortName: 'SNOMEDCT', name: 'SNOMEDCT', branchPath: 'MAIN', latestVersion: '2026-01-01', latestVersionBranch: 'MAIN/2026-01-01' },
        { shortName: 'SNOMEDCT-BE', name: 'Belgian Edition', branchPath: 'MAIN/SNOMEDCT-BE', latestVersion: null, latestVersionBranch: null },
      ]);
      // Choosing the default code system loads its releases, newest first.
      http.expectOne('/api/code-systems/SNOMEDCT/versions').flush([
        { version: '2026-01-01', branchPath: 'MAIN/2026-01-01' },
        { version: '2025-07-01', branchPath: 'MAIN/2025-07-01' },
      ]);
      fixture.detectChanges();
    }
    return fixture;
  }

  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('previews the definition after a pause in typing, then creates the value set', async () => {
    const fixture = create({});
    const page = fixture.nativeElement as HTMLElement;
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    type(page, '#name', 'Childhood asthma');
    type(page, '#ecl', '<< 233678006');
    http.expectNone(() => true);
    vi.advanceTimersByTime(500);

    http
      .expectOne('/api/ecl/expansion?ecl=%3C%3C%20233678006&branch=MAIN/2026-01-01&limit=20')
      .flush({ items: [{ conceptId: '233678006', preferredTerm: 'Childhood asthma', fullySpecifiedName: null }], total: 9, offset: 0, limit: 20 });
    fixture.detectChanges();
    expect(page.textContent).toContain('9 concepts match.');

    submit(page);
    const request = http.expectOne({ method: 'POST', url: '/api/value-sets' });
    expect(request.request.body).toEqual({
      type: 'ECL',
      codeSystem: 'SNOMEDCT',
      name: 'Childhood asthma',
      description: '',
      ecl: '<< 233678006',
      visibility: 'PRIVATE',
      tags: [],
      showOrganisation: false,
      sctid: '',
      conceptIds: [],
      codeSystemVersion: '2026-01-01',
    });
    request.flush({ ...MINE, id: 12 });

    expect(navigate).toHaveBeenCalledWith(['/value-sets', 12]);
  });

  it('builds against the chosen code system and searches on its branch', () => {
    const fixture = create({});
    const page = fixture.nativeElement as HTMLElement;
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    const select = page.querySelector<HTMLSelectElement>('#code-system')!;
    expect(Array.from(select.options).map((option) => option.textContent?.trim())).toEqual([
      'SNOMEDCT',
      'Belgian Edition',
    ]);
    select.value = 'SNOMEDCT-BE';
    select.dispatchEvent(new Event('change'));
    http.expectOne('/api/code-systems/SNOMEDCT-BE/versions').flush([]);
    fixture.detectChanges();
    expect(page.querySelector('#code-system-version')?.textContent).toContain('no release yet');
    type(page, '#name', 'Belgian asthma');
    type(page, '#ecl', '<< 195967001');
    vi.advanceTimersByTime(500);
    http
      .expectOne('/api/ecl/expansion?ecl=%3C%3C%20195967001&branch=MAIN/SNOMEDCT-BE&limit=20')
      .flush({ items: [], total: 0, offset: 0, limit: 20 });

    submit(page);
    const request = http.expectOne({ method: 'POST', url: '/api/value-sets' });
    expect(request.request.body.codeSystem).toBe('SNOMEDCT-BE');
    expect(request.request.body.codeSystemVersion).toBeNull();
    request.flush({ ...MINE, id: 12 });
  });

  it('builds on an earlier release when one is chosen', () => {
    const fixture = create({});
    const page = fixture.nativeElement as HTMLElement;
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    const release = page.querySelector<HTMLSelectElement>('#code-system-version')!;
    expect(Array.from(release.options).map((option) => option.textContent?.trim())).toEqual([
      '2026-01-01 (latest)',
      '2025-07-01',
    ]);
    release.value = '2025-07-01';
    release.dispatchEvent(new Event('change'));
    type(page, '#name', 'Asthma as of July 2025');
    type(page, '#ecl', '<< 195967001');
    vi.advanceTimersByTime(500);
    http
      .expectOne('/api/ecl/expansion?ecl=%3C%3C%20195967001&branch=MAIN/2025-07-01&limit=20')
      .flush({ items: [], total: 0, offset: 0, limit: 20 });

    submit(page);
    const request = http.expectOne({ method: 'POST', url: '/api/value-sets' });
    expect(request.request.body.codeSystemVersion).toBe('2025-07-01');
    request.flush({ ...MINE, id: 12 });
  });

  it('adds typed and suggested tags, tidied up, and sends them when saving', () => {
    const fixture = create({});
    const page = fixture.nativeElement as HTMLElement;
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const entry = page.querySelector<HTMLInputElement>('#tag-entry')!;
    const addTag = Array.from(page.querySelectorAll('button')).find((b) => b.textContent?.includes('Add tag'))!;

    expect(Array.from(page.querySelectorAll('#known-tags option')).map((o) => o.getAttribute('value'))).toEqual([
      'respiratory',
      'registry',
    ]);
    entry.value = '  Primary   Care ';
    addTag.click();
    entry.value = 'respiratory';
    entry.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    entry.value = '<script>';
    addTag.click();
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('A tag can use letters');

    type(page, '#name', 'Tagged');
    type(page, '#ecl', '<< 195967001 |Asthma|');
    submit(page);
    const request = http.expectOne({ method: 'POST', url: '/api/value-sets' });
    expect(request.request.body.tags).toEqual(['primary care', 'respiratory']);
    request.flush({ ...MINE, id: 12 });
    vi.advanceTimersByTime(500);
    http.expectOne((r) => r.url === '/api/ecl/expansion').flush({ items: [], total: 0, offset: 0, limit: 20 });
  });

  it('does not save while required fields are empty', () => {
    const fixture = create({});
    const page = fixture.nativeElement as HTMLElement;

    submit(page);
    fixture.detectChanges();

    http.expectNone({ method: 'POST', url: '/api/value-sets' });
    expect(page.textContent).toContain('Give the value set a name.');
    expect(page.textContent).toContain('Enter an ECL expression');
  });

  it('keeps the form usable and explains why when the preview fails', () => {
    const fixture = create({});
    const page = fixture.nativeElement as HTMLElement;

    type(page, '#ecl', 'not ecl');
    vi.advanceTimersByTime(500);
    http
      .expectOne((request) => request.url === '/api/ecl/expansion')
      .flush({ detail: 'Snowstorm rejected the request. Check the ECL expression and branch.' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Snowstorm rejected the request');
    expect(page.querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(false);
  });

  it('checks a SNOMED CT identifier with the backend and shows the concept it names', async () => {
    const fixture = create({ id: '7' });
    const page = fixture.nativeElement as HTMLElement;
    http.expectOne('/api/value-sets/7').flush(MINE);
    fixture.detectChanges();
    await fixture.whenStable();

    type(page, '#sctid', '723264001');
    page.querySelector('#sctid')!.dispatchEvent(new Event('blur'));
    const check = http.expectOne((request) => request.url === '/api/set-identifiers/check');
    expect(check.request.params.get('sctid')).toBe('723264001');
    expect(check.request.params.get('branch')).toBe(MINE.branch);
    expect(check.request.params.get('valueSetId')).toBe('7');
    check.flush({ conceptId: '723264001', fullySpecifiedName: 'Lateralizable body structure reference set (foundation metadata concept)', preferredTerm: 'Lateralizable body structure reference set' });
    fixture.detectChanges();
    expect(page.querySelector('.identifier-found')?.textContent).toContain('Lateralizable body structure reference set (foundation metadata concept)');

    // Pressing Check for the same identifier does not ask again.
    Array.from(page.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Check')!.click();
    http.expectNone((request) => request.url === '/api/set-identifiers/check');
  });

  it('shows why an identifier cannot be used', async () => {
    const fixture = create({ id: '7' });
    const page = fixture.nativeElement as HTMLElement;
    http.expectOne('/api/value-sets/7').flush(MINE);
    fixture.detectChanges();
    await fixture.whenStable();

    type(page, '#sctid', '22298006');
    Array.from(page.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Check')!.click();
    http
      .expectOne((request) => request.url === '/api/set-identifiers/check')
      .flush({ detail: '22298006 is not a reference set.' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    expect(page.querySelector('#sctid-result [role="alert"]')?.textContent).toContain('22298006 is not a reference set.');
  });

  it('offers the organisation from the profile as who the set is shown as made by', async () => {
    const fixture = create({ id: '7' }, {}, 'Example Health Trust');
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const page = fixture.nativeElement as HTMLElement;
    http.expectOne('/api/value-sets/7').flush({ ...MINE, showOrganisation: true });
    fixture.detectChanges();
    await fixture.whenStable();

    const radios = page.querySelectorAll<HTMLInputElement>('input[type="radio"][formcontrolname="showOrganisation"]');
    expect(radios.length).toBe(2);
    expect(radios[1].checked).toBe(true);
    expect(radios[1].parentElement?.textContent).toContain('Example Health Trust');

    radios[0].click();
    submit(page);
    const request = http.expectOne({ method: 'PUT', url: '/api/value-sets/7' });
    expect(request.request.body.showOrganisation).toBe(false);
    request.flush(MINE);
  });

  it('points to the profile when the owner has no organisation, and saves the owner as themselves', async () => {
    const fixture = create({ id: '7' });
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const page = fixture.nativeElement as HTMLElement;
    http.expectOne('/api/value-sets/7').flush({ ...MINE, showOrganisation: true });
    fixture.detectChanges();
    await fixture.whenStable();

    expect(page.querySelector('input[formcontrolname="showOrganisation"]')).toBeNull();
    expect(page.querySelector('a[href="/profile"]')?.textContent).toContain('profile settings');

    submit(page);
    const request = http.expectOne({ method: 'PUT', url: '/api/value-sets/7' });
    expect(request.request.body.showOrganisation).toBe(false);
    request.flush(MINE);
  });

  it('loads an existing value set and saves the changes', () => {
    const fixture = create({ id: '7' });
    const page = fixture.nativeElement as HTMLElement;
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    http.expectOne('/api/value-sets/7').flush(MINE);
    fixture.detectChanges();
    expect(page.querySelector<HTMLInputElement>('#name')?.value).toBe('Asthma diagnoses');

    page.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
    submit(page);
    const request = http.expectOne({ method: 'PUT', url: '/api/value-sets/7' });
    expect(request.request.body.visibility).toBe('COMMUNITY');
    request.flush({ ...MINE, visibility: 'COMMUNITY' });

    expect(navigate).toHaveBeenCalledWith(['/value-sets', 7]);
  });

  const ASTHMA = { conceptId: '195967001', preferredTerm: 'Asthma', fullySpecifiedName: 'Asthma (disorder)' };
  const CHILDHOOD = { conceptId: '233678006', preferredTerm: 'Childhood asthma', fullySpecifiedName: 'Childhood asthma (disorder)' };
  const ALLERGIC = { conceptId: '389145006', preferredTerm: 'Allergic asthma', fullySpecifiedName: 'Allergic asthma (disorder)' };

  function button(page: HTMLElement, label: string): HTMLButtonElement {
    return Array.from(page.querySelectorAll('button')).find((b) => b.textContent?.includes(label))!;
  }

  function searchFor(fixture: ComponentFixture<ValueSetEditorPage>, term: string, results: object[]): void {
    type(fixture.nativeElement as HTMLElement, '#concept-search', term);
    vi.advanceTimersByTime(400);
    http
      .expectOne(`/api/concepts?term=${term}&branch=MAIN/2026-01-01`)
      .flush({ items: results, total: results.length, offset: 0, limit: 25 });
    http
      .expectOne((request) => request.url === '/api/concepts/child-counts')
      .flush({ '195967001': 27, '233678006': 0 });
    fixture.detectChanges();
  }

  it('builds a hand-picked list from ticked search results and from their children', () => {
    const fixture = create({});
    const page = fixture.nativeElement as HTMLElement;
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    page.querySelector<HTMLInputElement>('input[type="radio"][value="MANUAL"]')!.click();
    fixture.detectChanges();
    expect(page.querySelector('#ecl')).toBeNull();
    type(page, '#name', 'Picked asthma codes');

    searchFor(fixture, 'asthma', [ASTHMA, CHILDHOOD]);
    expect(page.querySelector('.pick-list')?.textContent).toContain('27 children');
    expect(page.querySelector('.pick-list')?.textContent).toContain('No children');
    const boxes = page.querySelectorAll<HTMLInputElement>('.pick-list input[type="checkbox"]');
    boxes[0].click();
    boxes[1].click();
    fixture.detectChanges();
    button(page, 'Add selected (2)').click();
    fixture.detectChanges();
    expect(page.textContent).toContain('2 concepts');

    // Tick the first result again and add its children: one new, one already in the list.
    page.querySelectorAll<HTMLInputElement>('.pick-list input[type="checkbox"]')[0].click();
    fixture.detectChanges();
    button(page, 'Add children of selected').click();
    http.expectOne('/api/concepts/195967001/children?branch=MAIN/2026-01-01').flush({ items: [CHILDHOOD, ALLERGIC], total: 2 });
    fixture.detectChanges();
    expect(page.textContent).toContain('Added 1. 1 already in the list or listed twice.');
    expect(page.textContent).toContain('3 concepts');

    page.querySelector<HTMLButtonElement>('button[aria-label="Remove Asthma"]')!.click();
    fixture.detectChanges();

    submit(page);
    const request = http.expectOne({ method: 'POST', url: '/api/value-sets' });
    expect(request.request.body).toEqual({
      type: 'MANUAL',
      codeSystem: 'SNOMEDCT',
      name: 'Picked asthma codes',
      description: '',
      ecl: '',
      visibility: 'PRIVATE',
      tags: [],
      showOrganisation: false,
      sctid: '',
      conceptIds: ['233678006', '389145006'],
      codeSystemVersion: '2026-01-01',
    });
    request.flush({ ...MINE, id: 12, type: 'MANUAL' });
  });

  it('builds a hand-picked list from an imported list of identifiers, after a review', () => {
    const fixture = create({});
    const page = fixture.nativeElement as HTMLElement;
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    page.querySelector<HTMLInputElement>('input[type="radio"][value="IMPORT"]')!.click();
    fixture.detectChanges();
    expect(page.querySelector('#ecl')).toBeNull();
    expect(page.querySelector('dialog')?.textContent).toContain('Import concepts');
    type(page, '#name', 'Imported asthma codes');

    type(page, '#import-text', 'Code\tTerm\n195967001\tAsthma\n100000000\tOld asthma\n12345\tTypo');
    fixture.detectChanges();
    expect(page.querySelector('dialog')?.textContent).toContain('3 identifiers read, after a header row.');
    button(page, 'Check identifiers').click();
    const check = http.expectOne({ method: 'POST', url: '/api/identifier-checks' });
    expect(check.request.body).toEqual({
      branch: 'MAIN/2026-01-01',
      entries: [
        { identifier: '195967001', term: 'Asthma' },
        { identifier: '100000000', term: 'Old asthma' },
        { identifier: '12345', term: 'Typo' },
      ],
    });
    check.flush({
      branch: 'MAIN/2026-01-01',
      entryCount: 3,
      duplicateCount: 0,
      found: [{ ...ASTHMA, language: 'en', descriptionId: null, suppliedTerm: 'Asthma', termDiffers: false }],
      inactive: [
        {
          conceptId: '100000000',
          term: 'Old asthma',
          reason: 'OUTDATED',
          descriptionId: null,
          replacements: [
            { conceptId: CHILDHOOD.conceptId, term: CHILDHOOD.preferredTerm, association: 'REPLACED_BY', alreadyInSet: false },
            { conceptId: ALLERGIC.conceptId, term: ALLERGIC.preferredTerm, association: 'REPLACED_BY', alreadyInSet: false },
          ],
        },
      ],
      rejected: [{ identifier: '12345', reason: 'NOT_AN_IDENTIFIER' }],
    });
    fixture.detectChanges();

    const dialog = page.querySelector('dialog')!;
    expect(dialog.textContent).toContain('Not a SNOMED CT identifier');
    expect(dialog.textContent).toContain('Outdated');
    expect(button(page, 'Add 3 new concepts')).toBeTruthy();
    // Decline the second suggested replacement.
    dialog.querySelectorAll<HTMLInputElement>('.review input[type="checkbox"]')[1].click();
    fixture.detectChanges();
    button(page, 'Add 2 new concepts').click();
    fixture.detectChanges();
    expect(page.textContent).toContain('2 concepts');
    expect(page.textContent).toContain('Childhood asthma');

    submit(page);
    const request = http.expectOne({ method: 'POST', url: '/api/value-sets' });
    expect(request.request.body.type).toBe('MANUAL');
    expect(request.request.body.conceptIds).toEqual(['195967001', '233678006']);
    request.flush({ ...MINE, id: 12, type: 'MANUAL' });
  });

  it('imports more identifiers into a saved list, pointing out those already in it', () => {
    const fixture = create({ id: '7' }, { import: 'concepts' });
    const page = fixture.nativeElement as HTMLElement;
    http.expectOne('/api/value-sets/7').flush({ ...MINE, type: 'MANUAL', ecl: '', branch: 'MAIN/2026-01-01' });
    http.expectOne('/api/value-sets/7/members').flush([ASTHMA, CHILDHOOD]);
    fixture.detectChanges();

    const dialog = page.querySelector('dialog')!;
    expect(dialog.textContent).toContain('The list has 2 concepts');
    type(page, '#import-text', '195967001\n389145006');
    fixture.detectChanges();
    button(page, 'Check identifiers').click();
    http.expectOne({ method: 'POST', url: '/api/identifier-checks' }).flush({
      branch: 'MAIN/2026-01-01',
      entryCount: 2,
      duplicateCount: 0,
      found: [ASTHMA, ALLERGIC].map((concept) => ({
        ...concept,
        language: 'en',
        descriptionId: null,
        suppliedTerm: null,
        termDiffers: false,
      })),
      inactive: [],
      rejected: [],
    });
    fixture.detectChanges();

    expect(dialog.textContent).toContain('1 already in the list');
    expect(dialog.textContent).toContain('2 found: 1 new, 1 already in the list');
    const filter = page.querySelector<HTMLSelectElement>('#found-filter')!;
    filter.value = 'inList';
    filter.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(dialog.querySelector('details tbody')?.textContent).toContain('Asthma');
    expect(dialog.querySelector('details tbody')?.textContent).not.toContain('Allergic asthma');

    button(page, 'Add 1 new concept').click();
    fixture.detectChanges();
    expect(page.textContent).toContain('Added 1. Save to keep the changes.');
    expect(page.textContent).toContain('3 concepts');

    // The import can be run again on the same list.
    button(page, 'Import concepts').click();
    fixture.detectChanges();
    expect(page.querySelector('dialog')?.textContent).toContain('The list has 3 concepts');
    button(page, 'Cancel').click();
    fixture.detectChanges();

    submit(page);
    const request = http.expectOne({ method: 'PUT', url: '/api/value-sets/7' });
    expect(request.request.body.conceptIds).toEqual(['195967001', '233678006', '389145006']);
  });

  it('adds what an ECL expression matches to a hand-picked list, once', () => {
    const fixture = create({});
    const page = fixture.nativeElement as HTMLElement;

    page.querySelector<HTMLInputElement>('input[type="radio"][value="IMPORT"]')!.click();
    fixture.detectChanges();
    page.querySelector<HTMLInputElement>('input[name="import-source"][value="ecl"]')!.click();
    fixture.detectChanges();
    expect(page.querySelector('#import-text')).toBeNull();

    type(page, '#import-ecl', '<< 404684003');
    fixture.detectChanges();
    button(page, 'Find concepts').click();
    http
      .expectOne({ method: 'POST', url: '/api/identifier-checks/ecl' })
      .flush(
        { detail: 'This expression matches 120000 concepts, and a list can hold at most 2000.' },
        { status: 400, statusText: 'Bad Request' },
      );
    fixture.detectChanges();
    expect(page.querySelector('dialog [role="alert"]')?.textContent).toContain('matches 120000 concepts');

    type(page, '#import-ecl', ' < 195967001 ');
    fixture.detectChanges();
    button(page, 'Find concepts').click();
    const request = http.expectOne({ method: 'POST', url: '/api/identifier-checks/ecl' });
    expect(request.request.body).toEqual({ branch: 'MAIN/2026-01-01', ecl: '< 195967001' });
    request.flush({
      branch: 'MAIN/2026-01-01',
      entryCount: 2,
      duplicateCount: 0,
      found: [CHILDHOOD, ALLERGIC].map((concept) => ({
        ...concept,
        language: 'en',
        descriptionId: null,
        suppliedTerm: null,
        termDiffers: false,
      })),
      inactive: [],
      rejected: [],
    });
    fixture.detectChanges();
    expect(page.querySelector('dialog')?.textContent).not.toContain('not recognised');
    button(page, 'Add 2 new concepts').click();
    fixture.detectChanges();
    expect(page.textContent).toContain('2 concepts');
    expect(page.textContent).toContain('Allergic asthma');
  });

  it('starts a new ECL value set from a suggested expression, on the release it was worked out against', () => {
    history.replaceState(
      {
        eclPrefill: {
          name: 'Picked asthma codes (ECL)',
          description: 'Defined by ECL worked out from the hand-picked list "Picked asthma codes".',
          ecl: '<< 195967001 |Asthma|',
          codeSystem: 'SNOMEDCT',
          codeSystemVersion: '2025-07-01',
        },
      },
      '',
    );
    try {
      const fixture = create({});
      const page = fixture.nativeElement as HTMLElement;
      expect(page.querySelector<HTMLInputElement>('#name')!.value).toBe('Picked asthma codes (ECL)');
      expect(page.querySelector<HTMLTextAreaElement>('#ecl')!.value).toBe('<< 195967001 |Asthma|');
      expect(page.querySelector<HTMLSelectElement>('#code-system-version')!.value).toBe('2025-07-01');
      expect(page.querySelector<HTMLInputElement>('input[type="radio"][value="ECL"]')!.checked).toBe(true);
    } finally {
      history.replaceState(null, '');
    }
  });

  it('loads the existing list when editing a hand-picked value set', () => {
    const fixture = create({ id: '7' });
    const page = fixture.nativeElement as HTMLElement;

    http.expectOne('/api/value-sets/7').flush({ ...MINE, type: 'MANUAL', ecl: '' });
    http.expectOne('/api/value-sets/7/members').flush([ASTHMA, CHILDHOOD]);
    fixture.detectChanges();

    expect(page.querySelector('input[type="radio"]')).toBeNull();
    expect(page.querySelector('#ecl')).toBeNull();
    expect(page.textContent).toContain('2 concepts');
    expect(page.textContent).toContain('Childhood asthma');
  });

  it('refuses to edit a value set owned by someone else', () => {
    const fixture = create({ id: '7' });

    http.expectOne('/api/value-sets/7').flush({ ...MINE, ownedByMe: false });
    fixture.detectChanges();

    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Only the owner can edit this value set.');
    expect(page.querySelector('form')).toBeNull();
  });
});
