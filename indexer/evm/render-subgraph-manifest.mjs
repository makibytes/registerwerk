import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const [network, suffix, outputPath] = process.argv.slice(2)

if (!network || !suffix || !outputPath) {
  console.error('Usage: render-subgraph-manifest.mjs <graph-network> <env-suffix> <output-manifest>')
  process.exit(2)
}
if (!/^[a-z0-9-]+$/.test(network) || !/^[A-Z0-9_]+$/.test(suffix)) {
  console.error('Network or environment suffix contains unsupported characters')
  process.exit(1)
}

const templatePath = path.join(scriptDir, 'subgraph', 'subgraph.yaml')
const template = fs.readFileSync(templatePath, 'utf8')
const zeroAddress = '0x0000000000000000000000000000000000000000'
const addressPattern = /^0x[0-9a-fA-F]{40}$/
const seenAddresses = new Map()

function requireAddress(component) {
  const addressName = `${component}_ADDRESS_${suffix}`
  const blockName = `${component}_START_BLOCK_${suffix}`
  const address = process.env[addressName] ?? ''
  const startBlock = process.env[blockName] ?? '0'
  validateInstance(addressName, address, blockName, startBlock)
  return [{ address, startBlock }]
}

function requireInstances(component) {
  const variable = `${component}_INSTANCES_${suffix}`
  const raw = process.env[variable]
  if (raw === undefined || raw.trim() === '') {
    throw new Error(`${variable} must be a comma-separated address@deploymentBlock list or exactly NONE`)
  }
  if (raw.trim() === 'NONE') return []

  return raw.split(',').map((entry, index) => {
    const parts = entry.trim().split('@')
    if (parts.length !== 2) {
      throw new Error(`${variable} entry ${index + 1} must use address@deploymentBlock`)
    }
    const [address, startBlock] = parts
    validateInstance(`${variable} entry ${index + 1}`, address, 'deploymentBlock', startBlock)
    return { address, startBlock }
  })
}

function validateInstance(addressLabel, address, blockLabel, startBlock) {
  if (!addressPattern.test(address) || address.toLowerCase() === zeroAddress) {
    throw new Error(`${addressLabel} must be a non-zero 20-byte EVM address`)
  }
  if (!/^[0-9]+$/.test(startBlock)) {
    throw new Error(`${blockLabel} must be a non-negative integer`)
  }
  const normalized = address.toLowerCase()
  const firstLabel = seenAddresses.get(normalized)
  if (firstLabel) throw new Error(`${addressLabel} duplicates ${firstLabel} (${address})`)
  seenAddresses.set(normalized, addressLabel)
}

function sourceName(block) {
  return block.match(/^    name: ([A-Za-z0-9_]+)$/m)?.[1]
}

function renderBlock(block, name, instance, index) {
  const renderedName = index === 0 ? name : `${name}_${index + 1}`
  let rendered = block.replace(/^    name: [A-Za-z0-9_]+$/m, `    name: ${renderedName}`)
  rendered = rendered.replace(
    /^      address: ".*"$/m,
    `      address: "${instance.address}"`,
  )
  rendered = rendered.replace(/^      startBlock: [0-9]+$/m, `      startBlock: ${instance.startBlock}`)
  return rendered
}

const configured = new Map([
  ['AssetTokenFactory', requireAddress('ASSET_TOKEN_FACTORY')],
  ['EwpgRepoMarketFactory', requireAddress('REPO_MARKET_FACTORY')],
  ['DvpSettlement', requireAddress('DVP_SETTLEMENT')],
  ['EwpgBondDesk', requireInstances('BOND_DESK')],
  ['StablecoinAmm', requireInstances('STABLECOIN_AMM')],
  ['EwpgRepoVault', requireInstances('REPO_VAULT')],
  ['EwpgConfidentialFactory', requireAddress('CONFIDENTIAL_FACTORY')],
])

const dataMarker = 'dataSources:\n'
const templatesMarker = '\ntemplates:\n'
const dataStart = template.indexOf(dataMarker)
const templatesStart = template.indexOf(templatesMarker)
if (dataStart < 0 || templatesStart < 0 || templatesStart <= dataStart) {
  throw new Error('subgraph.yaml does not contain the expected dataSources/templates sections')
}

const prefixEnd = dataStart + dataMarker.length
const dataBody = template.slice(prefixEnd, templatesStart)
const starts = [...dataBody.matchAll(/^  - kind: ethereum$/gm)].map(match => match.index)
if (starts.length === 0) throw new Error('subgraph.yaml contains no static data sources')

const preamble = dataBody.slice(0, starts[0])
const blocks = starts.map((start, index) => dataBody.slice(start, starts[index + 1] ?? dataBody.length))
const occurrences = new Map()
const renderedBlocks = []

for (const block of blocks) {
  const name = sourceName(block)
  if (!name || !configured.has(name)) {
    renderedBlocks.push(block)
    continue
  }
  occurrences.set(name, (occurrences.get(name) ?? 0) + 1)
  const instances = configured.get(name)
  if (instances.length === 0) {
    renderedBlocks.push(
      `  # NO CONFIGURED INSTANCES — OPERATOR ASSERTION; NOT CHAIN-DISCOVERED: ${name} on ${network}; ` +
        `${name === 'EwpgBondDesk' ? 'BOND_DESK' : name === 'StablecoinAmm' ? 'STABLECOIN_AMM' : 'REPO_VAULT'}_INSTANCES_${suffix}=NONE.\n`,
    )
    continue
  }
  instances.forEach((instance, index) => renderedBlocks.push(renderBlock(block, name, instance, index)))
}

for (const name of configured.keys()) {
  if (occurrences.get(name) !== 1) throw new Error(`Expected exactly one ${name} prototype in subgraph.yaml`)
}

let rendered = template.slice(0, prefixEnd) + preamble + renderedBlocks.join('') + template.slice(templatesStart)
rendered = rendered.replace(/^    network: .*$/gm, `    network: ${network}`)
if (rendered.includes(`address: "${zeroAddress}"`)) {
  throw new Error('Rendered manifest still contains an unconfigured static address')
}

fs.writeFileSync(outputPath, rendered)
