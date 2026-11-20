import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { CodeMap, MapRow, MapTarget, SourceRow } from '../core/map-api';
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
  localCodeCount: 0,
};

const ASTHMA: MapTarget = {
  code: '195967001',
  term: 'Asthma',
  fullySpecifiedName: 'Asthma (disorder)',
  relationship: 'EQUIVALENT',
  origin: 'EXACT_TERM',
};

function row(id: number, code: string, term: string, changes: Partial<MapRow> = {}): MapRow {
  return { id, code, term, fullySpecifiedName: '', status: 'UNMAPPED', note: null, termChecked: false, targets: [], ...changes };
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

  async function load(rows: MapRow[], map: CodeMap = MAP, localCodes: SourceRow[] = []): Promise<HTMLElement> {
    http.expectOne('/api/maps/7').flush(map);
    http.expectOne('/api/maps/7/rows').flush(rows);
    http.expectOne('/api/maps/7/local-codes').flush(localCodes);
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
    expect(page.textContent).toContain('1 code has suggestions waiting');
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
    expect(save.request.body).toEqual({ targets: [{ code: '195967001', relationship: 'EQUIVALENT' }], noMap: false, note: null });
    save.flush(row(1, 'D01', 'Asthma', { status: 'MAPPED', termChecked: true, targets: [ASTHMA] }));
    await fixture.whenStable();

    expect(page.querySelector('tr[data-row="1"]')?.textContent).toContain('Mapped');
  });

  it('accepts the ticked suggestions together, and says which were left to choose by hand', async () => {
    const cold: MapTarget = { ...ASTHMA, code: '82272006', term: 'Cold' };
    const chill: MapTarget = { ...ASTHMA, code: '84162001', term: 'Cold' };
    const suggested = { status: 'SUGGESTED' as const, termChecked: true };
    const page = await load([
      row(1, 'D01', 'Asthma', { ...suggested, targets: [ASTHMA] }),
      row(2, 'D02', 'Cold', { ...suggested, targets: [cold, chill] }),
      row(3, 'D03', 'Gout', { termChecked: true }),
    ]);

    // Only suggestions can be ticked.
    expect(page.querySelector('tr[data-row="3"] input[type="checkbox"]')).toBeNull();
    page.querySelector<HTMLInputElement>('thead input[type="checkbox"]')!.click();
    await fixture.whenStable();
    expect(page.textContent).toContain('2 ticked');
    expect(page.textContent).toContain('1 of them has several suggestions');
    // Ticking a row does not open it.
    expect(page.querySelector('tr.detail')).toBeNull();

    [...page.querySelectorAll<HTMLButtonElement>('.bulk button')].find((button) => button.textContent?.trim() === 'Accept')!.click();

    const accept = http.expectOne('/api/maps/7/accepted-suggestions');
    expect(accept.request.body).toEqual({ rowIds: [1, 2] });
    accept.flush([
      row(1, 'D01', 'Asthma', { status: 'MAPPED', termChecked: true, targets: [ASTHMA] }),
      row(2, 'D02', 'Cold', { ...suggested, targets: [cold, chill] }),
    ]);
    await fixture.whenStable();

    expect(page.querySelector('tr[data-row="1"]')?.textContent).toContain('Mapped');
    expect(page.textContent).toContain('Accepted 1 suggestion. 1 left to choose by hand, as it has several suggestions.');
    expect(page.textContent).not.toContain('ticked');
  });

  it('dismisses only the suggestions that were ticked', async () => {
    const suggested = { status: 'SUGGESTED' as const, termChecked: true, targets: [ASTHMA] };
    const page = await load([row(1, 'D01', 'Asthma', suggested), row(2, 'D02', 'Asthma attack', suggested)]);

    page.querySelector<HTMLInputElement>('tr[data-row="2"] input[type="checkbox"]')!.click();
    await fixture.whenStable();
    [...page.querySelectorAll<HTMLButtonElement>('.bulk button')].find((button) => button.textContent?.trim() === 'Dismiss')!.click();

    const dismiss = http.expectOne('/api/maps/7/dismissed-suggestions');
    expect(dismiss.request.body).toEqual({ rowIds: [2] });
    dismiss.flush([row(2, 'D02', 'Asthma attack', { termChecked: true })]);
    await fixture.whenStable();

    expect(page.querySelector('tr[data-row="1"]')?.textContent).toContain('Suggested');
    expect(page.querySelector('tr[data-row="2"]')?.textContent).toContain('Unmapped');
    expect(page.textContent).toContain('Dismissed the suggestions of 1 code.');
  });

  it('marks a row as having no map from the keyboard', async () => {
    const page = await load([row(1, 'D01', 'Asthma', { termChecked: true })]);

    page.querySelector<HTMLElement>('tr[data-row="1"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'n' }));

    const save = http.expectOne('/api/maps/7/rows/1');
    expect(save.request.body).toEqual({ targets: [], noMap: true, note: null });
    save.flush(row(1, 'D01', 'Asthma', { status: 'NO_MAP', termChecked: true }));
  });

  it('does not claim to have looked for a term too short to search', async () => {
    const page = await load([row(1, 'D08', 'MI', { termChecked: true })]);

    page.querySelector<HTMLElement>('tr[data-row="1"]')!.click();
    await fixture.whenStable();

    expect(page.querySelector('.panel')?.textContent).toContain('too short to look for automatically');
  });

  it('opens a row when clicked and closes it when clicked again', async () => {
    const page = await load([row(1, 'D01', 'Asthma', { termChecked: true }), row(2, 'D02', 'Cold', { termChecked: true })]);
    const first = page.querySelector<HTMLElement>('tr[data-row="1"]')!;

    first.click();
    await fixture.whenStable();
    expect(page.querySelector('tr.detail')).not.toBeNull();
    expect(first.getAttribute('aria-expanded')).toBe('true');

    first.click();
    await fixture.whenStable();
    expect(page.querySelector('tr.detail')).toBeNull();

    // Opening another row closes the first: only one is open at a time.
    first.click();
    page.querySelector<HTMLElement>('tr[data-row="2"]')!.click();
    await fixture.whenStable();
    expect(page.querySelectorAll('tr.detail')).toHaveLength(1);
    expect(page.querySelector('tr.detail')?.textContent).toContain('Cold');
  });

  it('offers local codes as targets in a map from SNOMED CT', async () => {
    const fromSnomed: CodeMap = { ...MAP, direction: 'FROM_SNOMED', localCodeCount: 2 };
    const page = await load(
      [row(1, '195967001', 'Asthma', { fullySpecifiedName: 'Asthma (disorder)', termChecked: true })],
      fromSnomed,
      [
        { code: 'L1', term: 'Bronchial asthma' },
        { code: 'L2', term: 'Head cold' },
      ],
    );

    expect(page.querySelector('thead')?.textContent).toContain('Local code');
    page.querySelector<HTMLElement>('tr[data-row="1"]')!.click();
    await fixture.whenStable();
    // The concept search is for maps to SNOMED CT; here the choice is among the uploaded codes.
    expect(page.querySelector('app-concept-picker')).toBeNull();
    const filter = page.querySelector<HTMLInputElement>('#local-filter')!;
    filter.value = 'cold';
    filter.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    const offered = [...page.querySelectorAll('.pick-list li')];
    expect(offered).toHaveLength(1);
    offered[0].querySelector('button')!.click();

    const save = http.expectOne('/api/maps/7/rows/1');
    expect(save.request.body).toEqual({ targets: [{ code: 'L2', relationship: 'EQUIVALENT' }], noMap: false, note: null });
    save.flush(
      row(1, '195967001', 'Asthma', {
        status: 'MAPPED',
        termChecked: true,
        targets: [{ code: 'L2', term: 'Head cold', fullySpecifiedName: '', relationship: 'EQUIVALENT', origin: 'MANUAL' }],
      }),
    );
    await fixture.whenStable();
    expect(page.querySelector('.targets select')?.textContent).toContain('Local code is broader');
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
