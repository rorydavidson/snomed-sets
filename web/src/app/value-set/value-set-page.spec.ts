import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Concept, UpgradeAvailability, UpgradePreview, ValueSet, ValueSetUpgrade, ValueSetVersion } from '../core/value-set-api';
import { ValueSetPage } from './value-set-page';

const SET: ValueSet = {
  id: 7,
  type: 'ECL',
  codeSystem: 'SNOMEDCT',
  name: 'Asthma diagnoses',
  description: 'All asthma disorders.',
  ecl: '<< 195967001 |Asthma|',
  branch: 'MAIN',
  codeSystemVersion: null,
  status: 'DRAFT',
  visibility: 'PRIVATE',
  ownerName: 'Me',
  ownedByMe: true,
  updatedAt: '2026-11-16T12:00:00Z',
  latestVersion: null,
  unpublishedChanges: false,
  tags: [],
  deprecated: false,
  allVersionsDeprecated: false,
  deprecationReason: null,
};

const VERSION_1: ValueSetVersion = {
  number: 1,
  ecl: '<< 195967001 |Asthma|',
  branch: 'MAIN',
  conceptCount: 121,
  note: 'First release',
  publishedAt: '2026-11-10T09:00:00Z',
  frozen: true,
  deprecated: false,
  deprecationReason: null,
};

const EMPTY_PAGE = { items: [], total: 0, offset: 0, limit: 50 };

const NO_UPGRADE: UpgradeAvailability = {
  codeSystem: 'SNOMEDCT',
  currentVersion: '2026-01-01',
  latestVersion: '2026-01-01',
  available: false,
};

