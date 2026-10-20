import { Injectable, inject } from '@angular/core';
import { FheClient } from '@registerwerk/ui';
import { WalletService } from '../wallet/wallet.service';

/**
 * Browser-side Zama Relayer integration (client-side encrypt and wallet-signed reveal) over this app's
 * {@link WalletService}. All logic lives in the shared {@link FheClient} in `@registerwerk/ui`.
 */
@Injectable({ providedIn: 'root' })
export class FheClientService extends FheClient {
  constructor() {
    super(inject(WalletService));
  }
}
