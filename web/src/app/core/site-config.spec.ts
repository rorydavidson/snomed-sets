import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { mapsEnabledGuard } from './site-config';

@Component({ template: '<p>Maps page</p>' })
class FakeMapsPage {}

@Component({ template: '<p>Not found</p>' })
class FakeNotFound {}

describe('mapsEnabledGuard', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'maps', component: FakeMapsPage, canActivate: [mapsEnabledGuard] },
          { path: '**', component: FakeNotFound },
        ]),
        provideLocationMocks(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function openMaps(siteResponse: () => void) {
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/maps');
    // The guard asks for the site's settings once the navigation has started.
    await new Promise((resolve) => setTimeout(resolve));
    siteResponse();
    await navigation;
    return harness;
  }

  it('opens the map pages when maps are switched on', async () => {
    const harness = await openMaps(() =>
      http.expectOne('/api/site').flush({ operator: null, privacyContact: null, mapsEnabled: true }),
    );
    expect(harness.routeNativeElement?.textContent).toContain('Maps page');
  });

  it('shows not found, keeping the address, when maps are switched off', async () => {
    const harness = await openMaps(() =>
      http.expectOne('/api/site').flush({ operator: null, privacyContact: null, mapsEnabled: false }),
    );
    expect(harness.routeNativeElement?.textContent).toContain('Not found');
    // The address bar is left as it was, rather than changed to the not-found page's.
    expect(TestBed.inject(Location).path()).not.toBe('/not-found');
  });

  it('shows not found when it cannot tell whether maps are on', async () => {
    const harness = await openMaps(() =>
      http.expectOne('/api/site').flush(null, { status: 502, statusText: 'Bad Gateway' }),
    );
    expect(harness.routeNativeElement?.textContent).toContain('Not found');
  });
});
