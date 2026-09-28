/**
 * Minimal QR Code (ISO/IEC 18004, model 2) encoder.
 *
 * Byte mode, ECC level M, versions 1..10 — enough for one `dshctl add …` line
 * and small enough to keep the whole plugin dependency-free. The Host renders
 * the matrix as SVG so the settings page can show it with a plain <img>.
 */

// ECC level M block layout per version: `ec` is the error-correction codeword
// count per block, `blocks` lists [group count, data codewords per block].
const ECC_M = [
  null,
  { ec: 10, blocks: [[1, 16]] },
  { ec: 16, blocks: [[1, 28]] },
  { ec: 26, blocks: [[1, 44]] },
  { ec: 18, blocks: [[2, 32]] },
  { ec: 24, blocks: [[2, 43]] },
  { ec: 16, blocks: [[4, 27]] },
  { ec: 18, blocks: [[4, 31]] },
  { ec: 22, blocks: [[2, 38], [2, 39]] },
  { ec: 22, blocks: [[3, 36], [2, 37]] },
  { ec: 26, blocks: [[4, 43], [1, 44]] },
]

// Centre coordinates of the alignment patterns, per version.
const ALIGNMENT = [
  null,
  [],
  [6, 18],
  [6, 22],
  [6, 26],
  [6, 30],
  [6, 34],
  [6, 22, 38],
  [6, 24, 42],
  [6, 26, 46],
  [6, 28, 50],
]

const MAX_VERSION = 10
const FORMAT_POLY = 0x537
const FORMAT_XOR = 0x5412
const VERSION_POLY = 0x1f25
// EC level M is `00` in the format information's level field.
const LEVEL_BITS_M = 0

/* ------------------------------------------------------------------ GF(256) */

const GF_EXP = new Uint8Array(512)
const GF_LOG = new Uint8Array(256)
{
  let x = 1
  for (let i = 0; i < 255; i++) {
    GF_EXP[i] = x
    GF_LOG[x] = i
    x <<= 1
    if (x & 0x100) x ^= 0x11d
  }
  for (let i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255]
}

function gfMul(a, b) {
  if (a === 0 || b === 0) return 0
  return GF_EXP[GF_LOG[a] + GF_LOG[b]]
}

/** Generator polynomial of the given degree, highest power first. */
function generatorPoly(degree) {
  let poly = [1]
  for (let i = 0; i < degree; i++) {
    const next = new Array(poly.length + 1).fill(0)
    for (let j = 0; j < poly.length; j++) {
      next[j] ^= poly[j]
      next[j + 1] ^= gfMul(poly[j], GF_EXP[i])
    }
    poly = next
  }
  return poly
}

function rsRemainder(data, ecLength) {
  const gen = generatorPoly(ecLength)
  const rem = new Uint8Array(ecLength)
  for (const byte of data) {
    const factor = byte ^ rem[0]
    rem.copyWithin(0, 1)
    rem[ecLength - 1] = 0
    if (factor !== 0) {
      for (let i = 0; i < ecLength; i++) rem[i] ^= gfMul(gen[i + 1], factor)
    }
  }
  return rem
}

/* --------------------------------------------------------------- bit buffer */

class BitBuffer {
  constructor() {
    this.bits = []
  }

  put(value, length) {
    for (let i = length - 1; i >= 0; i--) this.bits.push((value >>> i) & 1)
  }

  get length() {
    return this.bits.length
  }
}

function utf8Bytes(text) {
  return new TextEncoder().encode(text)
}

function dataCodewordsFor(version) {
  let total = 0
  for (const [count, perBlock] of ECC_M[version].blocks) total += count * perBlock
  return total
}

function chooseVersion(byteLength) {
  for (let version = 1; version <= MAX_VERSION; version++) {
    const header = 4 + (version < 10 ? 8 : 16)
    if (dataCodewordsFor(version) * 8 - header >= byteLength * 8) return version
  }
  throw new Error(`payload of ${byteLength} bytes exceeds the version ${MAX_VERSION} byte-mode capacity`)
}

/* ---------------------------------------------------------------- encoding */

