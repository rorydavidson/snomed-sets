import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';

describe('App', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  it('lets a visitor browse without signing in, offering sign-in in the header', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();

    const page = fixture.nativeElement as HTMLElement;
    const link = page.querySelector<HTMLAnchorElement>('header a.sign-in');
    expect(link?.textContent).toContain('Sign in');
    expect(link?.getAttribute('href')).toBe('/sign-in');
    expect(page.querySelector('router-outlet')).not.toBeNull();
  });

  it('shows the signed-in initials, with the name and sign-out in the account menu', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    http.expectOne('/api/me').flush({ name: 'Sample Person' });
    await fixture.whenStable();

    const header = (fixture.nativeElement as HTMLElement).querySelector('header')!;
    const account = header.querySelector<HTMLButtonElement>('[aria-controls="navbar-user"]')!;
    expect(account.textContent).toContain('SP');
    expect(account.getAttribute('aria-expanded')).toBe('false');
    expect(header.querySelector('a.sign-in')).toBeNull();

    account.click();
    await fixture.whenStable();

    expect(account.getAttribute('aria-expanded')).toBe('true');
    const menu = header.querySelector('#navbar-user')!;
    expect(menu.textContent).toContain('Sample Person');
    const signOut = menu.querySelector('button')!;
    expect(signOut.textContent).toContain('Sign out');
    expect(menu.querySelector('a[href="/profile"]')?.textContent).toContain('Profile settings');

    signOut.click();
    http.expectOne('/logout');
    await fixture.whenStable();
    expect(header.querySelector('#navbar-user')).toBeNull();
  });

  it('opens one menu at a time and closes it with Escape', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();

    const header = (fixture.nativeElement as HTMLElement).querySelector('header')!;
    header.querySelector<HTMLButtonElement>('[aria-controls="navbar-apps"]')!.click();
    await fixture.whenStable();
    const apps = header.querySelectorAll<HTMLAnchorElement>('#navbar-apps a');
    expect(apps.length).toBeGreaterThan(0);
    expect(header.querySelector('#navbar-apps')?.textContent).toContain('Tools from SNOMED International');
    apps.forEach((app) => {
      expect(app.target).toBe('_blank');
      expect(app.rel).toContain('noopener');
    });

    header.querySelector<HTMLButtonElement>('[aria-controls="navbar-links"]')!.click();
    await fixture.whenStable();
    expect(header.querySelector('#navbar-apps')).toBeNull();
    const links = header.querySelector('#navbar-links')?.textContent;
    expect(links).toContain('SNOMED International');
    expect(links).not.toContain('Privacy policy');

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await fixture.whenStable();
    expect(header.querySelector('#navbar-links')).toBeNull();
  });

  it('ends every page with the SNOMED CT notice and a link to the privacy page', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();

    const footer = (fixture.nativeElement as HTMLElement).querySelector('footer')!;
    expect(footer.textContent).toContain('used by permission of SNOMED International');
    expect(footer.textContent).toContain('registered trademarks of SNOMED International');
    expect(footer.querySelector('a[href="/privacy"]')?.textContent).toContain('Privacy');
    expect(footer.textContent).toContain('licence (CC BY-ND 4.0)');
    expect(footer.textContent).toContain('Concepts from national extensions are not part of the GPS.');
  });

  it('reports a failure that is not a missing session', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(null, { status: 502, statusText: 'Bad Gateway' });
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')).not.toBeNull();
  });
});
