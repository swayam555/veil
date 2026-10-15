import { Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { Logo } from '../ui/logo';

@Component({
  selector: 'veil-login',
  imports: [FormsModule, RouterLink, Logo],
  template: `
    <div class="min-h-screen grid lg:grid-cols-[1fr_520px]">
      <section class="hidden lg:flex flex-col justify-between bg-night text-white p-12 relative overflow-hidden">
        <a routerLink="/"><veil-logo [dark]="true" [size]="30" /></a>
        <div class="max-w-lg relative z-10">
          <p class="eyebrow !text-white/50 mb-4">Security console</p>
          <h1 class="text-4xl font-semibold tracking-tight leading-tight">Say yes to AI.<br/>Keep the sensitive parts at home.</h1>
          <p class="text-white/60 mt-4 leading-relaxed">Every prompt your people and your apps send to an AI model passes through Veil:
            personal data is redacted and restored, secrets are stopped, confidential work stays on your own models.</p>
        </div>
        <div class="grid grid-cols-3 gap-6 text-sm relative z-10">
          <div><div class="text-2xl font-semibold tabular">~0.4 ms</div><div class="text-white/50">pattern detection per prompt</div></div>
          <div><div class="text-2xl font-semibold tabular">20</div><div class="text-white/50">kinds of sensitive data detected</div></div>
          <div><div class="text-2xl font-semibold tabular">0</div><div class="text-white/50">sensitive values stored</div></div>
        </div>
        <div class="absolute -right-40 top-1/3 size-[520px] rounded-full border border-white/5"></div>
        <div class="absolute -right-24 top-1/3 mt-16 size-[390px] rounded-full border border-white/5"></div>
      </section>

      <section class="flex flex-col justify-center px-6 sm:px-12 py-12">
        <div class="lg:hidden mb-10"><a routerLink="/"><veil-logo [size]="30" /></a></div>
        <h2 class="text-2xl font-semibold tracking-tight">Sign in</h2>
        <p class="text-muted text-sm mt-1">to the Acme Fintech demo workspace.</p>
        @if (expired()) {
          <div class="mt-5 rounded-lg bg-warn-soft text-[#7a4d05] text-sm px-3 py-2">Your session expired. Please sign in again.</div>
        }
        <form class="mt-6 flex flex-col gap-4" (ngSubmit)="submit()">
          <div>
            <label class="label" for="email">Work email</label>
            <input id="email" class="input" type="email" name="email" [(ngModel)]="email" autocomplete="username" required />
          </div>
          <div>
            <label class="label" for="password">Password</label>
            <input id="password" class="input" type="password" name="password" [(ngModel)]="password" autocomplete="current-password" required />
          </div>
          @if (error()) { <div class="text-sm text-block" role="alert">{{ error() }}</div> }
          <button class="btn btn-primary h-10 mt-1" type="submit" [disabled]="busy()">{{ busy() ? 'Signing in…' : 'Sign in' }}</button>
        </form>
        <div class="mt-8">
          <div class="eyebrow mb-2">Demo accounts</div>
          <div class="grid sm:grid-cols-2 gap-2">
            <button class="card text-left p-3 hover:border-brand transition-colors" (click)="fill('admin@acme.test', 'veil-admin')">
              <div class="text-sm font-medium">Asha Rao</div><div class="text-xs text-muted">Security admin · can change policy</div>
            </button>
            <button class="card text-left p-3 hover:border-brand transition-colors" (click)="fill('auditor@acme.test', 'veil-auditor')">
              <div class="text-sm font-medium">Vikram Shah</div><div class="text-xs text-muted">Auditor · read-only</div>
            </button>
          </div>
        </div>
      </section>
    </div>`,
})
export class Login {
  private auth = inject(AuthService);
  private router = inject(Router);
  expired = input<string>();
  email = 'admin@acme.test';
  password = '';
  busy = signal(false);
  error = signal('');

  fill(e: string, p: string) {
    this.email = e;
    this.password = p;
    this.submit();
  }

  async submit() {
    this.busy.set(true);
    this.error.set('');
    try {
      await this.auth.login(this.email, this.password);
      this.router.navigate(['/console']);
    } catch (e: any) {
      this.error.set(e?.status === 429 ? 'Too many attempts. Wait 15 minutes and try again.' : e?.status === 401 ? 'Wrong email or password.' : 'Veil is not reachable. Is the stack running?');
    } finally {
      this.busy.set(false);
    }
  }
}
