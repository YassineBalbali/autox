import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { trackKeyboardInset } from './keyboard-inset';

@Component({ template: '' })
class Screen {
  constructor() {
    trackKeyboardInset();
  }
}

/** A visual viewport that the test moves, as an on-screen keyboard does on iOS. */
class FakeViewport extends EventTarget {
  height = 760;
  offsetTop = 0;

  keyboard(covered: number, panned = 0): void {
    this.height = 760 - covered - panned;
    this.offsetTop = panned;
    this.dispatchEvent(new Event('resize'));
  }
}

/**
 * How much of the window the on-screen keyboard covers, for the conversation screen's
 * composer to sit above it.
 */
describe('trackKeyboardInset', () => {
  const root = document.documentElement;
  const value = (name: string) => root.style.getPropertyValue(name);

  afterEach(() => vi.unstubAllGlobals());

  function start(): { viewport: FakeViewport; destroy: () => void } {
    const viewport = new FakeViewport();
    vi.stubGlobal('innerHeight', 760);
    vi.stubGlobal('visualViewport', viewport);
    const fixture = TestBed.createComponent(Screen);
    return { viewport, destroy: () => fixture.destroy() };
  }

  it('is zero with no keyboard', () => {
    const { destroy } = start();

    expect(value('--keyboard')).toBe('0px');
    expect(value('--viewport-top')).toBe('0px');
    destroy();
  });

  it('follows the keyboard up and down', () => {
    const { viewport, destroy } = start();

    viewport.keyboard(300);
    expect(value('--keyboard')).toBe('300px');

    viewport.keyboard(0);
    expect(value('--keyboard')).toBe('0px');
    destroy();
  });

  it('counts the panning iOS does to bring the field into view', () => {
    const { viewport, destroy } = start();

    viewport.keyboard(300, 40);

    expect(value('--keyboard')).toBe('300px');
    expect(value('--viewport-top')).toBe('40px');
    destroy();
  });

  it('cleans up after itself, leaving the fallbacks to apply', () => {
    const { viewport, destroy } = start();
    viewport.keyboard(300);

    destroy();

    expect(value('--keyboard')).toBe('');
    expect(value('--viewport-top')).toBe('');
  });

  it('does nothing where there is no visual viewport to read', () => {
    vi.stubGlobal('visualViewport', null);
    const fixture = TestBed.createComponent(Screen);

    expect(value('--keyboard')).toBe('');
    fixture.destroy();
  });
});
