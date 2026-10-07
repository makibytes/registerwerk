import { VaultRequest } from '../../../../core/models';

/** Forward pricing (T1-07) helpers of the vault-requests screen, kept pure so they can be unit-tested. */

const SECONDS_PER_DAY = 86_400;
const MIN_PERIOD_HOURS = 1;
const MAX_PERIOD_HOURS = 31 * 24;

/** `61200` -> `"17:00"` (UTC time of day of the daily cut-off). */
export function formatCutoffUtc(secondsOfDay: number): string {
  const h = Math.floor(secondsOfDay / 3600);
  const m = Math.floor((secondsOfDay % 3600) / 60);
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

/** `"17:00"` -> `61200`; null when it is not a valid HH:mm time of day. */
export function parseCutoffUtc(hhmm: string): number | null {
  const match = /^([01]\d|2[0-3]):([0-5]\d)$/.exec((hhmm ?? '').trim());
  return match ? Number(match[1]) * 3600 + Number(match[2]) * 60 : null;
}

/** Period in hours -> seconds; null outside the 1 hour .. 31 days the backend accepts (or not a whole second count). */
export function periodSecondsOf(hours: number): number | null {
  if (!Number.isFinite(hours) || hours < MIN_PERIOD_HOURS || hours > MAX_PERIOD_HOURS) return null;
  const seconds = hours * 3600;
  return Number.isInteger(seconds) ? seconds : null;
}

export function periodLabel(seconds: number): string {
  if (seconds === SECONDS_PER_DAY) return 'daily';
  if (seconds === 7 * SECONDS_PER_DAY) return 'weekly';
  const hours = seconds / 3600;
  return `every ${Number.isInteger(hours) ? hours : hours.toFixed(1)} h`;
}

/**
 * Fulfil is refused on-chain until a NAV was struck at or after the request's dealing point
 * (`NavNotStruckAfterDealingPoint`) — the screen holds the button back up front instead of letting the
 * operator run into the revert.
 */
export function canFulfil(req: VaultRequest, busy: boolean): boolean {
  return !req.complianceHold && !req.awaitingConfirmation && !req.awaitingNavStrike && !busy;
}

/** Why Fulfil is unavailable / what it does; the dealing point is shown in UTC. */
export function fulfilHint(req: VaultRequest): string {
  if (req.awaitingNavStrike) {
    const when = req.dealingPoint ? ` (${req.dealingPoint.replace('T', ' ').slice(0, 16)} UTC)` : '';
    return 'Waiting for the next NAV strike: the latest NAV was struck before this request\'s dealing point'
      + `${when}. Strike a NAV after it to fulfil — forward pricing means nobody settles at an already-known NAV.`;
  }
  return 'Fulfil';
}
