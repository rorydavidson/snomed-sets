import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { IdentifierLinkPage } from './identifier-link-page';

describe('IdentifierLinkPage', () => {
  let http: HttpTestingController;

  function open(params: Record<string, string>) {
    TestBed.configureTestingModule({
      imports: [IdentifierLinkPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap(params) } } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(IdentifierLinkPage);
    fixture.detectChanges();
    return { fixture, navigate };
  }

  it('goes on to the version of the set the identifier names', () => {
    const { navigate } = open({ sctid: '723264001', version: '2' });
    http.expectOne('/api/value-sets/sct/723264001').flush({ id: 7 });

    expect(navigate).toHaveBeenCalledWith(['/value-sets', 7, 'versions', '2'], { replaceUrl: true });
  });

  it('says so when no set it may show has the identifier', async () => {
    const { fixture, navigate } = open({ sctid: '723264001' });
    http.expectOne('/api/value-sets/sct/723264001').flush({ detail: 'Value set not found' }, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();

    expect(navigate).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent).toContain(
      'No value set could be found for 723264001',
    );
  });
});