function concepts(count: number, startAt: number): Concept[] {
  return Array.from({ length: count }, (_, index) => ({
    conceptId: String(startAt + index),
    preferredTerm: `Concept ${startAt + index}`,
    language: 'en',
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

  /**
   * Opens the page and answers the requests it makes for the value set, its versions, its
   * upgrade history and, for the owner, whether a newer release is available.
   */
  function open(
    set: ValueSet,
    versions: ValueSetVersion[] = [],
    upgrades: ValueSetUpgrade[] = [],
    availability: UpgradeAvailability = NO_UPGRADE,
  ): void {
    fixture = TestBed.createComponent(ValueSetPage);
    page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne('/api/code-systems').flush([
      { shortName: 'SNOMEDCT', name: 'International Edition', branchPath: 'MAIN', latestVersion: '2026-01-01', latestVersionBranch: 'MAIN/2026-01-01' },
    ]);
    http.expectOne('/api/value-sets/7').flush(set);
    http.expectOne('/api/value-sets/7/versions').flush(versions);
    http.expectOne('/api/value-sets/7/upgrades').flush(upgrades);
    if (set.ownedByMe) {
      http.expectOne('/api/value-sets/7/upgrade').flush(availability);
    }
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
    expect(page.querySelector('tbody .lang')?.textContent).toBe('en');

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

  it('offers deletion with a warning once every published version is deprecated', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const retired: ValueSet = { ...SET, status: 'PUBLISHED', visibility: 'COMMUNITY', latestVersion: 1, allVersionsDeprecated: true };
    open(retired, [{ ...VERSION_1, deprecated: true, deprecationReason: 'Wrong branch' }]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    button(page, 'Delete')?.click();
    await fixture.whenStable();
    expect(page.textContent).toContain('may already be in use or referred to by others');
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

  it('opens a concept as a map of its relationships and moves along them', async () => {
    open(SET);
    http
      .expectOne('/api/value-sets/7/expansion?offset=0&limit=50')
      .flush({ items: [{ conceptId: '233678006', preferredTerm: 'Childhood asthma', fullySpecifiedName: 'Childhood asthma (disorder)', language: 'en' }], total: 1, offset: 0, limit: 50 });
    await fixture.whenStable();

    button(page, 'Childhood asthma')?.click();
    await fixture.whenStable();
    http.expectOne('/api/concepts/233678006/children?branch=MAIN').flush({ items: [], total: 0 });
    http.expectOne('/api/concepts/233678006?branch=MAIN').flush({
      conceptId: '233678006',
      fullySpecifiedName: 'Childhood asthma (disorder)',
      preferredTerm: 'Childhood asthma',
      active: true,
      definitionStatus: 'PRIMITIVE',
      moduleId: '900000000000207008',
      effectiveTime: '20020131',
      descriptions: [{ term: 'Childhood asthma', type: 'SYNONYM', language: 'en', preferredIn: ['en-GB', 'en-US'] }],
      parents: [{ typeId: '116680003', typeTerm: 'Is a', targetId: '195967001', targetTerm: 'Asthma', group: 0 }],
      attributes: [
        { typeId: '246454002', typeTerm: 'Occurrence', targetId: '255398004', targetTerm: 'Childhood', group: 1 },
        { typeId: '363698007', typeTerm: 'Finding site', targetId: '89187006', targetTerm: 'Airway structure', group: 2 },
      ],
    });
    await fixture.whenStable();

    const dialog = page.querySelector('dialog')!;
    expect(dialog.querySelector('.cv-tag')?.textContent).toBe('disorder');
    expect(dialog.textContent).toContain('Synonym · preferred in en-GB, en-US');
    expect(Array.from(dialog.querySelectorAll('.cv-group-label')).map((label) => label.textContent)).toEqual(['Group 1', 'Group 2']);
    expect(dialog.textContent).toContain('Has no kinds of its own');
    expect(dialog.querySelector('.cv-expression')?.textContent).toBe(
      [
        '<<< 195967001 |Asthma| :',
        '    { 246454002 |Occurrence| = 255398004 |Childhood| },',
        '    { 363698007 |Finding site| = 89187006 |Airway structure| }',
      ].join('\n'),
    );

    // Follow the parent: the map moves onto Asthma and the trail remembers where we came from.
    Array.from(dialog.querySelectorAll<HTMLButtonElement>('.cv-above .cv-node'))[0].click();
    await fixture.whenStable();
    http.expectOne('/api/concepts/195967001/children?branch=MAIN').flush({
      items: Array.from({ length: 14 }, (_, i) => ({ conceptId: String(1000000 + i), preferredTerm: `Kind ${i}`, fullySpecifiedName: null })),
      total: 27,
    });
    http.expectOne('/api/concepts/195967001?branch=MAIN').flush({
      conceptId: '195967001',
      fullySpecifiedName: 'Asthma (disorder)',
      preferredTerm: 'Asthma',
      active: true,
      definitionStatus: 'FULLY_DEFINED',
      moduleId: '900000000000207008',
      effectiveTime: '20020131',
      descriptions: [],
      parents: [],
      attributes: [],
    });
    await fixture.whenStable();

    expect(dialog.querySelector('h2')?.textContent).toBe('Asthma');
    expect(dialog.querySelector('.cv-focus')?.classList.contains('cv-defined')).toBe(true);
    expect(dialog.querySelectorAll('.cv-below .cv-node')).toHaveLength(12);
    expect(dialog.textContent).toContain('27 kinds of this');
    expect(dialog.querySelector('.cv-step')?.textContent).toBe('Childhood asthma');

    dialog.querySelector<HTMLButtonElement>('.cv-step')!.click();
    await fixture.whenStable();
    http.expectOne('/api/concepts/233678006/children?branch=MAIN').flush({ items: [], total: 0 });
    http
      .expectOne('/api/concepts/233678006?branch=MAIN')
      .flush({ detail: 'Concept 233678006 was not found in this code system.' }, { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();
    expect(dialog.querySelector('[role="alert"]')?.textContent).toContain('was not found');
    expect(dialog.querySelector('.cv-step')).toBeNull();
  });

  it('offers a download only for a published set that is shared with the community', async () => {
    open({ ...SET, ownedByMe: false, visibility: 'COMMUNITY', status: 'PUBLISHED', latestVersion: 2 }, [VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    const links = Array.from(page.querySelectorAll<HTMLAnchorElement>('a[download]')).map((link) => link.getAttribute('href'));
    expect(links).toEqual(['/api/value-sets/7/download', '/api/value-sets/7/download?format=fhir']);
    expect(page.textContent).toContain('Download version 2 (TSV)');
    expect(page.textContent).toContain('Download version 2 (FHIR JSON)');
  });

  it('offers no download for a private or unpublished set', async () => {
    open({ ...SET, status: 'PUBLISHED', latestVersion: 1 }, [VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    expect(page.querySelector('a[download]')).toBeNull();
  });

  it('offers deprecation instead of deletion once published, and asks for a reason', async () => {
    const published: ValueSet = { ...SET, status: 'PUBLISHED', latestVersion: 1 };
    open(published, [VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();
    expect(button(page, 'Delete')).toBeUndefined();

    button(page, 'Deprecate value set')?.click();
    await fixture.whenStable();
    button(page, 'Deprecate this value set')?.click();
    await fixture.whenStable();
    http.expectNone({ method: 'PUT', url: '/api/value-sets/7/deprecation' });
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('A reason is needed');

    page.querySelector<HTMLInputElement>('#deprecation-reason')!.value = ' Replaced by set 12 ';
    button(page, 'Deprecate this value set')?.click();
    const request = http.expectOne({ method: 'PUT', url: '/api/value-sets/7/deprecation' });
    expect(request.request.body).toEqual({ reason: 'Replaced by set 12' });
    request.flush({ ...published, deprecated: true, deprecationReason: 'Replaced by set 12' });

    http.expectOne('/api/value-sets/7').flush({ ...published, deprecated: true, deprecationReason: 'Replaced by set 12' });
    http.expectOne('/api/value-sets/7/versions').flush([VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    expect(page.querySelector('.warning-panel')?.textContent).toContain('Replaced by set 12');
    expect(button(page, 'Publish')).toBeUndefined();
    expect(button(page, 'Restore value set')).toBeDefined();
  });

  it('deprecates and restores a single version from the history', async () => {
    const published: ValueSet = { ...SET, status: 'PUBLISHED', latestVersion: 1 };
    const deprecated: ValueSetVersion = { ...VERSION_1, deprecated: true, deprecationReason: 'Wrong branch' };
    open(published, [VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    button(page, 'Deprecate version 1')?.click();
    await fixture.whenStable();
    page.querySelector<HTMLInputElement>('#deprecation-reason')!.value = 'Wrong branch';
    button(page, 'Deprecate version 1')?.click();
    http.expectOne({ method: 'PUT', url: '/api/value-sets/7/versions/1/deprecation' }).flush(deprecated);
    http.expectOne('/api/value-sets/7').flush(published);
    http.expectOne('/api/value-sets/7/versions').flush([deprecated]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    expect(page.querySelector('.version')?.textContent).toContain('Wrong branch');
    expect(page.querySelector('.warning-panel')?.textContent).toContain('Every published version');

    button(page, 'Restore version 1')?.click();
    http.expectOne({ method: 'DELETE', url: '/api/value-sets/7/versions/1/deprecation' }).flush(VERSION_1);
    http.expectOne('/api/value-sets/7').flush(published);
    http.expectOne('/api/value-sets/7/versions').flush([VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();
    expect(page.querySelector('.warning-panel')).toBeNull();
  });

  it('discards unpublished changes only after confirming', async () => {
    const changed: ValueSet = { ...SET, status: 'PUBLISHED', latestVersion: 1, unpublishedChanges: true };
    open(changed, [VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    button(page, 'Discard changes')?.click();
    await fixture.whenStable();
    http.expectNone({ method: 'POST', url: '/api/value-sets/7/discard-changes' });
    button(page, 'Yes, discard')?.click();
    http.expectOne({ method: 'POST', url: '/api/value-sets/7/discard-changes' }).flush({ ...changed, unpublishedChanges: false });
    http.expectOne('/api/value-sets/7').flush({ ...changed, unpublishedChanges: false });
    http.expectOne('/api/value-sets/7/versions').flush([VERSION_1]);
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    expect(button(page, 'Discard changes')).toBeUndefined();
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

  describe('upgrading to a newer release', () => {
    const OLD: ValueSet = { ...SET, type: 'MANUAL', ecl: '', branch: 'MAIN/2025-07-01', codeSystemVersion: '2025-07-01' };
    const AVAILABLE: UpgradeAvailability = { ...NO_UPGRADE, currentVersion: '2025-07-01', available: true };
    const PREVIEW: UpgradePreview = {
      codeSystem: 'SNOMEDCT',
      type: 'MANUAL',
      fromVersion: '2025-07-01',
      toVersion: '2026-01-01',
      fromBranch: 'MAIN/2025-07-01',
      toBranch: 'MAIN/2026-01-01',
      conceptCountBefore: 2,
      added: [],
      removed: [],
      termChanges: [{ conceptId: '195967001', oldTerm: 'Asthma (old term)', newTerm: 'Asthma' }],
      inactivated: [
        {
          conceptId: '1234567',
          term: 'Retired asthma concept',
          reason: 'DUPLICATE',
          replacements: [
            { conceptId: '389145006', term: 'Allergic asthma', association: 'POSSIBLY_EQUIVALENT_TO', alreadyInSet: false },
            { conceptId: '195967001', term: 'Asthma', association: 'POSSIBLY_EQUIVALENT_TO', alreadyInSet: true },
            { conceptId: '233678006', term: 'Childhood asthma', association: 'SAME_AS', alreadyInSet: false },
          ],
          keptBefore: false,
        },
      ],
    };
    const DONE: ValueSetUpgrade = {
      id: 3,
      fromVersion: '2025-07-01',
      toVersion: '2026-01-01',
      fromBranch: 'MAIN/2025-07-01',
      toBranch: 'MAIN/2026-01-01',
      upgradedAt: '2026-11-17T10:00:00Z',
      publishedVersion: 1,
      changeCounts: { INACTIVATED: 1, REPLACEMENT_ADDED: 1, REPLACEMENT_DECLINED: 1, TERM_CHANGED: 1 },
    };

    async function openUpgradeDialog(): Promise<void> {
      open(OLD, [], [], AVAILABLE);
      http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
      await fixture.whenStable();
      expect(page.textContent).toContain('A newer release of SNOMEDCT is available');
      button(page, 'Upgrade to 2026-01-01')?.click();
      await fixture.whenStable();
      http.expectOne('/api/value-sets/7/upgrade/preview').flush(PREVIEW);
      await fixture.whenStable();
    }

    it('shows what would change, with every replacement accepted, and saves nothing yet', async () => {
      await openUpgradeDialog();
      const dialog = page.querySelector('dialog')!;

      expect(dialog.textContent).toContain('Upgrade to SNOMEDCT 2026-01-01');
      expect(dialog.textContent).toContain('1 of 2 concepts are inactive');
      expect(dialog.textContent).toContain('Duplicate');
      expect(dialog.textContent).toContain('already in the list');
      const boxes = dialog.querySelectorAll<HTMLInputElement>('td.candidate input[type="checkbox"]');
      expect(boxes).toHaveLength(2);
      expect(Array.from(boxes).every((box) => box.checked)).toBe(true);
      http.expectNone({ method: 'POST', url: '/api/value-sets/7/upgrade' });
    });

    it('sends only the accepted replacements, publishes, then offers the change report', async () => {
      await openUpgradeDialog();
      const dialog = page.querySelector('dialog')!;

      dialog.querySelectorAll<HTMLInputElement>('td.candidate input[type="checkbox"]')[0].click();
      dialog.querySelectorAll<HTMLInputElement>('input[type="radio"]')[1].click();
      await fixture.whenStable();
      const note = dialog.querySelector<HTMLInputElement>('#upgrade-note')!;
      note.value = 'Moved to January';
      note.dispatchEvent(new Event('input'));
      button(dialog, 'Upgrade')?.click();

      const request = http.expectOne({ method: 'POST', url: '/api/value-sets/7/upgrade' });
      expect(request.request.body).toEqual({
        targetVersion: '2026-01-01',
        acceptedReplacements: [{ inactiveConceptId: '1234567', replacementConceptId: '233678006' }],
        keptInactiveConceptIds: [],
        publish: true,
        note: 'Moved to January',
      });
      request.flush({ valueSet: { ...OLD, branch: 'MAIN/2026-01-01', codeSystemVersion: '2026-01-01' }, upgrade: DONE });
      http.expectOne('/api/value-sets/7').flush({ ...OLD, branch: 'MAIN/2026-01-01', codeSystemVersion: '2026-01-01' });
      http.expectOne('/api/value-sets/7/versions').flush([VERSION_1]);
      http.expectOne('/api/value-sets/7/upgrades').flush([DONE]);
      http.expectOne('/api/value-sets/7/upgrade').flush(NO_UPGRADE);
      http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
      await fixture.whenStable();

      expect(dialog.textContent).toContain('Published as version 1.');
      expect(dialog.querySelector('a[download]')?.getAttribute('href')).toBe('/api/value-sets/7/upgrades/3/report');
      expect(page.textContent).not.toContain('A newer release of SNOMEDCT is available');
    });

    it('keeps the preview open and explains why when the upgrade is refused', async () => {
      await openUpgradeDialog();
      button(page.querySelector('dialog')!, 'Upgrade')?.click();
      http
        .expectOne({ method: 'POST', url: '/api/value-sets/7/upgrade' })
        .flush({ detail: 'Release 2026-02-01 of SNOMEDCT came out after the preview.' }, { status: 409, statusText: 'Conflict' });
      await fixture.whenStable();

      const alert = page.querySelector('dialog [role="alert"]');
      expect(alert?.textContent).toContain('could not be saved');
      expect(alert?.textContent).toContain('came out after the preview');
      expect(page.querySelector('dialog')?.textContent).toContain('Retired asthma concept');
    });

    it('keeps a long list compact, with search, a filter and accept or decline for what is shown', async () => {
      const many: UpgradePreview = {
        ...PREVIEW,
        conceptCountBefore: 12,
        termChanges: [],
        inactivated: Array.from({ length: 10 }, (_, index) => ({
          conceptId: String(1000000 + index),
          term: index < 3 ? `Retired fracture ${index}` : `Retired asthma ${index}`,
          reason: 'OUTDATED',
          replacements:
            index === 9
              ? []
              : [{ conceptId: String(2000000 + index), term: `Current ${index}`, association: 'REPLACED_BY', alreadyInSet: false }],
          keptBefore: false,
        })),
      };
      open(OLD, [], [], AVAILABLE);
      http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
      await fixture.whenStable();
      button(page, 'Upgrade to 2026-01-01')?.click();
      await fixture.whenStable();
      http.expectOne('/api/value-sets/7/upgrade/preview').flush(many);
      await fixture.whenStable();
      const dialog = page.querySelector('dialog')!;

      expect(dialog.querySelector('.stats')?.textContent?.replace(/\s+/g, ' ')).toContain('9 replacements will be added');
      expect(dialog.querySelectorAll('tbody tr')).toHaveLength(10);

      const search = dialog.querySelector<HTMLInputElement>('#upgrade-search')!;
      search.value = 'fracture';
      search.dispatchEvent(new Event('input'));
      await fixture.whenStable();
      expect(dialog.querySelectorAll('tbody tr')).toHaveLength(3);
      button(dialog, 'Add no replacements shown')?.click();
      await fixture.whenStable();
      expect(dialog.querySelector('.stats')?.textContent?.replace(/\s+/g, ' ')).toContain('6 replacements will be added');

      search.value = '';
      search.dispatchEvent(new Event('input'));
      const filter = dialog.querySelector<HTMLSelectElement>('#upgrade-filter')!;
      filter.value = 'unreplaced';
      filter.dispatchEvent(new Event('change'));
      await fixture.whenStable();
      expect(dialog.querySelectorAll('tbody tr')).toHaveLength(1);
      expect(dialog.querySelector('tbody')?.textContent).toContain('No replacement suggested');

      button(dialog, 'Upgrade')?.click();
      const request = http.expectOne({ method: 'POST', url: '/api/value-sets/7/upgrade' });
      expect(request.request.body.acceptedReplacements).toHaveLength(6);
      expect(request.request.body.acceptedReplacements.map((r: { inactiveConceptId: string }) => r.inactiveConceptId)).not.toContain('1000000');
    });

    it('removes inactive concepts by default but keeps any the owner unticks, and remembers earlier keeps', async () => {
      const twoInactive: UpgradePreview = {
        ...PREVIEW,
        inactivated: [
          { ...PREVIEW.inactivated[0] },
          { conceptId: '7654321', term: 'Kept last time', reason: 'OUTDATED', replacements: [], keptBefore: true },
        ],
      };
      open(OLD, [], [], AVAILABLE);
      http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
      await fixture.whenStable();
      button(page, 'Upgrade to 2026-01-01')?.click();
      await fixture.whenStable();
      http.expectOne('/api/value-sets/7/upgrade/preview').flush(twoInactive);
      await fixture.whenStable();
      const dialog = page.querySelector('dialog')!;

      const remove = (term: string) =>
        dialog.querySelector<HTMLInputElement>(`input[aria-label="Remove ${term}"]`)!;
      expect(remove('Retired asthma concept').checked).toBe(true);
      expect(remove('Kept last time').checked).toBe(false);
      expect(dialog.textContent).toContain('kept, flagged inactive');

      remove('Retired asthma concept').click();
      await fixture.whenStable();
      expect(dialog.querySelector('.stats')?.textContent?.replace(/\s+/g, ' ')).toContain('0 will be removed');

      button(dialog, 'Remove all inactive')?.click();
      await fixture.whenStable();
      remove('Kept last time').click();
      button(dialog, 'Upgrade')?.click();
      const request = http.expectOne({ method: 'POST', url: '/api/value-sets/7/upgrade' });
      expect(request.request.body.keptInactiveConceptIds).toEqual(['7654321']);
    });

    it('flags a concept kept as inactive in the concept list', async () => {
      open(OLD);
      http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush({
        items: [
          { conceptId: '195967001', preferredTerm: 'Asthma', fullySpecifiedName: 'Asthma (disorder)', language: 'en', active: true },
          { conceptId: '1234567', preferredTerm: 'Retired asthma concept', fullySpecifiedName: 'Retired', language: 'en', active: false },
        ],
        total: 2,
        offset: 0,
        limit: 50,
      });
      await fixture.whenStable();

      const rows = Array.from(page.querySelectorAll('tbody tr'));
      expect(rows[0].querySelector('.badge-warning')).toBeNull();
      expect(rows[1].querySelector('.badge-warning')?.textContent).toBe('Inactive');
    });

    it('lists past upgrades with their change report for anyone who can see the set', async () => {
      open({ ...SET, ownedByMe: false, visibility: 'COMMUNITY' }, [VERSION_1], [{ ...DONE, publishedVersion: null }]);
      http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
      await fixture.whenStable();

      expect(page.textContent).toContain('2025-07-01 → 2026-01-01');
      expect(page.textContent).toContain('saved as a draft');
      expect(page.textContent).toContain('1 inactive and taken out, 1 replacement added, 1 replacement declined, 1 term changed');
      expect(page.querySelector('a[href="/api/value-sets/7/upgrades/3/report"]')).not.toBeNull();
      expect(button(page, 'Upgrade to')).toBeUndefined();
    });
  });

  it('names the code system and the release the set is built on', async () => {
    open({ ...SET, branch: 'MAIN/2025-07-01', codeSystemVersion: '2025-07-01' });
    http.expectOne('/api/value-sets/7/expansion?offset=0&limit=50').flush(EMPTY_PAGE);
    await fixture.whenStable();

    const facts = page.querySelector('.facts')!;
    expect(facts.textContent).toContain('International Edition');
    expect(facts.textContent).toContain('(SNOMEDCT)');
    expect(facts.textContent).toContain('2025-07-01');
  });
});
