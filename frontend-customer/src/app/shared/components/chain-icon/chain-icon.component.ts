import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Chain } from '../../../core/models';

@Component({
  selector: 'app-chain-icon',
  standalone: true,
  imports: [CommonModule],
  template: `
    <span [class]="'chain-chip chain-' + chain.toLowerCase()">
      <span class="chain-dot"></span>
      {{ label }}
    </span>
  `,
  styles: [`
    .chain-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: currentColor;
      flex-shrink: 0;
    }
  `]
})
export class ChainIconComponent {
  @Input({ required: true }) chain!: Chain;

  get label(): string {
    const labels: Record<Chain, string> = {
      ETHEREUM:  'Ethereum',
      POLYGON:   'Polygon',
      BASE:      'Base',
      FHENIX:    'Fhenix',
      INCO:      'Inco',
      SOLANA:    'Solana',
      ARBITRUM:  'Arbitrum One',
      AVALANCHE: 'Avalanche',
      OPTIMISM:  'Optimism',
      STARKNET:  'Starknet',
      STELLAR:   'Stellar',
      CANTON:    'Canton',
    };
    return labels[this.chain] ?? this.chain;
  }
}
