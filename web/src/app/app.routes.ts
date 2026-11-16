import { Routes } from '@angular/router';
import { LibraryPage } from './library/library-page';
import { NotFoundPage } from './not-found-page';
import { ValueSetPage } from './value-set/value-set-page';

export const routes: Routes = [
  { path: '', component: LibraryPage, title: 'Library · SNOMED CT value sets' },
  { path: 'value-sets/:id', component: ValueSetPage, title: 'Value set · SNOMED CT value sets' },
  { path: '**', component: NotFoundPage, title: 'Not found · SNOMED CT value sets' },
];
