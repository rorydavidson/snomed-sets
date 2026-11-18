import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ValueSet } from '../core/value-set-api';
import { Session } from '../core/session';
import { LibraryPage } from './library-page';

const ASTHMA: ValueSet = {
  id: 1,
  type: 'ECL',
  codeSystem: 'SNOMEDCT',
  name: 'Asthma diagnoses',
  description: 'All asthma disorders.',
  ecl: '<< 195967001 |Asthma|',
  branch: 'MAIN',
  codeSystemVersion: null,
  status: 'PUBLISHED',
  visibility: 'COMMUNITY',
  ownerName: 'Sample data',
  ownerIsOrganisation: false,
  ownerAvatarUrl: null,
  ownerContactable: false,
  ownedByMe: false,
  showOrganisation: false,
  updatedAt: '2026-11-16T12:00:00Z',
  latestVersion: null,
  unpublishedChanges: false,
  tags: [],
  deprecated: false,
  allVersionsDeprecated: false,
  deprecationReason: null,
};

describe('LibraryPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LibraryPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    localStorage.clear();
    TestBed.inject(Session).state.set({ status: 'signedIn', name: 'Me', avatarUrl: null });
  });

  it('lists value sets with a link to each', async () => {
    const fixture = TestBed.createComponent(LibraryPage);
    fixture.detectChanges();
    http.expectOne('/api/value-sets').flush([ASTHMA]);
    await fixture.whenStable();

    const link = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('a.set-name');
    expect(link?.textContent).toContain('Asthma diagnoses');
    expect(link?.getAttribute('href')).toBe('/value-sets/1');
    // Signed in, people go straight to their library without the introduction.
    expect((fixture.nativeElement as HTMLElement).querySelector('app-library-hero')).toBeNull();
  });

  it('shows how a value set is made as a tip until it is dismissed, and remembers that', async () => {
    const fixture = TestBed.createComponent(LibraryPage);
    const page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne('/api/value-sets').flush([ASTHMA]);
    await fixture.whenStable();

    const tip = page.querySelector('app-steps-tip section');
    expect(tip?.textContent).toContain('How a value set is made');
    expect(tip?.querySelectorAll('li').length).toBe(3);

    tip!.querySelector<HTMLButtonElement>('button[aria-label="Dismiss tip"]')!.click();
    await fixture.whenStable();
    expect(page.querySelector('app-steps-tip section')).toBeNull();

    const again = TestBed.createComponent(LibraryPage);
    again.detectChanges();
    http.expectOne('/api/value-sets').flush([ASTHMA]);
    await again.whenStable();
    expect((again.nativeElement as HTMLElement).querySelector('app-steps-tip section')).toBeNull();
  });

  function typeAndSearch(page: HTMLElement, text: string): void {
    const input = page.querySelector<HTMLInputElement>('#library-search')!;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    page.querySelector<HTMLFormElement>('form[role="search"]')!.dispatchEvent(new Event('submit'));
  }

  function chooseConcepts(page: HTMLElement): void {
    page.querySelector<HTMLInputElement>('input[name="search-mode"][value="concepts"]')!.click();
  }

  it('searches set names, descriptions and tags only when Search is pressed, and a tag can be clicked', async () => {
    const fixture = TestBed.createComponent(LibraryPage);
    const page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne('/api/value-sets').flush([
      { ...ASTHMA, tags: ['respiratory'] },
      { ...ASTHMA, id: 2, name: 'Fracture of femur', description: 'Injuries for the trauma audit', tags: ['trauma'] },
      { ...ASTHMA, id: 3, name: 'Childhood asthma', tags: ['paediatric', 'respiratory'] },
    ]);
    await fixture.whenStable();
    const names = () => Array.from(page.querySelectorAll('a.set-name')).map((a) => a.textContent?.trim());
    expect(names()).toHaveLength(3);

    // Typing alone does nothing.
    const input = page.querySelector<HTMLInputElement>('#library-search')!;
    input.value = 'ASTHMA child';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(names()).toHaveLength(3);

    typeAndSearch(page, 'ASTHMA child');
    await fixture.whenStable();
    expect(names()).toEqual(['Childhood asthma']);
    expect(page.textContent).toContain('1 of 3 value sets match "ASTHMA child".');

    Array.from(page.querySelectorAll<HTMLButtonElement>('button.tag')).find((b) => b.textContent?.trim() === 'respiratory')!.click();
    await fixture.whenStable();
    expect(names()).toEqual(['Asthma diagnoses', 'Childhood asthma']);
    expect(input.value).toBe('respiratory');

    typeAndSearch(page, 'audit');
    await fixture.whenStable();
    expect(names()).toEqual(['Fracture of femur']);

    typeAndSearch(page, 'nothing like this');
    await fixture.whenStable();
    expect(page.textContent).toContain('None of the 3 value sets match "nothing like this".');

    Array.from(page.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Clear')!.click();
    await fixture.whenStable();
    expect(names()).toHaveLength(3);
  });

  it('finds sets containing matching concepts and shows which concepts matched', async () => {
    const fixture = TestBed.createComponent(LibraryPage);
    const page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne('/api/value-sets').flush([ASTHMA, { ...ASTHMA, id: 2, name: 'Fracture of femur' }]);
    await fixture.whenStable();
    chooseConcepts(page);
    await fixture.whenStable();
    // Signed-in people are not told about the visitors' limit.
    expect(page.textContent).not.toContain('once every 3 minutes');

    typeAndSearch(page, 'asthma');
    http.expectOne('/api/concept-search?term=asthma').flush({
      hits: [
        {
          valueSetId: 1,
          matchCount: 5,
          examples: [
            { conceptId: '389145006', term: 'Allergic asthma' },
            { conceptId: '233678006', term: 'Childhood asthma' },
          ],
        },
      ],
      truncated: false,
      storedTermsOnly: false,
    });
    await fixture.whenStable();

    const names = Array.from(page.querySelectorAll('a.set-name')).map((a) => a.textContent?.trim());
    expect(names).toEqual(['Asthma diagnoses']);
    expect(page.querySelector('.matches')?.textContent).toContain('Contains Allergic asthma, Childhood asthma and 3 more');
    expect(page.textContent).toContain('1 of 2 value sets contain a concept matching "asthma".');
    expect(page.querySelector('.search-note')).toBeNull();
  });

  it('says when a concept search was cut short or could only use stored terms', async () => {
    const fixture = TestBed.createComponent(LibraryPage);
    const page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne('/api/value-sets').flush([ASTHMA]);
    await fixture.whenStable();
    chooseConcepts(page);
    typeAndSearch(page, 'disorder');
    http.expectOne('/api/concept-search?term=disorder').flush({ hits: [], truncated: true, storedTermsOnly: true });
    await fixture.whenStable();

    const notes = Array.from(page.querySelectorAll('.search-note')).map((note) => note.textContent);
    expect(notes[0]).toContain('only the first 10,000 in each release');
    expect(notes[1]).toContain('could not be reached');
  });

  it('asks for at least three characters before searching for concepts', async () => {
    const fixture = TestBed.createComponent(LibraryPage);
    const page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne('/api/value-sets').flush([ASTHMA]);
    await fixture.whenStable();
    chooseConcepts(page);
    typeAndSearch(page, 'as');
    await fixture.whenStable();

    expect(page.querySelector('[role="alert"]')?.textContent).toContain('at least 3 characters');
  });

  it('shows the wait the server asks for when a visitor has already searched from elsewhere', async () => {
    TestBed.inject(Session).state.set({ status: 'signedOut' });
    const fixture = TestBed.createComponent(LibraryPage);
    const page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne('/api/value-sets').flush([{ ...ASTHMA, ownerName: null }]);
    await fixture.whenStable();
    chooseConcepts(page);
    typeAndSearch(page, 'asthma');
    http
      .expectOne('/api/concept-search?term=asthma')
      .flush({ detail: 'Try again later.' }, { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '90' } });
    await fixture.whenStable();

    expect(page.querySelector('[role="timer"]')?.textContent).toMatch(/Next concept search in 1:(29|30)/);
    expect(page.querySelector('[role="alert"]')).toBeNull();
  });

  it('lets a visitor search for concepts once every three minutes, and says so', async () => {
    TestBed.inject(Session).state.set({ status: 'signedOut' });
    const fixture = TestBed.createComponent(LibraryPage);
    const page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne('/api/value-sets').flush([{ ...ASTHMA, ownerName: null }]);
    await fixture.whenStable();
    chooseConcepts(page);
    await fixture.whenStable();
    expect(page.textContent).toContain('Without signing in you can search for concepts once every 3 minutes');

    typeAndSearch(page, 'asthma');
    http.expectOne('/api/concept-search?term=asthma').flush({ hits: [], truncated: false, storedTermsOnly: false });
    await fixture.whenStable();

    const search = Array.from(page.querySelectorAll<HTMLButtonElement>('button[type="submit"]'))[0];
    expect(search.disabled).toBe(true);
    expect(page.querySelector('[role="timer"]')?.textContent).toMatch(/Next concept search in [23]:\d\d/);
    expect(Number(localStorage.getItem('snomed-sets.next-concept-search'))).toBeGreaterThan(Date.now());
    // Set searches stay open to everyone.
    page.querySelector<HTMLInputElement>('input[name="search-mode"][value="sets"]')!.click();
    await fixture.whenStable();
    expect(search.disabled).toBe(false);
  });

  it('lists deprecated value sets marked as such, and can hide them', async () => {
    const fixture = TestBed.createComponent(LibraryPage);
    const page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne('/api/value-sets').flush([
      ASTHMA,
      { ...ASTHMA, id: 2, name: 'Old asthma list', deprecated: true, deprecationReason: 'Replaced' },
      { ...ASTHMA, id: 3, name: 'Retired versions', allVersionsDeprecated: true },
    ]);
    await fixture.whenStable();
    const names = () => Array.from(page.querySelectorAll('a.set-name')).map((a) => a.textContent?.trim());
    expect(names()).toEqual(['Asthma diagnoses', 'Old asthma list', 'Retired versions']);
    expect(page.querySelectorAll('tr.row-deprecated .badge-warning').length).toBe(2);

    const toggle = page.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
    expect(toggle.parentElement?.textContent).toContain('Hide deprecated value sets (2)');
    toggle.click();
    await fixture.whenStable();
    expect(names()).toEqual(['Asthma diagnoses']);
    expect(page.textContent).toContain('1 value set');
  });

  it('shows a visitor who has not signed in the library without owners or a way to create', async () => {
    TestBed.inject(Session).state.set({ status: 'signedOut' });
    const fixture = TestBed.createComponent(LibraryPage);
    const page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne('/api/value-sets').flush([{ ...ASTHMA, ownerName: null }]);
    await fixture.whenStable();

    expect(page.querySelector('a.set-name')?.textContent).toContain('Asthma diagnoses');
    expect(page.textContent).not.toContain('New value set');
    expect(page.querySelector('th')?.parentElement?.textContent).not.toContain('Owner');
    const signIn = page.querySelector<HTMLAnchorElement>('app-library-hero a[href="/sign-in"]');
    expect(signIn?.textContent).toContain('with your SNOMED International account');
    expect(page.querySelector('h1')?.textContent).toContain('Shared lists of SNOMED CT concepts');
    expect(page.querySelector('a[href="#library"]')).not.toBeNull();
    expect(page.querySelector('#library h2')?.textContent).toContain('Published value sets');
    expect(page.querySelector('app-value-set-steps')).toBeNull();
  });

  it('shows the backend explanation and a retry when loading fails', async () => {
    const fixture = TestBed.createComponent(LibraryPage);
    fixture.detectChanges();
    http
      .expectOne('/api/value-sets')
      .flush({ detail: 'The database is unavailable.' }, { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();

    const alert = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('The database is unavailable.');

    alert?.querySelector('button')?.click();
    http.expectOne('/api/value-sets').flush([]);
  });

  it('shows the edition and release each set is built on', async () => {
    const fixture = TestBed.createComponent(LibraryPage);
    const page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne('/api/code-systems').flush([
      { shortName: 'SNOMEDCT', name: 'International Edition', branchPath: 'MAIN', latestVersion: '2026-07-01', latestVersionBranch: 'MAIN/2026-07-01' },
    ]);
    http.expectOne('/api/value-sets').flush([
      { ...ASTHMA, branch: 'MAIN/2026-01-01', codeSystemVersion: '2026-01-01' },
      { ...ASTHMA, id: 2, name: 'Belgian asthma', codeSystem: 'SNOMEDCT-BE' },
    ]);
    await fixture.whenStable();

    const headers = Array.from(page.querySelectorAll('th')).map((th) => th.textContent?.trim());
    expect(headers).toContain('Edition');
    expect(headers).toContain('Release');
    const rows = Array.from(page.querySelectorAll('tbody tr')).map((row) => row.textContent ?? '');
    expect(rows[0]).toContain('International Edition');
    expect(rows[0]).toContain('2026-01-01');
    // An edition Snowstorm did not name falls back to its short name; a set with no release says so.
    expect(rows[1]).toContain('SNOMEDCT-BE');
    expect(rows[1]).toContain('Live branch');
  });
});
