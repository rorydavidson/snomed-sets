import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Concept } from '../core/value-set-api';
import { ValueSetPage } from './value-set-page';

function concepts(count: number, startAt: number): Concept[] {
  return Array.from({ length: count }, (_, index) => ({
    conceptId: String(startAt + index),
    preferredTerm: `Concept ${startAt + index}`,
    fullySpecifiedName: `Concept ${startAt + index} (disorder)`,
  }));
}

describe('ValueSetPage', () => {
  let http: HttpTestingController;

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

  it('shows the definition and pages through the expansion', async () => {
    const fixture = TestBed.createComponent(ValueSetPage);
    const page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();

    http.expectOne('/api/value-sets/7').flush({
      id: 7,
      name: 'Asthma diagnoses',
      description: 'All asthma disorders.',
      ecl: '<< 195967001 |Asthma|',
      branch: 'MAIN',
      status: 'PUBLISHED',
      visibility: 'COMMUNITY',
      ownerName: 'Sample data',
      ownedByMe: false,
      updatedAt: '2026-11-16T12:00:00Z',
    });
    http
      .expectOne('/api/value-sets/7/expansion?offset=0&limit=50')
      .flush({ items: concepts(50, 1), total: 120, offset: 0, limit: 50 });
    await fixture.whenStable();

    expect(page.querySelector('pre')?.textContent).toContain('<< 195967001 |Asthma|');
    expect(page.textContent).toContain('Showing 1 to 50 of 120 concepts');

    const next = Array.from(page.querySelectorAll('button')).find((b) => b.textContent?.includes('Next'));
    next?.click();
    http
      .expectOne('/api/value-sets/7/expansion?offset=50&limit=50')
      .flush({ items: concepts(50, 51), total: 120, offset: 50, limit: 50 });
    await fixture.whenStable();

    expect(page.textContent).toContain('Showing 51 to 100 of 120 concepts');
  });

  it('keeps the definition visible when only the expansion fails', async () => {
    const fixture = TestBed.createComponent(ValueSetPage);
    const page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();

    http.expectOne('/api/value-sets/7').flush({
      id: 7,
      name: 'Asthma diagnoses',
      description: 'All asthma disorders.',
      ecl: '<< 195967001 |Asthma|',
      branch: 'MAIN',
      status: 'PUBLISHED',
      visibility: 'COMMUNITY',
      ownerName: 'Sample data',
      ownedByMe: false,
      updatedAt: '2026-11-16T12:00:00Z',
    });
    http
      .expectOne('/api/value-sets/7/expansion?offset=0&limit=50')
      .flush({ detail: 'Snowstorm could not be reached or did not return JSON.' }, { status: 502, statusText: 'Bad Gateway' });
    await fixture.whenStable();

    expect(page.querySelector('pre')?.textContent).toContain('<< 195967001 |Asthma|');
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Snowstorm could not be reached');
  });
});
