import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PrivacyPage } from './privacy-page';

describe('PrivacyPage', () => {
  let http: HttpTestingController;

  function open() {
    TestBed.configureTestingModule({
      imports: [PrivacyPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(PrivacyPage);
    fixture.detectChanges();
    return fixture;
  }

  it('names who runs the site and how to contact them, as configured', async () => {
    const fixture = open();
    http
      .expectOne('/api/site')
      .flush({ operator: 'Example Health Trust', privacyContact: 'privacy@example.org', mapsEnabled: true });
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.textContent).toContain('This site is run by Example Health Trust');
    expect(page.querySelector<HTMLAnchorElement>('a[href="mailto:privacy@example.org"]')?.textContent).toContain(
      'privacy@example.org',
    );
    // What is kept, and that messages are never stored.
    expect(page.querySelector('table')?.textContent).toContain('The message itself is never kept.');
    expect(page.textContent).toContain("SNOMED International's privacy policy");
    expect(page.querySelector('table')?.textContent).toContain('Your maps');
  });

  it('leaves maps out when this deployment has switched them off', async () => {
    const fixture = open();
    http.expectOne('/api/site').flush({ operator: null, privacyContact: null, mapsEnabled: false });
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.querySelector('table')?.textContent).toContain('Your value sets');
    expect(page.textContent).not.toMatch(/\bmaps?\b/i);
  });

  it('still reads properly when the deployment has not said who runs it', async () => {
    const fixture = open();
    http.expectOne('/api/site').flush(null, { status: 502, statusText: 'Bad Gateway' });
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;

    expect(page.textContent).toContain('The organisation that runs this site is responsible');
    expect(page.textContent).toContain('contact the organisation that runs it');
    expect(page.querySelector('a[href^="mailto:"]')).toBeNull();
    // Not knowing whether maps are on, it says what would be kept if they were.
    expect(page.querySelector('table')?.textContent).toContain('Your maps');
  });
});