function buildCodewords(bytes, version) {
  const { ec: ecPerBlock, blocks } = ECC_M[version]
  const totalData = dataCodewordsFor(version)
  const buffer = new BitBuffer()
  buffer.put(0b0100, 4)
  buffer.put(bytes.length, version < 10 ? 8 : 16)
  for (const byte of bytes) buffer.put(byte, 8)

  const capacity = totalData * 8
  for (let i = 0; i < 4 && buffer.length < capacity; i++) buffer.bits.push(0)
  while (buffer.length % 8 !== 0) buffer.bits.push(0)

  const data = []
  for (let i = 0; i < buffer.length; i += 8) {
    let byte = 0
    for (let j = 0; j < 8; j++) byte = (byte << 1) | buffer.bits[i + j]
    data.push(byte)
  }
  const pad = [0xec, 0x11]
  for (let i = 0; data.length < totalData; i++) data.push(pad[i % 2])

  // Split into blocks, append each block's remainder, then interleave.
  const dataBlocks = []
  const ecBlocks = []
  let offset = 0
  for (const [count, perBlock] of blocks) {
    for (let i = 0; i < count; i++) {
      const block = data.slice(offset, offset + perBlock)
      offset += perBlock
      dataBlocks.push(block)
      ecBlocks.push(rsRemainder(block, ecPerBlock))
    }
  }
  const out = []
  const maxData = Math.max(...dataBlocks.map((block) => block.length))
  for (let i = 0; i < maxData; i++) {
    for (const block of dataBlocks) if (i < block.length) out.push(block[i])
  }
  for (let i = 0; i < ecPerBlock; i++) {
    for (const block of ecBlocks) out.push(block[i])
  }
  return out
}

/* ------------------------------------------------------------------ matrix */

function createMatrix(size) {
  const rows = []
  for (let r = 0; r < size; r++) rows.push(new Int8Array(size).fill(-1))
  return rows
}

function placeFinder(modules, row, col) {
  const size = modules.length
  for (let r = -1; r <= 7; r++) {
    for (let c = -1; c <= 7; c++) {
      const rr = row + r
      const cc = col + c
      if (rr < 0 || rr >= size || cc < 0 || cc >= size) continue
      const inner = r >= 0 && r <= 6 && c >= 0 && c <= 6
      const dark = inner && (r === 0 || r === 6 || c === 0 || c === 6 || (r >= 2 && r <= 4 && c >= 2 && c <= 4))
      modules[rr][cc] = dark ? 1 : 0
    }
  }
}

function placeAlignment(modules, version) {
  const centers = ALIGNMENT[version]
  const size = modules.length
  for (const r of centers) {
    for (const c of centers) {
      // Skip the three positions occupied by the finder patterns.
      if ((r === 6 && c === 6) || (r === 6 && c === size - 7) || (r === size - 7 && c === 6)) continue
      for (let dr = -2; dr <= 2; dr++) {
        for (let dc = -2; dc <= 2; dc++) {
          const dark = Math.max(Math.abs(dr), Math.abs(dc)) !== 1
          modules[r + dr][c + dc] = dark ? 1 : 0
        }
      }
    }
  }
}

function placeTiming(modules) {
  const size = modules.length
  for (let i = 8; i < size - 8; i++) {
    const dark = i % 2 === 0 ? 1 : 0
    if (modules[6][i] === -1) modules[6][i] = dark
    if (modules[i][6] === -1) modules[i][6] = dark
  }
}

/** Reserve the format-information modules so data placement skips them. */
function reserveFormat(modules) {
  const size = modules.length
  for (let i = 0; i < 9; i++) {
    if (modules[8][i] === -1) modules[8][i] = 0
    if (modules[i][8] === -1) modules[i][8] = 0
  }
  for (let i = 0; i < 8; i++) {
    if (modules[8][size - 1 - i] === -1) modules[8][size - 1 - i] = 0
    if (modules[size - 1 - i][8] === -1) modules[size - 1 - i][8] = 0
  }
  modules[size - 8][8] = 1 // the always-dark module
}

