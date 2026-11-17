import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Concept, ValueSet, ValueSetVersion } from '../core/value-set-api';
import { ValueSetPage } from './value-set-page';

const SET: ValueSet = {
  id: 7,
  type: 'ECL',
  codeSystem: 'SNOMEDCT',
  name: 'Asthma diagnoses',
  description: 'All asthma disorders.',
  ecl: '<< 195967001 |Asthma|',
  branch: 'MAIN',
  status: 'DRAFT',
  visibility: 'PRIVATE',
  ownerName: 'Me',
  ownedByMe: true,
  updatedAt: '2026-11-16T12:00:00Z',
  latestVersion: null,
  unpublishedChanges: false,
  tags: [],
  deprecated: false,
  allVersionsDeprecated: false,
  deprecationReason: null,
};

const VERSION_1: ValueSetVersion = {
  number: 1,
  ecl: '<< 195967001 |Asthma|',
  branch: 'MAIN',
  conceptCount: 121,
  note: 'First release',
  publishedAt: '2026-11-10T09:00:00Z',
  frozen: true,
  deprecated: false,
  deprecationReason: null,
};

const EMPTY_PAGE = { items: [], total: 0, offset: 0, limit: 50 };

function concepts(count: number, startAt: number): Concept[] {
  return Array.from({ length: count }, (_, index) => ({
    conceptId: String(startAt + index),
    preferredTerm: `Concept ${startAt + index}`,
    language: 'en',
    fullySpecifiedName: `Concept ${startAt + index} (disorder)`,
  }));
}

function button(page: HTMLElement, label: string): HTMLButtonElement | undefined {
  return Array.from(page.querySelectorAll('button')).find((b) => b.textContent?.includes(label));
}

