import { HostState, hashSecret } from '../../src/state.ts';
const [path, requestId] = process.argv.slice(2);
if (!path || !requestId) throw new Error('Fixture arguments required');
const state = new HostState(path);
state.claimRuntime();
const paired = state.consumePairing(state.createPairing({ readWorkspaceIds: ['alpha'], executeWorkspaceIds: ['alpha'] }).pairingToken, 'Synthetic crash fixture');
state.admitCommand(paired.deviceId, requestId, 'prompt', hashSecret('crash-fixture'));
process.send?.({ deviceId: paired.deviceId });
// Parent terminates this synthetic process to prove OS-released lock recovery.
setInterval(() => { state.getDevice(paired.deviceId); }, 10_000);
