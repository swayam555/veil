import { Component, input } from '@angular/core';

/** The Veil mark: a lens with a horizontal veil drawn across it. */
@Component({
  selector: 'veil-logo',
  template: `
    <span class="inline-flex items-center gap-2 select-none">
      <svg [attr.width]="size()" [attr.height]="size()" viewBox="0 0 32 32" aria-hidden="true">
        <rect x="1" y="1" width="30" height="30" rx="9" [attr.fill]="dark() ? '#ffffff' : '#0d1117'" />
        <circle cx="16" cy="16" r="8.5" fill="none" [attr.stroke]="dark() ? '#0d1117' : '#ffffff'" stroke-width="2.2" />
        <path d="M5 16h22" [attr.stroke]="'#4f46e5'" stroke-width="3.2" stroke-linecap="round" />
      </svg>
      @if (wordmark()) {
        <span class="font-semibold tracking-tight" [class.text-white]="dark()" [style.font-size.px]="size() * 0.62">Veil</span>
      }
    </span>`,
})
export class Logo {
  size = input(28);
  dark = input(false);
  wordmark = input(true);
}