describe('ValueSetPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ValueSetPage>;
  let page: HTMLElement;

  /** Opens the page and answers the two requests it makes for the value set and its versions. */
  function open(set: ValueSet, versions: ValueSetVersion[] = []): void {
    fixture = TestBed.createComponent(ValueSetPage);
    page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne('/api/value-sets/7').flush(set);
    http.expectOne('/api/value-sets/7/versions').flush(versions);
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ValueSetPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: '7' }) } } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('shows the definition and pages through the expansion', async () => {
    open(SET);
    http
      .expectOne('/api/value-sets/7/expansion?offset=0&limit=50')
      .flush({ items: concepts(50, 1), total: 120, offset: 0, limit: 50 });
    await fixture.whenStable();

    expect(page.querySelector('pre')?.textContent).toContain('<< 195967001 |Asthma|');
    expect(page.textContent).toContain('Showing 1 to 50 of 120 concepts');
    expect(page.querySelector('tbody .lang')?.textContent).toBe('en');

    button(page, 'Next')?.click();
    http
      .expectOne('/api/value-sets/7/expansion?offset=50&limit=50')
      .flush({ items: concepts(50, 51), total: 120, offset: 50, limit: 50 });
    await fixture.whenStable();

    expect(page.textContent).toContain('Showing 51 to 100 of 120 concepts');
  });

  it('keeps the definition visible when only the expansion fails', async () => {
    open(SET);
    http
      .expectOne('/api/value-sets/7/expansion?offset=0&limit=50')
      .flush({ detail: 'Snowstorm could not be reached or did not return JSON.' }, { status: 502, statusText: 'Bad Gateway' });
    await fixture.whenStable();

    expect(page.querySelector('pre')?.textContent).toContain('<< 195967001 |Asthma|');
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Snowstorm could not be reached');
  });

  it('lets the owner delete only after confirming', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    open(SET);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    expect(page.querySelector('a[href="/value-sets/7/edit"]')).not.toBeNull();
    button(page, 'Delete')?.click();
    await fixture.whenStable();
    http.expectNone({ method: 'DELETE', url: '/api/value-sets/7' });

    button(page, 'Yes, delete')?.click();
    http.expectOne({ method: 'DELETE', url: '/api/value-sets/7' }).flush(null, { status: 204, statusText: 'No Content' });

    expect(navigate).toHaveBeenCalledWith(['/']);
  });

  it('offers deletion with a warning once every published version is deprecated', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const retired: ValueSet = { ...SET, status: 'PUBLISHED', visibility: 'COMMUNITY', latestVersion: 1, allVersionsDeprecated: true };
    open(retired, [{ ...VERSION_1, deprecated: true, deprecationReason: 'Wrong branch' }]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    button(page, 'Delete')?.click();
    await fixture.whenStable();
    expect(page.textContent).toContain('may already be in use or referred to by others');
    http.expectNone({ method: 'DELETE', url: '/api/value-sets/7' });

    button(page, 'Yes, delete')?.click();
    http.expectOne({ method: 'DELETE', url: '/api/value-sets/7' }).flush(null, { status: 204, statusText: 'No Content' });
    expect(navigate).toHaveBeenCalledWith(['/']);
  });

  it('offers no owner actions on a value set owned by someone else', async () => {
    open({ ...SET, ownedByMe: false });
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    expect(page.querySelector('a[href="/value-sets/7/edit"]')).toBeNull();
    expect(button(page, 'Delete')).toBeUndefined();
    expect(button(page, 'Publish')).toBeUndefined();
  });

  it('publishes a draft as version 1 with a note, then shows it as published', async () => {
    open(SET);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();
    expect(page.textContent).toContain('This value set has not been published yet.');

    button(page, 'Publish')?.click();
    await fixture.whenStable();
    page.querySelector<HTMLInputElement>('#publish-note')!.value = ' First release ';
    button(page, 'Publish version 1')?.click();

    const request = http.expectOne({ method: 'POST', url: '/api/value-sets/7/versions' });
    expect(request.request.body).toEqual({ note: 'First release' });
    request.flush(VERSION_1);

    http.expectOne('/api/value-sets/7').flush({ ...SET, status: 'PUBLISHED', latestVersion: 1 });
    http.expectOne('/api/value-sets/7/versions').flush([VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    expect(page.textContent).toContain('Published · v1');
    expect(page.textContent).toContain('First release');
    expect(button(page, 'Publish')).toBeUndefined();
  });

  it('shows an older version when it is chosen from the history', async () => {
    const version2: ValueSetVersion = { ...VERSION_1, number: 2, ecl: '<< 233678006', note: '' };
    open({ ...SET, status: 'PUBLISHED', ecl: '<< 233678006', latestVersion: 2 }, [version2, VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();
    expect(page.querySelector('pre')?.textContent).toContain('<< 233678006');

    button(page, 'Version 1')?.click();
    http
      .expectOne('/api/value-sets/7/versions/1/expansion?offset=0&limit=50')
      .flush({ items: concepts(1, 1), total: 121, offset: 0, limit: 50 });
    await fixture.whenStable();

    expect(page.querySelector('pre')?.textContent).toContain('<< 195967001 |Asthma|');
    expect(page.textContent).toContain('Showing 1 to 1 of 121 concepts');
  });

  it('opens a concept as a map of its relationships and moves along them', async () => {
    open(SET);
    http
      .expectOne('/api/value-sets/7/expansion?offset=0&limit=50')
      .flush({ items: [{ conceptId: '233678006', preferredTerm: 'Childhood asthma', fullySpecifiedName: 'Childhood asthma (disorder)', language: 'en' }], total: 1, offset: 0, limit: 50 });
    await fixture.whenStable();

    button(page, 'Childhood asthma')?.click();
    await fixture.whenStable();
    http.expectOne('/api/concepts/233678006/children?branch=MAIN').flush({ items: [], total: 0 });
    http.expectOne('/api/concepts/233678006?branch=MAIN').flush({
      conceptId: '233678006',
      fullySpecifiedName: 'Childhood asthma (disorder)',
      preferredTerm: 'Childhood asthma',
      active: true,
      definitionStatus: 'PRIMITIVE',
      moduleId: '900000000000207008',
      effectiveTime: '20020131',
      descriptions: [{ term: 'Childhood asthma', type: 'SYNONYM', language: 'en', preferredIn: ['en-GB', 'en-US'] }],
      parents: [{ typeId: '116680003', typeTerm: 'Is a', targetId: '195967001', targetTerm: 'Asthma', group: 0 }],
      attributes: [
        { typeId: '246454002', typeTerm: 'Occurrence', targetId: '255398004', targetTerm: 'Childhood', group: 1 },
        { typeId: '363698007', typeTerm: 'Finding site', targetId: '89187006', targetTerm: 'Airway structure', group: 2 },
      ],
    });
    await fixture.whenStable();

    const dialog = page.querySelector('dialog')!;
    expect(dialog.querySelector('.cv-tag')?.textContent).toBe('disorder');
    expect(dialog.textContent).toContain('Synonym · preferred in en-GB, en-US');
    expect(Array.from(dialog.querySelectorAll('.cv-group-label')).map((label) => label.textContent)).toEqual(['Group 1', 'Group 2']);
    expect(dialog.textContent).toContain('Has no kinds of its own');
    expect(dialog.querySelector('.cv-expression')?.textContent).toBe(
      [
        '<<< 195967001 |Asthma| :',
        '    { 246454002 |Occurrence| = 255398004 |Childhood| },',
        '    { 363698007 |Finding site| = 89187006 |Airway structure| }',
      ].join('\n'),
    );

    // Follow the parent: the map moves onto Asthma and the trail remembers where we came from.
    Array.from(dialog.querySelectorAll<HTMLButtonElement>('.cv-above .cv-node'))[0].click();
    await fixture.whenStable();
    http.expectOne('/api/concepts/195967001/children?branch=MAIN').flush({
      items: Array.from({ length: 14 }, (_, i) => ({ conceptId: String(1000000 + i), preferredTerm: `Kind ${i}`, fullySpecifiedName: null })),
      total: 27,
    });
    http.expectOne('/api/concepts/195967001?branch=MAIN').flush({
      conceptId: '195967001',
      fullySpecifiedName: 'Asthma (disorder)',
      preferredTerm: 'Asthma',
      active: true,
      definitionStatus: 'FULLY_DEFINED',
      moduleId: '900000000000207008',
      effectiveTime: '20020131',
      descriptions: [],
      parents: [],
      attributes: [],
    });
    await fixture.whenStable();

    expect(dialog.querySelector('h2')?.textContent).toBe('Asthma');
    expect(dialog.querySelector('.cv-focus')?.classList.contains('cv-defined')).toBe(true);
    expect(dialog.querySelectorAll('.cv-below .cv-node')).toHaveLength(12);
    expect(dialog.textContent).toContain('27 kinds of this');
    expect(dialog.querySelector('.cv-step')?.textContent).toBe('Childhood asthma');

    dialog.querySelector<HTMLButtonElement>('.cv-step')!.click();
    await fixture.whenStable();
    http.expectOne('/api/concepts/233678006/children?branch=MAIN').flush({ items: [], total: 0 });
    http
      .expectOne('/api/concepts/233678006?branch=MAIN')
      .flush({ detail: 'Concept 233678006 was not found in this code system.' }, { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();
    expect(dialog.querySelector('[role="alert"]')?.textContent).toContain('was not found');
    expect(dialog.querySelector('.cv-step')).toBeNull();
  });

  it('offers a download only for a published set that is shared with the community', async () => {
    open({ ...SET, ownedByMe: false, visibility: 'COMMUNITY', status: 'PUBLISHED', latestVersion: 2 }, [VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    const link = page.querySelector<HTMLAnchorElement>('a[download]');
    expect(link?.getAttribute('href')).toBe('/api/value-sets/7/download');
    expect(link?.textContent).toContain('Download version 2 (TSV)');
  });

  it('offers no download for a private or unpublished set', async () => {
    open({ ...SET, status: 'PUBLISHED', latestVersion: 1 }, [VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    expect(page.querySelector('a[download]')).toBeNull();
  });

  it('offers deprecation instead of deletion once published, and asks for a reason', async () => {
    const published: ValueSet = { ...SET, status: 'PUBLISHED', latestVersion: 1 };
    open(published, [VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();
    expect(button(page, 'Delete')).toBeUndefined();

    button(page, 'Deprecate value set')?.click();
    await fixture.whenStable();
    button(page, 'Deprecate this value set')?.click();
    await fixture.whenStable();
    http.expectNone({ method: 'PUT', url: '/api/value-sets/7/deprecation' });
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('A reason is needed');

    page.querySelector<HTMLInputElement>('#deprecation-reason')!.value = ' Replaced by set 12 ';
    button(page, 'Deprecate this value set')?.click();
    const request = http.expectOne({ method: 'PUT', url: '/api/value-sets/7/deprecation' });
    expect(request.request.body).toEqual({ reason: 'Replaced by set 12' });
    request.flush({ ...published, deprecated: true, deprecationReason: 'Replaced by set 12' });

    http.expectOne('/api/value-sets/7').flush({ ...published, deprecated: true, deprecationReason: 'Replaced by set 12' });
    http.expectOne('/api/value-sets/7/versions').flush([VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    expect(page.querySelector('.warning-panel')?.textContent).toContain('Replaced by set 12');
    expect(button(page, 'Publish')).toBeUndefined();
    expect(button(page, 'Restore value set')).toBeDefined();
  });

  it('deprecates and restores a single version from the history', async () => {
    const published: ValueSet = { ...SET, status: 'PUBLISHED', latestVersion: 1 };
    const deprecated: ValueSetVersion = { ...VERSION_1, deprecated: true, deprecationReason: 'Wrong branch' };
    open(published, [VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    button(page, 'Deprecate version 1')?.click();
    await fixture.whenStable();
    page.querySelector<HTMLInputElement>('#deprecation-reason')!.value = 'Wrong branch';
    button(page, 'Deprecate version 1')?.click();
    http.expectOne({ method: 'PUT', url: '/api/value-sets/7/versions/1/deprecation' }).flush(deprecated);
    http.expectOne('/api/value-sets/7').flush(published);
    http.expectOne('/api/value-sets/7/versions').flush([deprecated]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    expect(page.querySelector('.version')?.textContent).toContain('Wrong branch');
    expect(page.querySelector('.warning-panel')?.textContent).toContain('Every published version');

    button(page, 'Restore version 1')?.click();
    http.expectOne({ method: 'DELETE', url: '/api/value-sets/7/versions/1/deprecation' }).flush(VERSION_1);
    http.expectOne('/api/value-sets/7').flush(published);
    http.expectOne('/api/value-sets/7/versions').flush([VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();
    expect(page.querySelector('.warning-panel')).toBeNull();
  });

  it('discards unpublished changes only after confirming', async () => {
    const changed: ValueSet = { ...SET, status: 'PUBLISHED', latestVersion: 1, unpublishedChanges: true };
    open(changed, [VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    button(page, 'Discard changes')?.click();
    await fixture.whenStable();
    http.expectNone({ method: 'POST', url: '/api/value-sets/7/discard-changes' });
    button(page, 'Yes, discard')?.click();
    http.expectOne({ method: 'POST', url: '/api/value-sets/7/discard-changes' }).flush({ ...changed, unpublishedChanges: false });
    http.expectOne('/api/value-sets/7').flush({ ...changed, unpublishedChanges: false });
    http.expectOne('/api/value-sets/7/versions').flush([VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    expect(button(page, 'Discard changes')).toBeUndefined();
  });

  it('explains why publishing was refused', async () => {
    open({ ...SET, status: 'PUBLISHED', latestVersion: 1, unpublishedChanges: true }, [VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();
    expect(page.textContent).toContain('Unpublished changes');

    button(page, 'Publish')?.click();
    await fixture.whenStable();
    button(page, 'Publish version 2')?.click();
    http
      .expectOne({ method: 'POST', url: '/api/value-sets/7/versions' })
      .flush({ detail: 'Snowstorm rejected the request. Check the ECL expression and branch.' }, { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();

    expect(page.querySelector('[role="alert"]')?.textContent).toContain('could not be published');
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Snowstorm rejected the request');
  });
});
