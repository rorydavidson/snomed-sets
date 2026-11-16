import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ValueSet } from '../core/value-set-api';
import { LibraryPage } from './library-page';

const ASTHMA: ValueSet = {
  id: 1,
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

    const link = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('a.card-title');
    expect(link?.textContent).toContain('Asthma diagnoses');
    expect(link?.getAttribute('href')).toBe('/value-sets/1');
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
