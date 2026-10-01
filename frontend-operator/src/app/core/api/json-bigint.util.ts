/**
 * Parses a JSON response while preserving full precision for large bare integer literals
 * (WAD-scaled on-chain values — health factors, rates, utilization — routinely exceed
 * `Number.MAX_SAFE_INTEGER`, e.g. a health factor of 1.5 serializes as `1500000000000000000`).
 * The backend's Java `BigInteger` DTO fields (see `lending/web/dto/*Response.java`) serialize as
 * bare JSON numbers, not strings, matching this codebase's existing `BigInteger` DTO convention
 * elsewhere — so parsing must guard against precision loss here rather than changing that
 * wire format globally.
 *
 * Quotes any bare (unquoted) integer literal of 16+ digits before handing the text to
 * `JSON.parse`, so it survives as a `string` instead of being silently rounded by the standard
 * JSON→number conversion. Only touches numeric literals that are NOT already inside a quoted
 * string (the regex only matches within likely value positions: after `:`, `,`, `[`, or at the
 * start, matching the whitespace/JSON-punctuation JSON.stringify itself always produces).
 */
export function parseJsonPreservingBigInts(text: string): unknown {
  const safeguarded = text.replace(
    /([:,[]\s*)(-?\d{16,})(\s*[,}\]])/g,
    (_match, before: string, digits: string, after: string) => `${before}"${digits}"${after}`,
  );
  return JSON.parse(safeguarded);
}
