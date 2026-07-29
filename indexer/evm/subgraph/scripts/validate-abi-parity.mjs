import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { Kind, parse } from 'graphql'

const subgraphDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const repoRoot = path.resolve(subgraphDir, '../../..')
const manifest = fs.readFileSync(path.join(subgraphDir, 'subgraph.yaml'), 'utf8')
const schema = fs.readFileSync(path.join(subgraphDir, 'schema.graphql'), 'utf8')
const schemaDocument = parse(schema)

const artifactPaths = {
  AssetTokenFactory: 'contracts/out/AssetTokenFactory.sol/AssetTokenFactory.json',
  EwpgRepoMarketFactory: 'contracts/out/EwpgRepoMarketFactory.sol/EwpgRepoMarketFactory.json',
  DvpSettlement: 'contracts/out/DvpSettlement.sol/DvpSettlement.0.8.36.json',
  EwpgBondDesk: 'contracts/out/EwpgBondDesk.sol/EwpgBondDesk.json',
  StablecoinAmm: 'contracts/out/StablecoinAmm.sol/StablecoinAmm.json',
  EwpgConfidentialFactory: 'contracts/out/EwpgConfidentialFactory.sol/EwpgConfidentialFactory.json',
  EwpgERC20: 'contracts/out/EwpgERC20.sol/EwpgERC20.json',
  EwpgERC721: 'contracts/out/EwpgERC721.sol/EwpgERC721.json',
  EwpgERC1155: 'contracts/out/EwpgERC1155.sol/EwpgERC1155.json',
  EwpgERC3525: 'contracts/out/EwpgERC3525.sol/EwpgERC3525.json',
  EwpgERC3643: 'contracts/out/EwpgERC3643.sol/EwpgERC3643.json',
  EwpgERC4626: 'contracts/out/EwpgERC4626.sol/EwpgERC4626.json',
  EwpgERC7540: 'contracts/out/EwpgERC7540.sol/EwpgERC7540.json',
  EwpgRepoMarket: 'contracts/out/EwpgRepoMarket.sol/EwpgRepoMarket.json',
  EwpgRepoVault: 'contracts/out/EwpgRepoVault.sol/EwpgRepoVault.json',
  ConfidentialERC20: 'contracts/out/ConfidentialERC20.sol/ConfidentialERC20.json',
}

const requiredEconomicEvents = {
  EwpgERC3525: [
    'Transfer(indexed address,indexed address,indexed uint256)',
    'TransferValue(indexed uint256,indexed uint256,uint256)',
    'SlotChanged(indexed uint256,indexed uint256,indexed uint256)',
  ],
  EwpgERC7540: [
    'DepositRequested(indexed uint256,indexed address,indexed address,uint256)',
    'RedeemRequested(indexed uint256,indexed address,indexed address,uint256)',
    'DepositRequestFulfilled(indexed uint256,uint256,uint256,uint256)',
    'RedeemRequestFulfilled(indexed uint256,uint256,uint256,uint256)',
    'RequestCancelled(indexed uint256,indexed address)',
  ],
  EwpgRepoMarket: [
    'Supplied(indexed address,uint256,uint256)',
    'Withdrawn(indexed address,uint256,uint256)',
  ],
  EwpgRepoVault: [
    'MarketAdded(indexed address,uint256)',
    'MarketCapUpdated(indexed address,uint256)',
    'MarketRemoved(indexed address)',
    'Allocated(indexed address,uint256)',
    'Deallocated(indexed address,uint256)',
    'Deposit(indexed address,indexed address,uint256,uint256)',
    'Withdraw(indexed address,indexed address,indexed address,uint256,uint256)',
    'Transfer(indexed address,indexed address,uint256)',
  ],
}

function signature(event) {
  const inputs = event.inputs.map(input => `${input.indexed ? 'indexed ' : ''}${input.type}`).join(',')
  return `${event.name}(${inputs})`
}

function eventsFromAbi(document) {
  const abi = Array.isArray(document) ? document : document.abi
  return new Set(abi.filter(item => item.type === 'event').map(signature))
}

