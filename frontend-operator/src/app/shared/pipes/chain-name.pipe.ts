import { Pipe, PipeTransform } from '@angular/core';

const CHAIN_NAMES: Record<string, string> = {
  ETHEREUM: 'Ethereum',
  POLYGON: 'Polygon',
  BASE: 'Base',
  SOLANA: 'Solana',
  MAINNET: 'Mainnet',
  TESTNET: 'Testnet',
};

@Pipe({
  name: 'chainName',
  standalone: true,
})
export class ChainNamePipe implements PipeTransform {
  transform(value: string): string {
    return CHAIN_NAMES[value] ?? value;
  }
}
