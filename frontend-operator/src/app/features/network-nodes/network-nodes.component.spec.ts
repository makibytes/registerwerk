import { beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NEVER } from 'rxjs';
import { ChainService, RPC_NODE_CHANGE } from '../../core/api/chain.service';
import { ChainHealth, RpcNode } from '../../core/models';
import { stepUpDialogSpy, TOKENS } from '../../shared/components/step-up/step-up-test-helper';
import { NetworkNodesComponent } from './network-nodes.component';

/** Every RPC-node change hands the approver the exact request (method, path, query, body) the service then sends. */
describe('NetworkNodesComponent - dual-control targets', () => {
  const service = {
    enableNode: vi.fn(), disableNode: vi.fn(), setExclusive: vi.fn(), deleteNode: vi.fn(),
    resetGenesisPin: vi.fn(), addNode: vi.fn(), updateNode: vi.fn(),
  };
  const chain = { id: 'c1', displayName: 'Sepolia' } as ChainHealth;
  const node = { id: 'n1', url: 'https://rpc.example', exclusive: false } as RpcNode;
  const nodeBody = { url: 'https://new.example', label: 'new' };
  const base = '/api/v1/admin/chains/c1/nodes';

  function create(otherResult?: unknown) {
    const spy = stepUpDialogSpy(otherResult);
    TestBed.configureTestingModule({
      imports: [NetworkNodesComponent],
      providers: [provideZonelessChangeDetection(), { provide: ChainService, useValue: service }],
    });
    TestBed.overrideProvider(MatSnackBar, { useValue: { open: vi.fn() } });
    TestBed.overrideProvider(MatDialog, { useValue: spy.dialog });
    return { component: TestBed.createComponent(NetworkNodesComponent).componentInstance, ...spy };
  }

  beforeEach(() => {
    vi.resetAllMocks();
    for (const m of Object.values(service)) m.mockReturnValue(NEVER);
    vi.spyOn(globalThis, 'confirm').mockReturnValue(true);
  });

  it('start and stop bind POST .../enable and .../disable with the empty body', () => {
    const { component, stepUps } = create();
    component.toggleEnabled(chain, node, true);
    component.toggleEnabled(chain, node, false);
    expect(stepUps[0]).toMatchObject({
      requireDualControl: true, action: RPC_NODE_CHANGE, target: `POST ${base}/n1/enable`, targetBody: {},
    });
    expect(stepUps[1]).toMatchObject({ action: RPC_NODE_CHANGE, target: `POST ${base}/n1/disable`, targetBody: {} });
    expect(service.enableNode).toHaveBeenCalledWith('c1', 'n1', TOKENS);
    expect(service.disableNode).toHaveBeenCalledWith('c1', 'n1', TOKENS);
  });

  it('pinning binds the query parameter the service sends', () => {
    const { component, stepUps } = create();
    component.toggleExclusive(chain, node);
    expect(stepUps[0]).toMatchObject({ target: `POST ${base}/n1/exclusive?value=true`, targetBody: {} });
    expect(service.setExclusive).toHaveBeenCalledWith('c1', 'n1', true, TOKENS);
  });

  it('removing a node binds DELETE and no body', () => {
    const { component, stepUps } = create();
    component.deleteNode(chain, node);
    expect(stepUps[0].target).toBe(`DELETE ${base}/n1`);
    expect(stepUps[0].targetBody).toBeUndefined();
    expect(service.deleteNode).toHaveBeenCalledWith('c1', 'n1', TOKENS);
  });

  it('resetting the genesis pin binds POST .../genesis-pin/reset with the empty body', () => {
    const { component, stepUps } = create();
    component.resetGenesisPin(chain);
    expect(stepUps[0]).toMatchObject({ target: `POST ${base}/genesis-pin/reset`, targetBody: {} });
    expect(service.resetGenesisPin).toHaveBeenCalledWith('c1', TOKENS);
  });

  it('adding a node binds POST .../nodes and the dialog request', () => {
    const { component, stepUps } = create(nodeBody);
    component.openAddNode(chain);
    expect(stepUps[0]).toMatchObject({ target: `POST ${base}`, targetBody: nodeBody });
    expect(service.addNode).toHaveBeenCalledWith('c1', nodeBody, TOKENS);
  });

  it('editing a node binds PUT .../nodes/{id} and the dialog request', () => {
    const { component, stepUps } = create(nodeBody);
    component.openEditNode(chain, node);
    expect(stepUps[0]).toMatchObject({ target: `PUT ${base}/n1`, targetBody: nodeBody });
    expect(service.updateNode).toHaveBeenCalledWith('c1', 'n1', nodeBody, TOKENS);
  });
});