const manifestEvents = new Map()
let currentDataSource = null
for (const line of manifest.split(/\r?\n/)) {
  const source = line.match(/^    name: ([A-Za-z0-9_]+)$/)
  if (source) currentDataSource = source[1]
  const event = line.match(/^        - event: (.+)$/)
  if (event && currentDataSource) {
    if (!manifestEvents.has(currentDataSource)) manifestEvents.set(currentDataSource, [])
    manifestEvents.get(currentDataSource).push(event[1])
  }
}

const failures = []
for (const [name, relativeArtifact] of Object.entries(artifactPaths)) {
  const abiPath = path.join(subgraphDir, 'abis', `${name}.json`)
  const artifactPath = path.join(repoRoot, relativeArtifact)
  if (!fs.existsSync(abiPath)) {
    failures.push(`${name}: missing checked-in subgraph ABI ${abiPath}`)
    continue
  }
  if (!fs.existsSync(artifactPath)) {
    failures.push(`${name}: missing canonical Forge artifact ${artifactPath}; run 'forge build' in contracts/ first`)
    continue
  }

  const checkedInEvents = eventsFromAbi(JSON.parse(fs.readFileSync(abiPath, 'utf8')))
  const canonicalEvents = eventsFromAbi(JSON.parse(fs.readFileSync(artifactPath, 'utf8')))
  for (const event of checkedInEvents) {
    if (!canonicalEvents.has(event)) failures.push(`${name}: checked-in ABI event is stale: ${event}`)
  }
  for (const event of manifestEvents.get(name) ?? []) {
    if (!checkedInEvents.has(event)) failures.push(`${name}: manifest event missing from checked-in ABI: ${event}`)
    if (!canonicalEvents.has(event)) failures.push(`${name}: manifest event missing from canonical contract ABI: ${event}`)
  }
}

if (!(manifestEvents.get('AssetTokenFactory') ?? []).some(event => event.startsWith('VaultDeployed('))) {
  failures.push('AssetTokenFactory: VaultDeployed handler is required for ERC4626/ERC7540 provenance')
}

for (const [name, events] of Object.entries(requiredEconomicEvents)) {
  const configured = new Set(manifestEvents.get(name) ?? [])
  for (const event of events) {
    if (!configured.has(event)) failures.push(`${name}: required economic lifecycle handler is absent: ${event}`)
  }
}

for (const name of ['EwpgBondDesk', 'StablecoinAmm', 'EwpgRepoVault']) {
  if (!manifestEvents.has(name)) failures.push(`${name}: required multi-instance renderer prototype is absent`)
}

const projectionStatusEnum = schemaDocument.definitions.find(
  definition => definition.kind === Kind.ENUM_TYPE_DEFINITION && definition.name.value === 'ProjectionStatus',
)
if (!projectionStatusEnum) {
  failures.push('schema: ProjectionStatus enum is required')
} else {
  const actualValues = projectionStatusEnum.values.map(value => value.name.value).sort()
  const requiredValues = ['EVENT_DERIVED', 'INCOMPLETE']
  if (actualValues.join(',') !== requiredValues.join(',')) {
    failures.push(`schema: ProjectionStatus must contain only ${requiredValues.join(' and ')}`)
  }
}

const entityTypes = schemaDocument.definitions.filter(
  definition =>
    definition.kind === Kind.OBJECT_TYPE_DEFINITION &&
    definition.directives.some(directive => directive.name.value === 'entity'),
)
if (entityTypes.length === 0) failures.push('schema: no @entity object types found')
for (const entity of entityTypes) {
  const statusField = entity.fields.find(field => field.name.value === 'projectionStatus')
  const validStatusType =
    statusField?.type.kind === Kind.NON_NULL_TYPE &&
    statusField.type.type.kind === Kind.NAMED_TYPE &&
    statusField.type.type.name.value === 'ProjectionStatus'
  if (!validStatusType) {
    failures.push(`schema: @entity ${entity.name.value} requires projectionStatus: ProjectionStatus!`)
  }
}

if (failures.length > 0) {
  console.error(`ABI parity validation failed:\n- ${failures.join('\n- ')}`)
  process.exit(1)
}

console.log(
  `ABI parity and provisional schema validated for ${Object.keys(artifactPaths).length} contracts, ` +
    `${[...manifestEvents.values()].flat().length} handlers, and ${entityTypes.length} entities`,
)
