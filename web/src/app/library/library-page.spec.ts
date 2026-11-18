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

  it('filters by name and by tag, and a tag can be clicked to filter on it', async () => {
    const fixture = TestBed.createComponent(LibraryPage);
    const page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne('/api/value-sets').flush([
      { ...ASTHMA, tags: ['respiratory'] },
      { ...ASTHMA, id: 2, name: 'Fracture of femur', tags: ['trauma'] },
      { ...ASTHMA, id: 3, name: 'Childhood asthma', tags: ['paediatric', 'respiratory'] },
    ]);
    await fixture.whenStable();
    const names = () => Array.from(page.querySelectorAll('a.set-name')).map((a) => a.textContent?.trim());
    const search = page.querySelector<HTMLInputElement>('#library-search')!;
    expect(names()).toHaveLength(3);

    search.value = 'ASTHMA child';
    search.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(names()).toEqual(['Childhood asthma']);
    expect(page.textContent).toContain('1 of 3 value sets');

    Array.from(page.querySelectorAll<HTMLButtonElement>('button.tag')).find((b) => b.textContent?.trim() === 'respiratory')!.click();
    await fixture.whenStable();
    expect(names()).toEqual(['Asthma diagnoses', 'Childhood asthma']);

    search.value = 'nothing like this';
    search.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(page.textContent).toContain('None of the 3 value sets match.');
    expect(page.querySelector<HTMLInputElement>('#concept-search-placeholder')?.disabled).toBe(true);
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
