import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./landing/landing').then(m => m.Landing), title: 'Veil — the AI data firewall' },
  { path: 'login', loadComponent: () => import('./pages/login').then(m => m.Login), title: 'Sign in · Veil' },
  { path: 'demo/chat', loadComponent: () => import('./pages/demo-chat').then(m => m.DemoChat), title: 'Demo chat · Veil' },
  {
    path: 'console',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/console/shell').then(m => m.Shell),
    children: [
      { path: '', loadComponent: () => import('./pages/console/overview').then(m => m.Overview), title: 'Overview · Veil' },
      { path: 'playground', loadComponent: () => import('./pages/console/playground').then(m => m.Playground), title: 'Playground · Veil' },
      { path: 'activity', loadComponent: () => import('./pages/console/activity').then(m => m.Activity), title: 'Activity · Veil' },
      { path: 'incidents', loadComponent: () => import('./pages/console/incidents').then(m => m.Incidents), title: 'Incidents · Veil' },
      { path: 'policies', loadComponent: () => import('./pages/console/policies').then(m => m.Policies), title: 'Policies · Veil' },
      { path: 'keys', loadComponent: () => import('./pages/console/keys').then(m => m.Keys), title: 'Keys & teams · Veil' },
      { path: 'providers', loadComponent: () => import('./pages/console/providers').then(m => m.Providers), title: 'Providers · Veil' },
      { path: 'shadow-ai', loadComponent: () => import('./pages/console/shadow-ai').then(m => m.ShadowAi), title: 'Shadow AI · Veil' },
      { path: 'dictionary', loadComponent: () => import('./pages/console/dictionary').then(m => m.Dictionary), title: 'Company terms · Veil' },
      { path: 'compliance', loadComponent: () => import('./pages/console/compliance').then(m => m.Compliance), title: 'Compliance · Veil' },
      { path: 'integrations', loadComponent: () => import('./pages/console/integrations').then(m => m.Integrations), title: 'Integrations · Veil' },
    ],
  },
  { path: '**', redirectTo: '' },
];
