/**
 * Canonical color map for token standards. Consumed by issuance-list, investment-list,
 * and trading-desk components. Add new standards here; do not duplicate per-component.
 */
export const TOKEN_STANDARD_COLORS: Record<string, string> = {
  ERC20:          '#3B82F6',
  ERC721:         '#A855F7',
  ERC1155:        '#F59E0B',
  ERC3643:        '#14B8A6',
  CONF_ERC20:     '#FB923C',
  CONF_ERC3643:   '#EC4899',
  SPL:            '#84CC16',
  SPL_2022:       '#65A30D',
  STARKNET_ERC20: '#F97316',
  STELLAR_ASSET:  '#06B6D4',
  CANTON_TOKEN:   '#8B5CF6',
};

export function tokenStandardColor(standard: string): string {
  return TOKEN_STANDARD_COLORS[standard] ?? '#94A3B8';
}
