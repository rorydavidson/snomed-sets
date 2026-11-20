import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { CodeMap, MapRow, MapTarget } from '../core/map-api';
import { MapPage } from './map-page';

const MAP: CodeMap = {
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
  counts: { total: 2, unmapped: 2, suggested: 0, mapped: 0, noMap: 0 },
};

const ASTHMA: MapTarget = {
  code: '195967001',
  term: 'Asthma',
  fullySpecifiedName: 'Asthma (disorder)',
  relationship: 'EQUIVALENT',
  origin: 'EXACT_TERM',
};

function row(id: number, code: string, term: string, changes: Partial<MapRow> = {}): MapRow {
  return { id, code, term, status: 'UNMAPPED', note: null, termChecked: false, targets: [], ...changes };
}

describe('MapPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<MapPage>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MapPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: '7' }) } } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MapPage);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  async function load(rows: MapRow[]): Promise<HTMLElement> {
    http.expectOne('/api/maps/7').flush(MAP);
    http.expectOne('/api/maps/7/rows').flush(rows);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('matches the terms of new codes as soon as the map is opened', async () => {
    await load([row(1, 'D01', 'Asthma'), row(2, 'D02', 'Cold', { termChecked: true })]);

    const match = http.expectOne('/api/maps/7/term-matches');
    // The row already looked for is not sent again.
    expect(match.request.body).toEqual({ rowIds: [1] });
    match.flush([row(1, 'D01', 'Asthma', { status: 'SUGGESTED', termChecked: true, targets: [ASTHMA] })]);
    await fixture.whenStable();

    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('tr[data-row="1"]')?.textContent).toContain('Suggested');
    expect(page.querySelector('tr[data-row="1"]')?.textContent).toContain('Asthma');
    expect(page.textContent).toContain('Accept all 1');
  });

  it('says so and offers to carry on when matching fails', async () => {
    await load([row(1, 'D01', 'Asthma')]);

    http.expectOne('/api/maps/7/term-matches').flush({ detail: 'Snowstorm could not be reached.' }, { status: 502, statusText: 'Bad Gateway' });
    await fixture.whenStable();

    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('Matching stopped: Snowstorm could not be reached.');
    expect(page.textContent).toContain('Match remaining');
  });

  it('accepts a suggestion only when asked, and saves it straight away', async () => {
    const page = await load([row(1, 'D01', 'Asthma', { status: 'SUGGESTED', termChecked: true, targets: [ASTHMA] })]);

    page.querySelector<HTMLElement>('tr[data-row="1"]')!.click();
    await fixture.whenStable();
    const accept = [...page.querySelectorAll<HTMLButtonElement>('.panel button')].find((button) => button.textContent?.trim() === 'Accept');
    accept!.click();

    const save = http.expectOne('/api/maps/7/rows/1');
    expect(save.request.method).toBe('PUT');
    expect(save.request.body).toEqual({ targets: [{ conceptId: '195967001', relationship: 'EQUIVALENT' }], noMap: false, note: null });
    save.flush(row(1, 'D01', 'Asthma', { status: 'MAPPED', termChecked: true, targets: [ASTHMA] }));
    await fixture.whenStable();

    expect(page.querySelector('tr[data-row="1"]')?.textContent).toContain('Mapped');
  });

  it('marks a row as having no map from the keyboard', async () => {
    const page = await load([row(1, 'D01', 'Asthma', { termChecked: true })]);

    page.querySelector<HTMLElement>('tr[data-row="1"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'n' }));

    const save = http.expectOne('/api/maps/7/rows/1');
    expect(save.request.body).toEqual({ targets: [], noMap: true, note: null });
    save.flush(row(1, 'D01', 'Asthma', { status: 'NO_MAP', termChecked: true }));
  });

  it('filters the table by status', async () => {
    const page = await load([
      row(1, 'D01', 'Asthma', { status: 'MAPPED', termChecked: true, targets: [ASTHMA] }),
      row(2, 'D02', 'Cold', { termChecked: true }),
    ]);

    const filter = page.querySelector<HTMLSelectElement>('#status-filter')!;
    filter.value = 'UNMAPPED';
    filter.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(page.querySelector('tr[data-row="1"]')).toBeNull();
    expect(page.querySelector('tr[data-row="2"]')).not.toBeNull();
  });
});
