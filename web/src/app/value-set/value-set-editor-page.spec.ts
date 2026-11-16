import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { ValueSet } from '../core/value-set-api';
import { ValueSetEditorPage } from './value-set-editor-page';

const MINE: ValueSet = {
  id: 7,
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
      name: 'Childhood asthma',
      description: '',
      ecl: '<< 233678006',
      branch: 'MAIN',
      visibility: 'PRIVATE',
    });
    request.flush({ ...MINE, id: 12 });

    expect(navigate).toHaveBeenCalledWith(['/value-sets', 12]);
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

  it('refuses to edit a value set owned by someone else', () => {
    const fixture = create({ id: '7' });

    http.expectOne('/api/value-sets/7').flush({ ...MINE, ownedByMe: false });
    fixture.detectChanges();

    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Only the owner can edit this value set.');
    expect(page.querySelector('form')).toBeNull();
  });
});
