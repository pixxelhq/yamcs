import { Routes, UrlMatcher, UrlSegment } from '@angular/router';
import { attachContextGuardFn } from '../core/guards/AttachContextGuard';
import { authGuardChildFn, authGuardFn } from '../core/guards/AuthGuard';
import {
  StackFilePageDirtyGuard,
  stackFilePageDirtyGuardFn,
} from './stack-file-dirty-guard/stack-file-dirty.guard';
import { StackFileLogComponent } from './stack-file-log/stack-file-log.component';
import { StackFileSettingsComponent } from './stack-file-settings/stack-file-settings.component';
import { StackFileComponent } from './stack-file/stack-file.component';
import { StackFileService } from './stack-file/StackFileService';
import { StackFolderComponent } from './stack-folder/stack-folder.component';
import { StacksPageComponent } from './stacks-page/stacks-page.component';

const objectNameMatcher: UrlMatcher = (url) => {
  let consumed = url;

  // Stop consuming at /-/
  // (handled by Angular again)
  const idx = url.findIndex((segment) => segment.path === '-');
  if (idx !== -1) {
    consumed = url.slice(0, idx);
  }

  const objectName = consumed.map((segment) => segment.path).join('/');
  return {
    consumed,
    posParams: {
      objectName: new UrlSegment(objectName, {}),
    },
  };
};

export const ROUTES: Routes = [
  {
    path: '',
    canActivate: [authGuardFn, attachContextGuardFn],
    canActivateChild: [authGuardChildFn],
    runGuardsAndResolvers: 'always',
    children: [
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'browse',
      },
      {
        path: 'browse',
        component: StacksPageComponent,
        children: [
          {
            path: '**',
            component: StackFolderComponent,
          },
        ],
      },
      {
        path: 'files',
        children: [
          {
            matcher: objectNameMatcher,
            providers: [StackFilePageDirtyGuard, StackFileService],
            canActivate: [StackFileService],
            canDeactivate: [stackFilePageDirtyGuardFn],
            children: [
              {
                path: '',
                pathMatch: 'full',
                component: StackFileComponent,
              },
              {
                path: '-/log',
                component: StackFileLogComponent,
              },
              {
                path: '-/settings',
                component: StackFileSettingsComponent,
              },
            ],
          },
        ],
      },
    ],
  },
];
