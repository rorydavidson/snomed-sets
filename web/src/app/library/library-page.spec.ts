import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ValueSet } from '../core/value-set-api';
import { LibraryPage } from './library-page';

const ASTHMA: ValueSet = {
  id: 1,
  type: 'ECL',
  codeSystem: 'SNOMEDCT',
  name: 'Asthma diagnoses',
  description: 'All asthma disorders.',
  ecl: '<< 195967001 |Asthma|',
  branch: 'MAIN',
  status: 'PUBLISHED',
  visibility: 'COMMUNITY',
  ownerName: 'Sample data',
  ownedByMe: false,
  updatedAt: '2026-11-16T12:00:00Z',
  latestVersion: null,
  unpublishedChanges: false,
  tags: [],
};

describe('LibraryPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LibraryPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  it('lists value sets with a link to each', async () => {
    const fixture = TestBed.createComponent(LibraryPage);
    fixture.detectChanges();
    http.expectOne('/api/value-sets').flush([ASTHMA]);
    await fixture.whenStable();

    const link = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('a.set-name');
    expect(link?.textContent).toContain('Asthma diagnoses');
    expect(link?.getAttribute('href')).toBe('/value-sets/1');
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
});
