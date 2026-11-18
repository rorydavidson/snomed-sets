import { Routes } from '@angular/router';
import { LibraryPage } from './library/library-page';
import { signedInGuard } from './core/session';
import { NotFoundPage } from './not-found-page';
import { ValueSetEditorPage } from './value-set/value-set-editor-page';
import { ValueSetPage } from './value-set/value-set-page';

export const routes: Routes = [
  { path: '', component: LibraryPage, title: 'Library · SNOMED CT value sets' },
  { path: 'value-sets/new', component: ValueSetEditorPage, canActivate: [signedInGuard], title: 'New value set · SNOMED CT value sets' },
  { path: 'value-sets/:id', component: ValueSetPage, title: 'Value set · SNOMED CT value sets' },
  // A fixed link to one published version, for sharing.
  { path: 'value-sets/:id/versions/:version', component: ValueSetPage, title: 'Value set · SNOMED CT value sets' },
  { path: 'value-sets/:id/edit', component: ValueSetEditorPage, canActivate: [signedInGuard], title: 'Edit value set · SNOMED CT value sets' },
  // Loaded only when opened, as most visits never need it.
  {
    path: 'profile',
    loadComponent: () => import('./profile/profile-page').then((m) => m.ProfilePage),
    canActivate: [signedInGuard],
    title: 'Profile settings · SNOMED CT value sets',
  },
  { path: '**', component: NotFoundPage, title: 'Not found · SNOMED CT value sets' },
];
