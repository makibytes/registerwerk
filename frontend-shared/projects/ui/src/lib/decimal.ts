import { Pipe, PipeTransform, LOCALE_ID, inject } from '@angular/core';
import { formatNumber } from '@angular/common';

/**
 * Precision-safe handling of the backend's `BigInteger` / `BigDecimal` (NUMERIC(96,18)) amounts. Both are
 * serialised as bare JSON numbers; token base units (18 decimals) routinely exceed `Number.MAX_SAFE_INTEGER`
 * (1000 tokens = 1e21), and a decimal with more than ~15 significant digits cannot survive `JSON.parse` either.
 * The helpers keep such values as decimal STRINGS from the wire to the screen. They are deliberately separate
 * from the lending services' integer-only parser: values below 16 significant digits stay plain numbers, so
 * existing arithmetic on small amounts is unchanged.
 */

/** A numeric literal (JSON grammar) with at least this many significant digits is not exactly representable. */
const UNSAFE_SIGNIFICANT_DIGITS = 16;

function significantDigits(literal: string): number {
  const mantissa = literal.replace(/^-/, '').split(/[eE]/)[0].replace('.', '');
  return mantissa.replace(/^0+/, '').length;
}

/**
 * `JSON.parse` that keeps every bare numeric literal with 16+ significant digits (integers AND decimals) as a
 * string. Scans the text so string contents are never touched; everything else parses exactly like `JSON.parse`.
 * Literals with an exponent are left alone (the backend never emits them for amounts).
 */
export function parseJsonPreservingBigNumbers(text: string): unknown {
  let out = '';
  let i = 0;
  const n = text.length;
  while (i < n) {
    const c = text[i];
    if (c === '"') {
      let j = i + 1;
      while (j < n && text[j] !== '"') j += text[j] === '\\' ? 2 : 1;
      out += text.slice(i, j + 1);
      i = j + 1;
    } else if (c === '-' || (c >= '0' && c <= '9')) {
      let j = i + 1;
      while (j < n && /[0-9.eE+-]/.test(text[j])) j++;
      const literal = text.slice(i, j);
      const bare = !/[eE]/.test(literal) && significantDigits(literal) >= UNSAFE_SIGNIFICANT_DIGITS;
      out += bare ? `"${literal}"` : literal;
      i = j;
    } else {
      out += c;
      i++;
    }
  }
  return JSON.parse(out);
}

const DECIMAL_STRING = /^-?\d+(\.\d+)?$/;

/** True for a plain decimal string such as `123456789012345678901.5` (no exponent, no grouping). */
export function isDecimalString(value: unknown): value is string {
  return typeof value === 'string' && DECIMAL_STRING.test(value);
}

/**
 * Formats a decimal string like Angular's `number` pipe (`minInt.minFrac-maxFrac`, en-US grouping) without ever
 * converting it to a `number`. Rounds half away from zero at `maxFrac`.
 */
export function formatDecimalString(value: string, minFrac = 0, maxFrac = 3, minInt = 1): string {
  const negative = value.startsWith('-');
  const [intRaw, fracRaw = ''] = value.replace(/^-/, '').split('.');
  let digits = BigInt(intRaw + fracRaw.slice(0, maxFrac).padEnd(maxFrac, '0'));
  if (fracRaw.length > maxFrac && fracRaw.charCodeAt(maxFrac) >= 53 /* '5' */) digits += 1n;
  const s = digits.toString().padStart(maxFrac + minInt, '0');
  const intPart = s.slice(0, s.length - maxFrac).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  let frac = s.slice(s.length - maxFrac);
  while (frac.length > minFrac && frac.endsWith('0')) frac = frac.slice(0, -1);
  const body = frac ? `${intPart}.${frac}` : intPart;
  return negative && /[1-9]/.test(s) ? `-${body}` : body;
}

/**
 * Drop-in for `| number:'1.0-2'` on amounts that can exceed 2^53: decimal strings are formatted exactly, safe
 * numbers go through Angular's own formatter (identical output), anything else renders as an empty string.
 */
@Pipe({ name: 'rwDecimal', standalone: true })
export class RwDecimalPipe implements PipeTransform {
  private readonly locale = inject(LOCALE_ID);

  transform(value: string | number | null | undefined, digitsInfo = '1.0-3'): string {
    if (value === null || value === undefined || value === '') return '';
    const m = /^(\d+)\.(\d+)-(\d+)$/.exec(digitsInfo);
    const [minInt, minFrac, maxFrac] = m ? [+m[1], +m[2], +m[3]] : [1, 0, 3];
    if (typeof value === 'string') {
      if (isDecimalString(value) && significantDigits(value) >= UNSAFE_SIGNIFICANT_DIGITS) {
        return formatDecimalString(value, minFrac, maxFrac, minInt);
      }
      const asNumber = Number(value);
      return Number.isFinite(asNumber) ? formatNumber(asNumber, this.locale, digitsInfo) : '';
    }
    return Number.isFinite(value) ? formatNumber(value, this.locale, digitsInfo) : '';
  }
}
