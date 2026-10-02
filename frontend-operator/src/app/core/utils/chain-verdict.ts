import { ChainVerificationResult } from '../models';

export type ChainVerdictKind = 'unknown' | 'valid' | 'broken' | 'pending-ack';

export interface ChainVerdict {
  kind: ChainVerdictKind;
  title: string;
  detail: string;
}

/**
 * Maps the backend's hash-chain verification view to what the card shows. UNKNOWN (no run recorded) is
 * neutral, never an alarm; a BROKEN effective status with no broken sequence number in the latest run
 * means the latest run was clean but an earlier break is not yet acknowledged.
 */
export function chainVerdict(r: ChainVerificationResult): ChainVerdict {
  const status = r.status ?? (r.valid ? 'VALID' : 'BROKEN');
  if (status === 'UNKNOWN') {
    return { kind: 'unknown', title: 'Not yet verified', detail: r.reason ?? 'No audit chain verification has been recorded yet.' };
  }
  if (status === 'VALID') {
    return { kind: 'valid', title: 'Audit hash chain intact', detail: `${r.rowsChecked} rows verified.` };
  }
  if (r.firstBrokenSequenceNo == null) {
    return {
      kind: 'pending-ack',
      title: 'Latest run clean, acknowledgement pending',
      detail: r.reason ?? 'An earlier break has not been acknowledged by two administrators yet; the alert stays active until it is.',
    };
  }
  return {
    kind: 'broken',
    title: 'Audit hash chain BROKEN',
    detail: `at sequence_no=${r.firstBrokenSequenceNo}${r.reason ? ' - ' + r.reason : ''}. ${r.rowsChecked} rows checked before failure.`,
  };
}

/** Only REGISTRY_ADMIN may acknowledge, and only while the effective status is BROKEN with an id to ack. */
export function canAcknowledgeChain(r: ChainVerificationResult | null, isRegistryAdmin: boolean): boolean {
  return isRegistryAdmin && !!r && r.status === 'BROKEN' && !!r.verificationId;
}
