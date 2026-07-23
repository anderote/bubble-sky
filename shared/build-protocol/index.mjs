import protocol from './index.cjs'

export const {
  SCHEMA_VERSION,
  normalizeJob,
  normalizeBuildState,
  normalizeRegionClaims,
  normalizeWorkerProgress,
  validateBuildState,
  validateBlueprint,
} = protocol

export default protocol
