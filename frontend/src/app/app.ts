import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * The application shell.
 *
 * <p>Holds the skip link and the router outlet, and nothing else. Header, navigation and
 * footer arrive with the screens that need them — a shell that guesses at its own chrome
 * before any screen exists is a shell that gets rewritten.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {}
