import { loadConfig } from './config.js';
import { createServer } from './server.js';
import { getFheInstance } from './fheInstance.js';

async function main(): Promise<void> {
  const config = loadConfig();

  // Fail fast at boot rather than on the first request: createInstance does real network I/O
  // (fetching the FHE public key/CRS params from the relayer), so a misconfigured/unreachable
  // relayer should crash-loop the container immediately, not silently 502 on first use.
  await getFheInstance(config);

  const app = createServer(config);
  app.listen(config.port, () => {
    // eslint-disable-next-line no-console
    console.log(
      `zama-relayer listening on :${config.port} (preset=${config.preset}, chainId=${config.chainId}, ` +
      `operator-decrypt=${config.operatorDecryptPrivateKey ? 'configured' : 'NOT configured'})`
    );
  });
}

main().catch((err) => {
  // eslint-disable-next-line no-console
  console.error('zama-relayer failed to start:', err);
  process.exit(1);
});
