import { Injectable, inject } from '@angular/core';
import { BehaviorSubject, Observable, map, retry, timer } from 'rxjs';
import { EndpointService } from '../api/endpoint.service';

/**
 * Caches address→name mappings and resolves them in batches via the backend.
 *
 * Components call resolve(address) and get an Observable<string|null>.
 * Pending addresses are collected for 60ms, then sent as one batch request.
 * A null result means "address is not in any addressbook".
 */
@Injectable({ providedIn: 'root' })
export class AddressResolutionService {
  private readonly endpointService = inject(EndpointService);

  // Cache: address → name (undefined = not yet fetched, null = unknown)
  private readonly cache = new Map<string, string | null>();
  private readonly cache$ = new BehaviorSubject<Map<string, string | null>>(this.cache);

  // Pending queue: addresses requested but not yet fetched
  private pending = new Set<string>();
  private batchTimer: ReturnType<typeof setTimeout> | null = null;

  resolve(address: string): Observable<string | null> {
    if (!this.cache.has(address)) {
      this.enqueue(address);
    }
    return this.cache$.pipe(
      map(c => c.has(address) ? (c.get(address) ?? null) : null),
    );
  }

  /** Call after creating/updating an endpoint to refresh the cache entry. */
  invalidate(address: string): void {
    this.cache.delete(address);
    this.enqueue(address);
  }

  private enqueue(address: string): void {
    this.pending.add(address);
    if (this.batchTimer) clearTimeout(this.batchTimer);
    this.batchTimer = setTimeout(() => this.flush(), 60);
  }

  private flush(): void {
    if (this.pending.size === 0) return;
    const batch = Array.from(this.pending);
    this.pending.clear();
    this.batchTimer = null;

    this.endpointService.resolveAddresses(batch).pipe(
      retry({ count: 2, delay: (_error, retryCount) => timer(retryCount * 500) }),
    ).subscribe({
      next: resp => {
        for (const addr of batch) {
          this.cache.set(addr, resp.resolutions[addr] ?? null);
        }
        this.cache$.next(this.cache);
      },
      error: () => {
        for (const addr of batch) {
          if (!this.cache.has(addr)) this.cache.set(addr, null);
        }
        this.cache$.next(this.cache);
      },
    });
  }
}
