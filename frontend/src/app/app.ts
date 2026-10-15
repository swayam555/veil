import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ToastHost } from './ui/toast';
import { ConfirmHost } from './ui/confirm';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ToastHost, ConfirmHost],
  template: `<router-outlet /><veil-toasts /><veil-confirm />`,
})
export class App {}
