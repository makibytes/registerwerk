/**
 * Canonical JSON for dual-control approvals - the browser twin of the backend's
 * `DualControlTarget.canonicalJson`. An approval is bound to the SHA-256 of this text, so both sides must
 * produce byte-identical output for the same document (the vectors in `canonical-json.spec.ts` are the same
 * as the backend's `DualControlTargetTest`).
 *
 * Canonical form: object keys sorted (by UTF-16 code unit, as Java's `String.compareTo`), no whitespace,
 * strings JSON-escaped (short escapes for \b \t \n \f \r, upper-case \u00XX for other control characters,
 * nothing else escaped), numbers as exact plain decimals without trailing zeros.
 *
 * Nothing ambiguous is ever canonicalised: a repeated object key or a number the browser cannot hold
 * exactly throws, so the approver can never be shown a value different from the one that gets bound.
 */

/** Beyond this many digits or this exponent a number is refused rather than expanded to plain form (backend: same cap). */
const MAX_NUMBER_MAGNITUDE = 1000;

const NUMBER_TOKEN = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/;

/** Exact plain-decimal form of a JSON number token: no exponent, no trailing zeros, `-0` is `0`. */
export function canonicalNumber(token: string): string {
  const m = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(token);
  if (!m) throw new Error(`Not a JSON number: ${token}`);
  const negative = m[1] === '-';
  const fraction = m[3] ?? '';
  const exponent = Number(m[4] ?? '0');
  let digits = (m[2] + fraction).replace(/^0+/, '');
  let scale = fraction.length - exponent; // value = digits * 10^-scale
  if (Math.abs(scale) > MAX_NUMBER_MAGNITUDE || digits.length > MAX_NUMBER_MAGNITUDE) {
    throw new Error('Number is too large to bind exactly');
  }
  if (digits === '') return '0';
  const trailing = /0*$/.exec(digits)![0].length;
  digits = digits.slice(0, digits.length - trailing);
  scale -= trailing;
  let plain: string;
  if (scale <= 0) {
    plain = digits + '0'.repeat(-scale);
  } else if (digits.length > scale) {
    plain = digits.slice(0, digits.length - scale) + '.' + digits.slice(digits.length - scale);
  } else {
    plain = '0.' + '0'.repeat(scale - digits.length) + digits;
  }
  return (negative ? '-' : '') + plain;
}

const SHORT_ESCAPES: Record<string, string> = { '"': '\\"', '\\': '\\\\', '\b': '\\b', '\t': '\\t', '\n': '\\n', '\f': '\\f', '\r': '\\r' };

function quote(text: string): string {
  let out = '"';
  for (const ch of text) {
    const short = SHORT_ESCAPES[ch];
    if (short) {
      out += short;
    } else if (ch < ' ') {
      out += '\\u' + ch.charCodeAt(0).toString(16).toUpperCase().padStart(4, '0');
    } else {
      out += ch;
    }
  }
  return out + '"';
}

