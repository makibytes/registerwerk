import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  effect,
  input,
  signal,
  viewChild,
} from '@angular/core';

/**
 * Renders a QR code onto a canvas.
 *
 * The `qrcode` library is pulled in with a dynamic import so it stays out of the initial bundle
 * — a QR code appears on exactly one page in each app. `angularx-qrcode` was rejected because it
 * carries an Angular peer dependency, and this repo has already been bitten once by a wrapper
 * library that lagged the Angular release train.
 *
 * Both apps use it for the same shape of problem — showing a URL or `otpauth://` URI that the
 * user needs on their phone rather than their desktop.
 */
@Component({
  selector: 'rw-qr-code',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [`
    :host {
      display: inline-block;
      line-height: 0;
    }

    canvas {
      display: block;
      border-radius: 8px;
      background: #FFFFFF;
    }

    .fallback {
      font-family: 'Manrope', sans-serif;
      font-size: 12px;
      line-height: 1.5;
      color: var(--rw-text-muted, #6B7280);
      word-break: break-all;
      max-width: 220px;
    }
  `],
  template: `
    <canvas #canvas [attr.aria-label]="alt()" role="img" [hidden]="failed()"></canvas>
    @if (failed()) {
      <!-- A QR code is a convenience, not the only route: the same URL is always a link on the
           page, so a rendering failure must not block the user. -->
      <p class="fallback">{{ value() }}</p>
    }
  `,
})
export class QrCodeComponent {
  /** The text to encode — a URL, or an `otpauth://` URI. */
  readonly value = input.required<string>();

  /** Edge length in CSS pixels. */
  readonly size = input(180);

  /** Accessible description, since a QR code is meaningless to a screen reader. */
  readonly alt = input('QR code');

  protected readonly failed = signal(false);

  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');

  constructor() {
    effect(() => {
      const text = this.value();
      const width = this.size();
      const element = this.canvas().nativeElement;
      void this.render(element, text, width);
    });
  }

  private async render(canvas: HTMLCanvasElement, text: string, width: number): Promise<void> {
    try {
      const qrcode = await import('qrcode');
      await qrcode.toCanvas(canvas, text, {
        width,
        margin: 1,
        errorCorrectionLevel: 'M',
        color: { dark: '#111827', light: '#FFFFFF' },
      });
      this.failed.set(false);
    } catch (err) {
      console.error('[rw-qr-code] Could not render QR code', err);
      this.failed.set(true);
    }
  }
}
