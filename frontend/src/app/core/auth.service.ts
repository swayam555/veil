import { Injectable, computed, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

export interface User {
  id: string;
  name: string;
  email: string;
  role: 'ADMIN' | 'VIEWER';
  orgId: string;
  orgName: string;
}

const STORAGE_KEY = 'veil.session';

/** Holds the console session. The JWT is verified by the gateway on every call. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly session = signal<{ token: string; user: User } | null>(this.restore());
  readonly user = computed(() => this.session()?.user ?? null);
  readonly token = computed(() => this.session()?.token ?? null);
  readonly isAdmin = computed(() => this.session()?.user.role === 'ADMIN');
  readonly signedIn = computed(() => this.session() !== null);

  constructor(private http: HttpClient, private router: Router) {}

  async login(email: string, password: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<{ accessToken: string; user: User }>('/api/auth/login', { email, password }));
    const s = { token: res.accessToken, user: res.user };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
    this.session.set(s);
  }

  logout(expired = false): void {
    localStorage.removeItem(STORAGE_KEY);
    this.session.set(null);
    this.router.navigate(['/login'], expired ? { queryParams: { expired: 1 } } : {});
  }

  private restore(): { token: string; user: User } | null {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return null;
      const s = JSON.parse(raw);
      const payload = JSON.parse(atob(s.token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
      if (payload.exp && payload.exp * 1000 < Date.now()) return null;
      return s;
    } catch {
      return null;
    }
  }
}
