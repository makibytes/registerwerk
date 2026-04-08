import { Pipe, PipeTransform } from '@angular/core';
import { Chain } from '../../core/models';

@Pipe({
  name: 'chainName',
  standalone: true,
})
export class ChainNamePipe implements PipeTransform {
  private readonly names: Record<Chain, string> = {
    ETHEREUM: 'Ethereum',
    POLYGON: 'Polygon',
    BASE: 'Base',
    SOLANA: 'Solana',
  };

  transform(value: Chain | null | undefined): string {
    if (!value) return '—';
    return this.names[value] ?? value;
  }
}
