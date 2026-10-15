import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Logo } from '../ui/logo';

/**
 * A stand-in for an AI chat website, for trying the browser extension safely. Its composer uses the same markup
 * hooks as real sites (a textarea with id "prompt-textarea" and a send button), and nothing typed here leaves the page.
 */
@Component({
  selector: 'veil-demo-chat',
  imports: [FormsModule, RouterLink, Logo],
  template: `
    <div class="min-h-screen bg-[#212121] text-[#ececec] flex flex-col">
      <header class="h-14 px-5 flex items-center justify-between border-b border-white/10">
        <div class="font-semibold">DemoGPT <span class="text-white/40 font-normal text-sm">· a pretend AI chat site</span></div>
        <a routerLink="/" class="opacity-70 hover:opacity-100"><veil-logo [size]="22" [dark]="true" /></a>
      </header>
      <main class="flex-1 overflow-y-auto">
        <div class="max-w-3xl mx-auto px-4 py-8 flex flex-col gap-6">
          @if (messages().length === 0) {
            <div class="text-center mt-16">
              <h1 class="text-3xl font-semibold">What can I help with?</h1>
              <p class="text-white/50 mt-3 text-sm max-w-md mx-auto">With the Veil extension installed, try pasting:<br/>
                <span class="font-mono text-[12.5px] text-white/70">Customer Priya Sharma, Aadhaar 2345 6789 0124, wants a refund</span></p>
            </div>
          }
          @for (m of messages(); track $index) {
            <div [class]="m.role === 'user' ? 'self-end max-w-[80%] rounded-3xl bg-[#303030] px-5 py-3 whitespace-pre-wrap' : 'self-start max-w-[90%] text-white/80 whitespace-pre-wrap'"
                 [attr.data-message-author-role]="m.role">{{ m.text }}</div>
          }
        </div>
      </main>
      <footer class="px-4 pb-6">
        <form class="max-w-3xl mx-auto rounded-3xl bg-[#303030] p-3 flex items-end gap-2" (ngSubmit)="send()">
          <textarea id="prompt-textarea" name="prompt" rows="2" [(ngModel)]="draft" placeholder="Ask anything"
                    class="flex-1 bg-transparent resize-none outline-none px-2 py-1 text-[15px] placeholder:text-white/40"
                    (keydown.enter)="enter($any($event))"></textarea>
          <button type="submit" data-testid="send-button" aria-label="Send prompt" class="size-9 rounded-full bg-white text-black font-bold disabled:opacity-30" [disabled]="!draft.trim()">↑</button>
        </form>
        <p class="text-center text-[11px] text-white/35 mt-2">Nothing here is sent anywhere. This page exists to demonstrate the Veil browser extension.</p>
      </footer>
    </div>`,
})
export class DemoChat {
  messages = signal<{ role: 'user' | 'assistant'; text: string }[]>([]);
  draft = '';

  enter(e: KeyboardEvent) {
    if (e.shiftKey) return;
    e.preventDefault();
    this.send();
  }

  send() {
    const text = this.draft.trim();
    if (!text) return;
    this.messages.update(m => [...m, { role: 'user', text }, { role: 'assistant', text: 'This is a demo page, so there is no model behind it. On a real AI site, this is where the answer would appear.' }]);
    this.draft = '';
  }
}