function compareUtf16(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** Canonical text of a JSON-compatible value (objects, arrays, strings, finite numbers, booleans, null). */
export function canonicalJson(value: unknown): string {
  if (value === null || value === undefined) return 'null';
  switch (typeof value) {
    case 'string':
      return quote(value);
    case 'boolean':
      return value ? 'true' : 'false';
    case 'number':
      if (!Number.isFinite(value)) throw new Error('Not a JSON number');
      return canonicalNumber(String(value));
    case 'object': {
      if (Array.isArray(value)) return '[' + value.map(canonicalJson).join(',') + ']';
      const entries = Object.entries(value as Record<string, unknown>)
        .filter(([, v]) => v !== undefined)
        .sort(([a], [b]) => compareUtf16(a, b));
      return '{' + entries.map(([k, v]) => quote(k) + ':' + canonicalJson(v)).join(',') + '}';
    }
    default:
      throw new Error(`Cannot canonicalise a ${typeof value}`);
  }
}

/**
 * Strict JSON parser: one complete document, no repeated object keys, and every number must survive the
 * trip through a JavaScript `number` unchanged (otherwise it throws - amounts travel as strings).
 */
export function parseStrictJson(text: string): unknown {
  let pos = 0;

  const fail = (message: string): never => {
    throw new Error(`${message} (at position ${pos})`);
  };
  const skipWhitespace = () => {
    while (pos < text.length && ' \t\n\r'.includes(text[pos])) pos++;
  };

  function parseValue(): unknown {
    skipWhitespace();
    const ch = text[pos];
    if (ch === '{') return parseObject();
    if (ch === '[') return parseArray();
    if (ch === '"') return parseString();
    if (ch === '-' || (ch >= '0' && ch <= '9')) return parseNumber();
    for (const [literal, value] of [['true', true], ['false', false], ['null', null]] as const) {
      if (text.startsWith(literal, pos)) {
        pos += literal.length;
        return value;
      }
    }
    return fail('Unexpected token');
  }

  function parseObject(): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    const seen = new Set<string>();
    pos++; // {
    skipWhitespace();
    if (text[pos] === '}') {
      pos++;
      return out;
    }
    for (;;) {
      skipWhitespace();
      if (text[pos] !== '"') fail('Object key expected');
      const key = parseString();
      if (seen.has(key)) fail(`Repeated key "${key}"`);
      seen.add(key);
      skipWhitespace();
      if (text[pos] !== ':') fail('":" expected');
      pos++;
      Object.defineProperty(out, key, { value: parseValue(), enumerable: true, writable: true, configurable: true });
      skipWhitespace();
      if (text[pos] === ',') {
        pos++;
        continue;
      }
      if (text[pos] === '}') {
        pos++;
        return out;
      }
      fail('"," or "}" expected');
    }
  }

  function parseArray(): unknown[] {
    const out: unknown[] = [];
    pos++; // [
    skipWhitespace();
    if (text[pos] === ']') {
      pos++;
      return out;
    }
    for (;;) {
      out.push(parseValue());
      skipWhitespace();
      if (text[pos] === ',') {
        pos++;
        continue;
      }
      if (text[pos] === ']') {
        pos++;
        return out;
      }
      fail('"," or "]" expected');
    }
  }

  function parseString(): string {
    pos++; // opening quote
    let out = '';
    for (;;) {
      if (pos >= text.length) fail('Unterminated string');
      const ch = text[pos++];
      if (ch === '"') return out;
      if (ch < ' ') fail('Control character in string');
      if (ch !== '\\') {
        out += ch;
        continue;
      }
      const esc = text[pos++];
      const simple: Record<string, string> = { '"': '"', '\\': '\\', '/': '/', b: '\b', f: '\f', n: '\n', r: '\r', t: '\t' };
      if (esc in simple) {
        out += simple[esc];
      } else if (esc === 'u') {
        const hex = text.slice(pos, pos + 4);
        if (!/^[0-9a-fA-F]{4}$/.test(hex)) fail('Invalid unicode escape');
        out += String.fromCharCode(parseInt(hex, 16));
        pos += 4;
      } else {
        fail('Invalid escape');
      }
    }
  }

  function parseNumber(): number {
    const start = pos;
    while (pos < text.length && /[-+0-9.eE]/.test(text[pos])) pos++;
    const token = text.slice(start, pos);
    if (!NUMBER_TOKEN.test(token)) fail(`Invalid number "${token}"`);
    const value = Number(token);
    if (!Number.isFinite(value) || canonicalNumber(String(value)) !== canonicalNumber(token)) {
      throw new Error(`The number ${token} cannot be held exactly in the browser; send it as a string`);
    }
    return value;
  }

  const value = parseValue();
  skipWhitespace();
  if (pos < text.length) fail('Unexpected content after the document');
  return value;
}