function placeVersionInfo(modules, version) {
  if (version < 7) return
  const size = modules.length
  const bits = bchVersion(version)
  for (let i = 0; i < 18; i++) {
    const bit = (bits >>> i) & 1
    const r = Math.floor(i / 3)
    const c = i % 3
    modules[r][size - 11 + c] = bit
    modules[size - 11 + c][r] = bit
  }
}

function placeData(modules, reserved, codewords) {
  const size = modules.length
  const total = codewords.length * 8
  let index = 0
  let upward = true
  for (let col = size - 1; col > 0; col -= 2) {
    if (col === 6) col--
    for (let i = 0; i < size; i++) {
      const row = upward ? size - 1 - i : i
      for (const c of [col, col - 1]) {
        if (reserved[row][c]) continue
        if (index < total) {
          const byte = codewords[index >> 3]
          modules[row][c] = (byte >>> (7 - (index & 7))) & 1
        } else {
          modules[row][c] = 0
        }
        index++
      }
    }
    upward = !upward
  }
}

function maskAt(mask, row, col) {
  switch (mask) {
    case 0: return (row + col) % 2 === 0
    case 1: return row % 2 === 0
    case 2: return col % 3 === 0
    case 3: return (row + col) % 3 === 0
    case 4: return (Math.floor(row / 2) + Math.floor(col / 3)) % 2 === 0
    case 5: return ((row * col) % 2) + ((row * col) % 3) === 0
    case 6: return (((row * col) % 2) + ((row * col) % 3)) % 2 === 0
    default: return (((row + col) % 2) + ((row * col) % 3)) % 2 === 0
  }
}

function applyMask(modules, reserved, mask) {
  const size = modules.length
  const out = modules.map((row) => Int8Array.from(row))
  for (let r = 0; r < size; r++) {
    for (let c = 0; c < size; c++) {
      if (reserved[r][c]) continue
      if (maskAt(mask, r, c)) out[r][c] ^= 1
    }
  }
  return out
}

function bchFormat(data) {
  let value = data << 10
  for (let i = 14; i >= 10; i--) {
    if ((value >>> i) & 1) value ^= FORMAT_POLY << (i - 10)
  }
  return (((data << 10) | value) ^ FORMAT_XOR) & 0x7fff
}

function bchVersion(version) {
  let value = version << 12
  for (let i = 17; i >= 12; i--) {
    if ((value >>> i) & 1) value ^= VERSION_POLY << (i - 12)
  }
  return ((version << 12) | value) & 0x3ffff
}

function writeFormat(modules, mask) {
  const size = modules.length
  const bits = bchFormat((LEVEL_BITS_M << 3) | mask)
  for (let i = 0; i < 15; i++) {
    const bit = (bits >>> i) & 1
    // First copy: left column upwards and top row leftwards.
    if (i < 6) modules[i][8] = bit
    else if (i === 6) modules[7][8] = bit
    else if (i === 7) modules[8][8] = bit
    else if (i === 8) modules[8][7] = bit
    else modules[8][14 - i] = bit
    // Second copy: bottom-left column and top-right row.
    if (i < 8) modules[8][size - 1 - i] = bit
    else modules[size - 15 + i][8] = bit
  }
  modules[size - 8][8] = 1
}

function penalty(modules) {
  const size = modules.length
  let score = 0

  const runScore = (line) => {
    let total = 0
    let run = 1
    for (let i = 1; i < line.length; i++) {
      if (line[i] === line[i - 1]) {
        run++
      } else {
        if (run >= 5) total += 3 + (run - 5)
        run = 1
      }
    }
    if (run >= 5) total += 3 + (run - 5)
    return total
  }
  for (let r = 0; r < size; r++) score += runScore(modules[r])
  for (let c = 0; c < size; c++) {
    const column = new Int8Array(size)
    for (let r = 0; r < size; r++) column[r] = modules[r][c]
    score += runScore(column)
  }

  for (let r = 0; r < size - 1; r++) {
    for (let c = 0; c < size - 1; c++) {
      const v = modules[r][c]
      if (v === modules[r][c + 1] && v === modules[r + 1][c] && v === modules[r + 1][c + 1]) score += 3
    }
  }

  const pattern = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0]
  const reversed = [0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1]
  const matches = (line, start, target) => {
    for (let i = 0; i < target.length; i++) if (line[start + i] !== target[i]) return false
    return true
  }
  for (let r = 0; r < size; r++) {
    for (let c = 0; c + 11 <= size; c++) {
      if (matches(modules[r], c, pattern) || matches(modules[r], c, reversed)) score += 40
    }
  }
  for (let c = 0; c < size; c++) {
    const column = new Int8Array(size)
    for (let r = 0; r < size; r++) column[r] = modules[r][c]
    for (let r = 0; r + 11 <= size; r++) {
      if (matches(column, r, pattern) || matches(column, r, reversed)) score += 40
    }
  }

  let dark = 0
  for (let r = 0; r < size; r++) for (let c = 0; c < size; c++) if (modules[r][c] === 1) dark++
  const percent = (dark * 100) / (size * size)
  score += Math.floor(Math.abs(percent - 50) / 5) * 10
  return score
}

