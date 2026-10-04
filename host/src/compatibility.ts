/** Each entry requires declaration + compiled implementation inspection and an isolated canary.
 * Never replace this with a semver range or infer compatibility from method presence. */
export const COMPATIBLE_DSH_VERSIONS = ['0.2.0-rc.2', '0.2.1-alpha.1'] as const;
export type CompatibleDshVersion = typeof COMPATIBLE_DSH_VERSIONS[number];
export function isCompatibleDshVersion(value: string): value is CompatibleDshVersion {
  return (COMPATIBLE_DSH_VERSIONS as readonly string[]).includes(value);
}
