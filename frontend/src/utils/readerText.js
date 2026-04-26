export const readerBatchConfig = {
  firstTargetLength: 180,
  firstMinimumLength: 120,
  paragraphTargetLength: 320,
  paragraphMinLength: 220,
}

const sentenceBoundaryPattern = /[\u3002\uFF01\uFF1F\uFF1B.!?]/

export function formatMs(ms) {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const minutes = String(Math.floor(totalSeconds / 60)).padStart(2, '0')
  const seconds = String(totalSeconds % 60).padStart(2, '0')
  return `${minutes}:${seconds}`
}

export function normalizeEditorText(text = '') {
  return text
    .replace(/\r\n/g, '\n')
    .replace(/\u00A0/g, ' ')
    .replace(/\u200B/g, '')
}

export function buildTextStats(text) {
  const normalizedText = normalizeEditorText(text)
  const characters = normalizedText.replace(/\s/g, '').length
  const sentences = normalizedText
    .split(/[\u3002\uFF01\uFF1F\uFF1B.!?;\n]+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .length

  return {
    characters,
    sentences,
  }
}

export function splitSentenceRanges(text, baseOffset) {
  if (!text) return []

  const ranges = []
  let start = 0

  for (let index = 0; index < text.length; index += 1) {
    if (!sentenceBoundaryPattern.test(text[index])) continue
    ranges.push(createSentenceRange(text, start, index + 1, baseOffset, ranges.length))
    start = index + 1
  }

  if (start < text.length) {
    ranges.push(createSentenceRange(text, start, text.length, baseOffset, ranges.length))
  }

  return ranges
}

function createSentenceRange(text, start, end, baseOffset, index) {
  return {
    key: `sentence-${baseOffset + start}-${index}`,
    text: text.slice(start, end),
    charStart: baseOffset + start,
    charEnd: baseOffset + end,
  }
}

export function buildReadingParagraphStructure(text) {
  if (!text) return []

  let offset = 0
  return text.split('\n').map((paragraphText, paragraphIndex, paragraphs) => {
    const paragraph = {
      key: `paragraph-${paragraphIndex}-${offset}`,
      empty: paragraphText.length === 0,
      sentences: splitSentenceRanges(paragraphText, offset),
    }

    offset += paragraphText.length
    if (paragraphIndex < paragraphs.length - 1) {
      offset += 1
    }
    return paragraph
  })
}

export function buildSentenceChars(sentence, segment) {
  if (!sentence?.text) return []

  return sentence.text.split('').map((character, index) => {
    const charIndex = sentence.charStart + index
    return {
      character,
      index: charIndex,
      active: !!segment && charIndex >= segment.charStart && charIndex < segment.charEnd,
    }
  })
}

export function buildFocusPreviewChars(sentence, segment) {
  if (!sentence) return []

  const chars = sentence.chars ?? []
  if (chars.length <= 72) {
    return chars
  }

  const activeIndex = segment
    ? Math.max(0, chars.findIndex((char) => char.index >= segment.charStart && char.index < segment.charEnd))
    : 0
  const windowRadius = 24
  const start = Math.max(0, activeIndex - windowRadius)
  const end = Math.min(chars.length, activeIndex + windowRadius + 1)
  const preview = chars.slice(start, end)

  if (start > 0) {
    preview.unshift({
      character: '...',
      index: `prefix-${sentence.charStart}`,
      active: false,
    })
  }

  if (end < chars.length) {
    preview.push({
      character: '...',
      index: `suffix-${sentence.charEnd}`,
      active: false,
    })
  }

  return preview
}

export function normalizeChunkStatus(status) {
  return typeof status === 'string' ? status.toUpperCase() : ''
}

export function canPlayChunk(chunk) {
  return !!chunk && chunk.startMs >= 0 && !!chunk.audioUrl && normalizeChunkStatus(chunk.status) === 'READY'
}

export function buildPlaceholderTaskChunk(batch, overrides = {}) {
  return {
    index: 0,
    sourceBatchIndex: batch.index,
    sourceChunkIndex: 0,
    status: 'PENDING',
    alignmentStatus: 'PENDING',
    text: batch.text,
    charStart: batch.charStart,
    charEnd: batch.charEnd,
    durationMs: 0,
    startMs: -1,
    endMs: -1,
    audioUrl: '',
    fileName: '',
    roughSegments: [],
    segments: [],
    ...overrides,
  }
}

function normalizeTaskSegments(segments) {
  if (!Array.isArray(segments)) return []
  return segments.map((segment, index) => ({
    ...segment,
    index,
    charStart: Math.max(0, segment.charStart ?? 0),
    charEnd: Math.max(0, segment.charEnd ?? 0),
  }))
}

export function mapTaskPayloadChunks(batch, taskChunks) {
  return taskChunks.map((chunk, pieceIndex) => {
    const localCharStart = Number(chunk?.charStart ?? 0)
    const localCharEnd = Number(chunk?.charEnd ?? localCharStart)
    return {
      index: pieceIndex,
      sourceBatchIndex: batch.index,
      sourceChunkIndex: Number(chunk?.index ?? pieceIndex),
      status: chunk?.status ?? 'PENDING',
      alignmentStatus: chunk?.alignmentStatus ?? 'PENDING',
      text: chunk?.text || batch.text,
      charStart: batch.charStart + localCharStart,
      charEnd: batch.charStart + localCharEnd,
      durationMs: Number(chunk?.durationMs ?? 0),
      startMs: -1,
      endMs: -1,
      audioUrl: chunk?.audioUrl || '',
      fileName: chunk?.fileName || '',
      roughSegments: normalizeTaskSegments(chunk?.roughSegments),
      segments: normalizeTaskSegments(chunk?.segments),
    }
  })
}

export function flattenParagraphTaskChunks(taskChunkGroups) {
  const flattened = []
  taskChunkGroups.forEach((group) => {
    if (!Array.isArray(group) || !group.length) return
    group.forEach((chunk) => {
      flattened.push({
        ...chunk,
        index: flattened.length,
      })
    })
  })
  return flattened
}

export function getChunkIdentity(chunk) {
  if (!chunk) return ''
  return [
    chunk.sourceBatchIndex ?? -1,
    chunk.sourceChunkIndex ?? -1,
    chunk.charStart ?? -1,
    chunk.charEnd ?? -1,
  ].join(':')
}

export function findChunkIndexByIdentity(chunks, identity) {
  return chunks.findIndex((chunk) => getChunkIdentity(chunk) === identity)
}

export function buildParagraphBatches(text, config = readerBatchConfig) {
  const normalized = normalizeEditorText(text)
  const paragraphs = []
  let paragraphStart = -1
  let cursor = 0

  while (cursor < normalized.length) {
    const lineEnd = normalized.indexOf('\n', cursor)
    const nextCursor = lineEnd >= 0 ? lineEnd + 1 : normalized.length
    const lineText = normalized.slice(cursor, lineEnd >= 0 ? lineEnd : normalized.length)
    const hasContent = lineText.trim().length > 0

    if (hasContent && paragraphStart < 0) {
      paragraphStart = cursor
    }

    if (!hasContent && paragraphStart >= 0) {
      const paragraphEnd = cursor > 0 && normalized[cursor - 1] === '\n' ? cursor - 1 : cursor
      const paragraphText = normalized.slice(paragraphStart, paragraphEnd)
      if (paragraphText.trim()) {
        paragraphs.push({
          text: paragraphText,
          charStart: paragraphStart,
          charEnd: paragraphEnd,
        })
      }
      paragraphStart = -1
    }

    cursor = nextCursor
  }

  if (paragraphStart >= 0) {
    const paragraphText = normalized.slice(paragraphStart)
    if (paragraphText.trim()) {
      paragraphs.push({
        text: paragraphText,
        charStart: paragraphStart,
        charEnd: normalized.length,
      })
    }
  }

  const batches = []
  paragraphs.forEach((paragraph, paragraphIndex) => {
    const targetLength = paragraphIndex === 0 ? config.firstTargetLength : config.paragraphTargetLength
    const minLength = paragraphIndex === 0
      ? Math.max(24, Math.floor(config.firstTargetLength * 0.6))
      : config.paragraphMinLength
    batches.push(...splitParagraphIntoBatches(paragraph.text, paragraph.charStart, targetLength, minLength))
  })

  const normalizedBatches = mergeShortLeadingBatch(normalized, batches, config.firstMinimumLength)

  return normalizedBatches.length
    ? normalizedBatches.map((batch, index) => ({
      ...batch,
      index,
    }))
    : [{
      index: 0,
      text: normalized,
      charStart: 0,
      charEnd: normalized.length,
    }]
}

function mergeShortLeadingBatch(sourceText, batches, firstMinimumLength) {
  if (batches.length < 2) {
    return batches
  }

  const [firstBatch, secondBatch, ...rest] = batches
  if (firstBatch.text.trim().length >= firstMinimumLength) {
    return batches
  }

  return [
    {
      text: sourceText.slice(firstBatch.charStart, secondBatch.charEnd),
      charStart: firstBatch.charStart,
      charEnd: secondBatch.charEnd,
    },
    ...rest,
  ]
}

function splitParagraphIntoBatches(text, baseCharStart, targetLength, minLength) {
  const sentences = splitSentenceRanges(text, 0)
  if (!sentences.length) {
    return [{
      text,
      charStart: baseCharStart,
      charEnd: baseCharStart + text.length,
    }]
  }

  const batches = []
  let batchStart = sentences[0].charStart
  let batchEnd = sentences[0].charEnd
  let batchText = text.slice(batchStart, batchEnd)

  for (let index = 1; index < sentences.length; index += 1) {
    const sentence = sentences[index]
    const candidateEnd = sentence.charEnd
    const candidateText = text.slice(batchStart, candidateEnd)

    if (batchText.length < minLength || candidateText.length <= targetLength) {
      batchEnd = candidateEnd
      batchText = candidateText
      continue
    }

    batches.push({
      text: batchText,
      charStart: baseCharStart + batchStart,
      charEnd: baseCharStart + batchEnd,
    })

    batchStart = sentence.charStart
    batchEnd = sentence.charEnd
    batchText = text.slice(batchStart, batchEnd)
  }

  if (batchText.trim()) {
    batches.push({
      text: batchText,
      charStart: baseCharStart + batchStart,
      charEnd: baseCharStart + batchEnd,
    })
  }

  return batches
}

export function rebuildParagraphChunks(chunks) {
  let cursor = 0
  let prefixContinuous = true

  return chunks.map((chunk) => {
    if (normalizeChunkStatus(chunk.status) !== 'READY' || !chunk.audioUrl || !chunk.durationMs || !prefixContinuous) {
      if (normalizeChunkStatus(chunk.status) !== 'READY') {
        prefixContinuous = false
      }
      return {
        ...chunk,
        startMs: -1,
        endMs: -1,
      }
    }

    const startMs = cursor
    const endMs = startMs + chunk.durationMs
    cursor = endMs
    return {
      ...chunk,
      startMs,
      endMs,
    }
  })
}

export function buildGlobalSegments(chunks) {
  const segments = []

  chunks.forEach((chunk) => {
    if (chunk.startMs < 0) return
    const sourceSegments = Array.isArray(chunk.segments) && chunk.segments.length
      ? chunk.segments
      : chunk.roughSegments

    sourceSegments.forEach((segment) => {
      segments.push({
        index: segments.length,
        text: segment.text,
        startMs: (segment.startMs ?? 0) + chunk.startMs,
        endMs: (segment.endMs ?? 0) + chunk.startMs,
        charStart: (segment.charStart ?? 0) + chunk.charStart,
        charEnd: (segment.charEnd ?? 0) + chunk.charStart,
      })
    })
  })

  return segments
}