/* ------------------------------------------------------------------- public */

/** Encode `text` and return the module matrix (1 = dark). */
export function encode(text, options = {}) {
  const value = String(text ?? '')
  if (value.length === 0) throw new Error('qr: text is required')
  const bytes = utf8Bytes(value)
  const version = chooseVersion(bytes.length)
  const size = version * 4 + 17
  const codewords = buildCodewords(bytes, version)

  const reserved = createMatrix(size)
  placeFinder(reserved, 0, 0)
  placeFinder(reserved, 0, size - 7)
  placeFinder(reserved, size - 7, 0)
  placeAlignment(reserved, version)
  placeTiming(reserved)
  reserveFormat(reserved)
  placeVersionInfo(reserved, version)
  const isFunction = reserved.map((row) => row.map((cell) => cell !== -1))
  // Reset the reserved modules so masking sees a clean, function-only matrix.
  const base = createMatrix(size)
  placeFinder(base, 0, 0)
  placeFinder(base, 0, size - 7)
  placeFinder(base, size - 7, 0)
  placeAlignment(base, version)
  placeTiming(base)
  reserveFormat(base)
  placeVersionInfo(base, version)
  for (let r = 0; r < size; r++) {
    for (let c = 0; c < size; c++) if (isFunction[r][c]) base[r][c] = reserved[r][c]
  }
  placeData(base, isFunction, codewords)

  let best = null
  let bestScore = Infinity
  for (let mask = 0; mask < 8; mask++) {
    const candidate = applyMask(base, isFunction, mask)
    writeFormat(candidate, mask)
    const score = penalty(candidate)
    if (score < bestScore) {
      bestScore = score
      best = candidate
    }
  }
  if (options.mask !== undefined) {
    const forced = applyMask(base, isFunction, options.mask)
    writeFormat(forced, options.mask)
    best = forced
  }

  return { version, size, modules: best }
}

/** Render the matrix (or the given text) as a standalone SVG document. */
export function svg(text, options = {}) {
  const quiet = Number.isInteger(options.quiet) ? options.quiet : 2
  const scale = Number.isInteger(options.scale) ? options.scale : 8
  const dark = typeof options.dark === 'string' ? options.dark : '#000000'
  const light = typeof options.light === 'string' ? options.light : '#ffffff'
  const matrix = options.modules ? { size: options.modules.length, modules: options.modules } : encode(text, options)
  const { size, modules } = matrix
  const total = (size + quiet * 2) * scale
  let path = ''
  for (let r = 0; r < size; r++) {
    let c = 0
    while (c < size) {
      if (modules[r][c] !== 1) {
        c++
        continue
      }
      let run = 1
      while (c + run < size && modules[r][c + run] === 1) run++
      const x = (c + quiet) * scale
      const y = (r + quiet) * scale
      path += `M${x} ${y}h${run * scale}v${scale}h-${run * scale}z`
      c += run
    }
  }
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${total} ${total}" width="${total}" height="${total}" ` +
    `shape-rendering="crispEdges" role="img"><rect width="${total}" height="${total}" fill="${light}"/>` +
    `<path d="${path}" fill="${dark}"/></svg>`
  )
}
