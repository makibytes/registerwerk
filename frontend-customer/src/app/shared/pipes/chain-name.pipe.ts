import { Pipe, PipeTransform } from '@angular/core';
import { Chain } from '../../core/models';

@Pipe({
  name: 'chainName',
  standalone: true,
})
export class ChainNamePipe implements PipeTransform {
  private readonly names: Record<Chain, string> = {
  ETHEREUM:  'Ethereum',
  POLYGON:   'Polygon',
  BASE:      'Base',
  FHENIX:    'Fhenix',
  INCO:      'Inco',
  SOLANA:    'Solana',
    ARBITRUM:  'Arbitrum One',
    AVALANCHE: 'Avalanche C-Chain',
    OPTIMISM:  'Optimism',
    STARKNET:  'Starknet',
    STELLAR:   'Stellar',
    CANTON:    'Canton',
  };

  transform(value: Chain | null | undefined): string {
    if (!value) return '—';
    return this.names[value] ?? value;
  }
}
