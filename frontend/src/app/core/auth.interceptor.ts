import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/** Adds the session token to console API calls and signs out when the gateway says it has expired. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.token();
  const authed = token && req.url.startsWith('/api/') && !req.url.startsWith('/api/auth/')
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;
  return next(authed).pipe(
    catchError((e: HttpErrorResponse) => {
      if (e.status === 401 && !req.url.startsWith('/api/auth/')) auth.logout(true);
      return throwError(() => e);
    }),
  );
};
