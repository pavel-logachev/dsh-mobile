/** Inspection plus recorded isolated-canary or live-acceptance evidence is required per entry.
 * rc.2/alpha.1: historical full/registry canaries (f7c35df, docs/DSH_CANARY.md).
 * rc.5: repaired full isolated canary (32eb825); see docs/DSH_CANARY.md.
 * Never replace this with a semver range or infer compatibility from method presence. */
export const COMPATIBLE_DSH_VERSIONS = ['0.2.0-rc.2', '0.2.1-alpha.1', '0.2.1-rc.5'] as const;
export type CompatibleDshVersion = typeof COMPATIBLE_DSH_VERSIONS[number];
export function isCompatibleDshVersion(value: string): value is CompatibleDshVersion {
  return (COMPATIBLE_DSH_VERSIONS as readonly string[]).includes(value);
}
