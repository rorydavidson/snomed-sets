import { Routes } from '@angular/router';
import { LibraryPage } from './library/library-page';
import { signedInGuard } from './core/session';
import { mapsEnabledGuard } from './core/site-config';
import { NotFoundPage } from './not-found-page';
import { IdentifierLinkPage } from './value-set/identifier-link-page';
import { ValueSetPage } from './value-set/value-set-page';

/** The editor is only for signed-in people making changes, so it is loaded when first opened. */
const editor = () => import('./value-set/value-set-editor-page').then((m) => m.ValueSetEditorPage);

export const routes: Routes = [
  { path: '', component: LibraryPage, title: 'Catalogue · Sets & Maps' },
  {
    path: 'value-sets/new',
    loadComponent: editor,
    canActivate: [signedInGuard],
    title: 'New value set · Sets & Maps',
  },
  // Share links that name a set by its SNOMED CT identifier rather than its number.
  { path: 'value-sets/sct/:sctid', component: IdentifierLinkPage, title: 'Value set · Sets & Maps' },
  { path: 'value-sets/sct/:sctid/versions/:version', component: IdentifierLinkPage, title: 'Value set · Sets & Maps' },
  { path: 'value-sets/:id', component: ValueSetPage, title: 'Value set · Sets & Maps' },
  // A fixed link to one published version, for sharing.
  { path: 'value-sets/:id/versions/:version', component: ValueSetPage, title: 'Value set · Sets & Maps' },
  {
    path: 'value-sets/:id/edit',
    loadComponent: editor,
    canActivate: [signedInGuard],
    title: 'Edit value set · Sets & Maps',
  },
  // Maps are private to their owner for now, so every map page needs a sign-in. A deployment can
  // switch maps off; that is checked first, so nobody is sent to sign in for a page that is not there.
  {
    path: 'maps',
    canActivate: [mapsEnabledGuard],
    children: [
      {
        path: '',
        loadComponent: () => import('./map/maps-page').then((m) => m.MapsPage),
        canActivate: [signedInGuard],
        title: 'Maps · Sets & Maps',
      },
      {
        path: 'new',
        loadComponent: () => import('./map/map-new-page').then((m) => m.MapNewPage),
        canActivate: [signedInGuard],
        title: 'New map · Sets & Maps',
      },
      {
        path: ':id',
        loadComponent: () => import('./map/map-page').then((m) => m.MapPage),
        canActivate: [signedInGuard],
        title: 'Map · Sets & Maps',
      },
    ],
  },
  // Loaded only when opened, as most visits never need it.
  {
    path: 'profile',
    loadComponent: () => import('./profile/profile-page').then((m) => m.ProfilePage),
    canActivate: [signedInGuard],
    title: 'Profile settings · Sets & Maps',
  },
  {
    path: 'privacy',
    loadComponent: () => import('./privacy/privacy-page').then((m) => m.PrivacyPage),
    title: 'Privacy · Sets & Maps',
  },
  { path: '**', component: NotFoundPage, title: 'Not found · Sets & Maps' },
];
