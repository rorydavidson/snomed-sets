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
    const link = page.querySelector<HTMLAnchorElement>('header a.button');
    expect(link?.textContent).toContain('Sign in');
    expect(link?.getAttribute('href')).toBe('/sign-in');
    expect(page.querySelector('router-outlet')).not.toBeNull();
  });

  it('shows the signed-in name and a sign-out button', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    http.expectOne('/api/me').flush({ name: 'Sample Person' });
    await fixture.whenStable();

    const header = (fixture.nativeElement as HTMLElement).querySelector('header');
    expect(header?.textContent).toContain('Sample Person');
    expect(header?.querySelector('button')?.textContent).toContain('Sign out');
  });

  it('reports a failure that is not a missing session', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(null, { status: 502, statusText: 'Bad Gateway' });
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')).not.toBeNull();
  });
});
