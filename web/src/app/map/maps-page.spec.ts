import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MapsPage } from './maps-page';

describe('MapsPage', () => {
  it('lists the maps with a link to each and how far they have got', async () => {
    TestBed.configureTestingModule({
      imports: [MapsPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(MapsPage);
    fixture.detectChanges();

    http.expectOne('/api/code-systems').flush([]);
    http.expectOne('/api/maps').flush([
      {
        id: 7,
        name: 'Ward picklist',
        description: '',
        direction: 'TO_SNOMED',
        codeSystem: 'SNOMEDCT',
        branch: 'MAIN/2026-01-01',
        codeSystemVersion: '2026-01-01',
        localSystemName: 'Ward diagnoses',
        localSystemUri: null,
        localSystemVersion: null,
        updatedAt: '2026-11-20T12:00:00Z',
        counts: { total: 12, unmapped: 4, suggested: 2, mapped: 5, noMap: 1 },
      },
    ]);
    await fixture.whenStable();

    const page = fixture.nativeElement as HTMLElement;
    const link = page.querySelector<HTMLAnchorElement>('a.set-name');
    expect(link?.textContent).toContain('Ward picklist');
    expect(link?.getAttribute('href')).toBe('/maps/7');
    const cells = [...page.querySelectorAll('tbody td')].map((cell) => cell.textContent?.trim());
    expect(cells).toContain('12');
    // Mapped and no-map rows are both decided.
    expect(cells).toContain('6');
  });
});
