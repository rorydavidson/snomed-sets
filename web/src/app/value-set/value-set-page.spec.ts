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
};

const VERSION_1: ValueSetVersion = {
  number: 1,
  ecl: '<< 195967001 |Asthma|',
  branch: 'MAIN',
  conceptCount: 121,
  note: 'First release',
  publishedAt: '2026-11-10T09:00:00Z',
  frozen: true,
};

const EMPTY_PAGE = { items: [], total: 0, offset: 0, limit: 50 };

function concepts(count: number, startAt: number): Concept[] {
  return Array.from({ length: count }, (_, index) => ({
    conceptId: String(startAt + index),
    preferredTerm: `Concept ${startAt + index}`,
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
