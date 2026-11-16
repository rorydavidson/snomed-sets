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
  status: 'DRAFT',
  visibility: 'PRIVATE',
  ownerName: 'Me',
  ownedByMe: true,
  updatedAt: '2026-11-16T12:00:00Z',
  latestVersion: null,
  unpublishedChanges: false,
  tags: [],
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

  function create(params: Record<string, string>): ComponentFixture<ValueSetEditorPage> {
    TestBed.configureTestingModule({
      imports: [ValueSetEditorPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap(params) } } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ValueSetEditorPage);
    fixture.detectChanges();
    http.expectOne('/api/tags').flush(['respiratory', 'registry']);
    if (!('id' in params)) {
      // A new value set starts by asking which code systems Snowstorm has.
      http.expectOne('/api/code-systems').flush([
        { shortName: 'SNOMEDCT', name: 'SNOMEDCT', branchPath: 'MAIN', latestVersion: '2026-01-01' },
        { shortName: 'SNOMEDCT-BE', name: 'Belgian Edition', branchPath: 'MAIN/SNOMEDCT-BE', latestVersion: null },
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
      .expectOne('/api/ecl/expansion?ecl=%3C%3C%20233678006&branch=MAIN&limit=20')
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
      conceptIds: [],
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
      'SNOMEDCT, version 2026-01-01',
      'Belgian Edition (SNOMEDCT-BE)',
    ]);
    select.value = 'SNOMEDCT-BE';
    select.dispatchEvent(new Event('change'));
    type(page, '#name', 'Belgian asthma');
    type(page, '#ecl', '<< 195967001');
    vi.advanceTimersByTime(500);
    http
      .expectOne('/api/ecl/expansion?ecl=%3C%3C%20195967001&branch=MAIN/SNOMEDCT-BE&limit=20')
      .flush({ items: [], total: 0, offset: 0, limit: 20 });

    submit(page);
    const request = http.expectOne({ method: 'POST', url: '/api/value-sets' });
    expect(request.request.body.codeSystem).toBe('SNOMEDCT-BE');
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
      .expectOne(`/api/concepts?term=${term}&branch=MAIN`)
      .flush({ items: results, total: results.length, offset: 0, limit: 25 });
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
    http.expectOne('/api/concepts/195967001/children?branch=MAIN').flush({ items: [CHILDHOOD, ALLERGIC], total: 2 });
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
      conceptIds: ['233678006', '389145006'],
    });
    request.flush({ ...MINE, id: 12, type: 'MANUAL' });
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
