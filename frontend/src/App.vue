<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ReaderPanel from './components/ReaderPanel.vue'
import ReaderTimeline from './components/ReaderTimeline.vue'
import ReaderToolbar from './components/ReaderToolbar.vue'
import {
  buildGlobalSegments,
  buildParagraphBatches,
  buildPlaceholderTaskChunk,
  buildReadingParagraphStructure,
  canPlayChunk,
  findChunkIndexByIdentity,
  flattenParagraphTaskChunks,
  formatMs,
  getChunkIdentity,
  mapTaskPayloadChunks,
  normalizeEditorText,
  readerBatchConfig,
  rebuildParagraphChunks,
} from './utils/readerText'

const UI = {
  title: '\u672c\u5730 TTS \u9605\u8bfb\u5668',
  sampleText: '',
  editorTitle: '\u8f93\u5165\u533a',
  previewTitle: '\u9884\u89c8\u533a',
  editorPlaceholder: '\u8bf7\u8f93\u5165\u8981\u8f6c\u6210\u8bed\u97f3\u7684\u6587\u672c',
  errorLoadVoices: '\u52a0\u8f7d\u97f3\u8272\u5931\u8d25',
  errorEmptyText: '\u8bf7\u8f93\u5165\u8981\u8f6c\u6210\u8bed\u97f3\u7684\u6587\u672c',
  errorSynthesis: '\u8bed\u97f3\u751f\u6210\u5931\u8d25',
  errorPolling: '\u4efb\u52a1\u8f6e\u8be2\u5931\u8d25',
  errorAutoplayBlocked: '\u6d4f\u89c8\u5668\u963b\u6b62\u4e86\u81ea\u52a8\u64ad\u653e\uff0c\u8bf7\u518d\u70b9\u51fb\u4e00\u6b21\u64ad\u653e',
  errorResumePlayback: '\u7ee7\u7eed\u64ad\u653e\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u70b9\u51fb\u64ad\u653e',
  errorAudioLoad: '\u97f3\u9891\u52a0\u8f7d\u5931\u8d25',
  voiceNameFallback: '\u9ed8\u8ba4\u7537\u58f0',
}

const voiceNameMap = {
  'cosy-default': '\u9ed8\u8ba4\u7537\u58f0',
  'cosy-gentle': '\u6e29\u67d4\u5973\u58f0',
  'cosy-clear': '\u6e05\u6670\u7537\u58f0',
}

const voiceInitialMap = {
  'cosy-default': '\u9ed8',
  'cosy-gentle': '\u67d4',
  'cosy-clear': '\u6e05',
}

const speedOptions = [1, 1.25, 1.5, 2]
const paragraphPrefetchLimit = 2
const taskPollIntervalMs = 450
const firstChunkPollIntervalMs = 60
const audioPrefetchLookahead = 2
const fallbackVoicePalette = ['#2563eb', '#0f766e', '#d97706', '#7c3aed']
const defaultVoice = {
  id: 'cosy-default',
  initials: 'C',
  accentColor: '#2563eb',
  name: UI.voiceNameFallback,
}

const audioRef = ref(null)
const editorRef = ref(null)
const panelRef = ref(null)

const voices = ref([])
const selectedVoiceId = ref('')
const rate = ref(1)

const draftText = ref(UI.sampleText)
const submittedText = ref('')
const isGenerating = ref(false)
const isBuffering = ref(false)
const isPlaying = ref(false)
const errorMessage = ref('')

const currentTimeMs = ref(0)
const playableDurationMs = ref(0)
const activeSegmentIndex = ref(-1)
const currentChunkIndex = ref(-1)
const firstChunkReady = ref(false)

const paragraphBatches = ref([])
const paragraphTaskChunks = ref([])
const audioChunks = ref([])
const globalSegments = ref([])

let sessionId = 0
let taskRequestController = null
let pendingAutoplay = false
let audioPrefetchPool = new Map()
let pendingSeekMs = null
let lastScrolledSentenceStart = -1

function normalizeVoice(voice, index = 0) {
  const fallbackName = defaultVoice.name
  const mappedName = typeof voice?.id === 'string'
    ? voiceNameMap[voice.id]
    : ''
  const name = mappedName
    || (typeof voice?.name === 'string' && voice.name.trim()
      ? voice.name.trim()
      : fallbackName)
  const accentColor = typeof voice?.accentColor === 'string' && voice.accentColor.trim()
    ? voice.accentColor
    : fallbackVoicePalette[index % fallbackVoicePalette.length]
  const initials = voiceInitialMap[voice?.id]
    || (typeof voice?.initials === 'string' && voice.initials.trim()
      ? voice.initials.trim().slice(0, 2).toUpperCase()
      : name
        .split(/\s+/)
        .filter(Boolean)
        .slice(0, 2)
        .map((part) => part[0])
        .join('')
        .toUpperCase()
        .slice(0, 2))

  return {
    ...defaultVoice,
    ...voice,
    name,
    accentColor,
    initials: initials || defaultVoice.initials,
  }
}

const normalizedVoices = computed(() => voices.value.map((voice, index) => normalizeVoice(voice, index)))
const activeVoice = computed(() => (
  normalizedVoices.value.find((voice) => voice.id === selectedVoiceId.value)
  ?? normalizedVoices.value[0]
  ?? defaultVoice
))

const visibleText = computed(() => submittedText.value || draftText.value)
const hasSession = computed(() => audioChunks.value.length > 0)
const hasPlayableAudio = computed(() => playableDurationMs.value > 0)
const readingTextOffset = computed(() => 0)
const readingText = computed(() => visibleText.value)

const activeSegment = computed(() => globalSegments.value[activeSegmentIndex.value] ?? null)
const displayActiveSegment = computed(() => activeSegment.value)

const formattedCurrentTime = computed(() => formatMs(currentTimeMs.value))
const formattedDuration = computed(() => formatMs(playableDurationMs.value))
const playheadPercent = computed(() => {
  const max = playableDurationMs.value || 1
  return Math.min(100, (currentTimeMs.value / max) * 100)
})
const readingParagraphs = computed(() => buildReadingParagraphStructure(readingText.value))
const flatSentences = computed(() => readingParagraphs.value.flatMap((paragraph) => paragraph.sentences))
const highlightedSentence = computed(() => (
  flatSentences.value.find((sentence) => (
    !!displayActiveSegment.value
    && displayActiveSegment.value.charStart < sentence.charEnd
    && displayActiveSegment.value.charEnd > sentence.charStart
  ))
  ?? null
))
const activeSentenceStart = computed(() => highlightedSentence.value?.charStart ?? -1)

watch(rate, (value) => {
  if (audioRef.value) {
    audioRef.value.playbackRate = value
  }
})

watch(activeSentenceStart, async (value) => {
  const cardBody = panelRef.value?.surfaceRef
  if (!cardBody || value < 0 || value === lastScrolledSentenceStart) return

  const localCharStart = value
  if (localCharStart < 0 || localCharStart > readingText.value.length) return

  await nextTick()
  const activeNode = cardBody.querySelector(`[data-sentence-start="${localCharStart}"]`)
    ?? cardBody.querySelector(`[data-char-index="${localCharStart}"]`)
  if (!activeNode) return

  lastScrolledSentenceStart = value
  activeNode.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' })
})

onMounted(async () => {
  document.title = UI.title
  await fetchVoices()
  setupAudio()
  await nextTick()
  syncEditorContent()
})

onBeforeUnmount(() => {
  teardownAudio()
  clearSession({ keepDraft: true, resetEditor: false })
})

function setupAudio() {
  const audio = audioRef.value
  if (!audio) return
  audio.addEventListener('timeupdate', syncPlayhead)
  audio.addEventListener('ended', handleEnded)
}

function teardownAudio() {
  const audio = audioRef.value
  if (!audio) return
  audio.removeEventListener('timeupdate', syncPlayhead)
  audio.removeEventListener('ended', handleEnded)
}

async function fetchVoices() {
  try {
    const response = await fetch('/api/tts/voices')
    const payload = await parseJsonResponse(response)
    if (!response.ok) {
      throw new Error(payload?.detail || UI.errorLoadVoices)
    }
    voices.value = Array.isArray(payload) && payload.length
      ? payload
      : [defaultVoice]
    if (!selectedVoiceId.value) {
      selectedVoiceId.value = normalizeVoice(voices.value[0]).id
    }
  } catch (error) {
    voices.value = [defaultVoice]
    selectedVoiceId.value = defaultVoice.id
    errorMessage.value = error.message || UI.errorLoadVoices
  }
}

async function togglePlayback() {
  if (isGenerating.value && !hasPlayableAudio.value) return

  const editorText = getEditorText()
  const normalizedDraft = normalizeEditorText(editorText || draftText.value).trim()
  const normalizedSubmitted = normalizeEditorText(submittedText.value).trim()
  const textChanged = normalizedDraft !== normalizedSubmitted

  if (isPlaying.value) {
    audioRef.value?.pause()
    isPlaying.value = false
    return
  }

  if (pendingSeekMs !== null) {
    const pendingChunk = findPlayableChunkForTime(pendingSeekMs)
    if (pendingChunk) {
      const seekMs = Math.max(0, pendingSeekMs - pendingChunk.startMs)
      await playChunk(pendingChunk.index, { autoplay: true, seekMs, session: sessionId })
      return
    }

    if (isGenerating.value) {
      pendingAutoplay = true
      isBuffering.value = true
      return
    }
  }

  if (!hasSession.value || textChanged) {
    await startSynthesisFlow()
    return
  }

  if (
    hasPlayableAudio.value
    && currentTimeMs.value >= Math.max(0, playableDurationMs.value - 20)
    && currentChunkIndex.value === audioChunks.value.length - 1
  ) {
    await playChunk(0, { autoplay: true, seekMs: 0, session: sessionId })
    return
  }

  if (currentChunkIndex.value >= 0) {
    await resumeCurrentChunk()
    return
  }

  if (canPlayChunk(audioChunks.value[0])) {
    await playChunk(0, { autoplay: true, seekMs: 0, session: sessionId })
    return
  }

  if (isGenerating.value) {
    pendingAutoplay = true
    return
  }

  await startSynthesisFlow()
}

async function startSynthesisFlow() {
  const text = normalizeEditorText(getEditorText() || draftText.value).trim()
  if (!text) {
    errorMessage.value = UI.errorEmptyText
    return
  }

  draftText.value = text
  errorMessage.value = ''
  const batches = buildParagraphBatches(text, readerBatchConfig)
  const currentSession = startNewSession(text, batches)
  pendingAutoplay = true
  isGenerating.value = true

  try {
    await createParagraphQueue(currentSession)
  } catch (error) {
    if (currentSession === sessionId) {
      errorMessage.value = error.message || UI.errorSynthesis
      isPlaying.value = false
      isBuffering.value = false
      isGenerating.value = false
    }
  }
}

function startNewSession(text, batches) {
  clearSession({ keepDraft: true, resetEditor: false })
  sessionId += 1
  submittedText.value = text
  paragraphBatches.value = batches
  paragraphTaskChunks.value = batches.map((batch) => [buildPlaceholderTaskChunk(batch)])
  audioChunks.value = rebuildParagraphChunks(flattenParagraphTaskChunks(paragraphTaskChunks.value))
  return sessionId
}

function clearSession(options = { keepDraft: true, resetEditor: true }) {
  sessionId += 1
  cancelPendingRequests()
  resetAudioElement()
  resetAudioPrefetchPool()
  isGenerating.value = false
  isBuffering.value = false
  isPlaying.value = false
  currentTimeMs.value = 0
  playableDurationMs.value = 0
  activeSegmentIndex.value = -1
  currentChunkIndex.value = -1
  firstChunkReady.value = false
  paragraphBatches.value = []
  paragraphTaskChunks.value = []
  globalSegments.value = []
  audioChunks.value = []
  lastScrolledSentenceStart = -1
  submittedText.value = ''
  errorMessage.value = ''
  pendingAutoplay = false
  pendingSeekMs = null
  if (!options.keepDraft) {
    draftText.value = UI.sampleText
    syncEditorContent()
  }
}

function cancelPendingRequests() {
  taskRequestController?.abort()
  taskRequestController = null
}

async function createParagraphQueue(currentSession) {
  taskRequestController?.abort()
  const controller = new AbortController()
  taskRequestController = controller

  let nextIndex = 0
  const workerCount = Math.min(Math.max(1, paragraphPrefetchLimit), paragraphBatches.value.length)
  const workers = Array.from({ length: workerCount }, async () => {
    while (!controller.signal.aborted && currentSession === sessionId) {
      const index = nextIndex
      nextIndex += 1

      if (index >= paragraphBatches.value.length) {
        return
      }

      try {
        await createParagraphTask(currentSession, index, controller)
      } catch (error) {
        controller.abort()
        throw error
      }
    }
  })

  await Promise.all(workers)

  if (currentSession !== sessionId) return
  isGenerating.value = false
}

async function createParagraphTask(currentSession, index, controller) {
  const batch = paragraphBatches.value[index]
  if (!batch) return

  const response = await fetch('/api/tts/tasks', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    signal: controller.signal,
    body: JSON.stringify({
      text: batch.text,
      voiceId: selectedVoiceId.value,
      rate: rate.value,
    }),
  })
  const payload = await parseJsonResponse(response)
  if (!response.ok) {
    throw new Error(payload?.detail || payload?.errorMessage || UI.errorSynthesis)
  }
  if (controller.signal.aborted || currentSession !== sessionId) return

  const nextTaskId = payload.taskId || ''
  await pollParagraphTask(currentSession, index, nextTaskId, controller.signal, payload)
}

async function pollParagraphTask(currentSession, index, paragraphTaskId, signal, initialPayload) {
  let payload = initialPayload

  while (currentSession === sessionId && !signal.aborted) {
    applyParagraphTaskSnapshot(index, payload, currentSession)
    await maybeAutoplayReadyChunk(currentSession)

    const status = typeof payload?.status === 'string' ? payload.status.toUpperCase() : ''
    if (status === 'FAILED') {
      throw new Error(payload?.errorMessage || UI.errorSynthesis)
    }
    if (status === 'COMPLETED') {
      return
    }

    await sleep(resolvePollInterval(index))
    const response = await fetch(`/api/tts/tasks/${paragraphTaskId}`, { signal })
    payload = await parseJsonResponse(response)
    if (!response.ok) {
      throw new Error(payload?.detail || payload?.errorMessage || UI.errorPolling)
    }
  }
}

function applyParagraphTaskSnapshot(index, payload, currentSession) {
  if (!payload || currentSession !== sessionId) return

  const status = typeof payload.status === 'string' ? payload.status.toUpperCase() : ''
  const batch = paragraphBatches.value[index]
  if (!batch) return

  const currentIdentity = getChunkIdentity(audioChunks.value[currentChunkIndex.value])
  const mappedTaskChunks = Array.isArray(payload.chunks) && payload.chunks.length
    ? mapTaskPayloadChunks(batch, payload.chunks)
    : [buildPlaceholderTaskChunk(batch, {
      status: status === 'FAILED' ? 'FAILED' : 'PENDING',
      alignmentStatus: payload.alignmentStatus || 'PENDING',
    })]

  const nextParagraphTaskChunks = [...paragraphTaskChunks.value]
  nextParagraphTaskChunks[index] = mappedTaskChunks
  paragraphTaskChunks.value = nextParagraphTaskChunks

  const nextChunks = rebuildParagraphChunks(flattenParagraphTaskChunks(nextParagraphTaskChunks))
  audioChunks.value = nextChunks
  if (currentIdentity) {
    const remappedIndex = findChunkIndexByIdentity(nextChunks, currentIdentity)
    if (remappedIndex >= 0) {
      currentChunkIndex.value = remappedIndex
    }
  }
  firstChunkReady.value = canPlayChunk(audioChunks.value[0])
  globalSegments.value = buildGlobalSegments(audioChunks.value)
  playableDurationMs.value = audioChunks.value.reduce((max, chunk) => Math.max(max, chunk.endMs ?? -1), 0)

  if (currentTimeMs.value > playableDurationMs.value && !isPlaying.value) {
    currentTimeMs.value = playableDurationMs.value
  }
  updateActiveSegment(currentTimeMs.value)
  scheduleUpcomingAudioPrefetch()
}

async function playChunk(index, options) {
  const { autoplay, seekMs, session } = options
  if (session !== sessionId) return false

  const chunk = audioChunks.value[index]
  if (!canPlayChunk(chunk)) return false

  pendingSeekMs = null
  currentChunkIndex.value = index
  isBuffering.value = false

  await loadAudioSource(chunk.audioUrl, seekMs, { awaitReady: !(autoplay && seekMs === 0) })
  if (session !== sessionId || currentChunkIndex.value !== index) return false

  currentTimeMs.value = chunk.startMs + seekMs
  updateActiveSegment(currentTimeMs.value)
  scheduleUpcomingAudioPrefetch()

  if (!autoplay) {
    isPlaying.value = false
    return true
  }

  try {
    audioRef.value.playbackRate = rate.value
    await audioRef.value.play()
    isPlaying.value = true
    return true
  } catch {
    isPlaying.value = false
    errorMessage.value = UI.errorAutoplayBlocked
    return false
  }
}

async function resumeCurrentChunk() {
  const chunk = audioChunks.value[currentChunkIndex.value]
  if (!canPlayChunk(chunk)) return
  try {
    audioRef.value.playbackRate = rate.value
    await audioRef.value.play()
    isPlaying.value = true
  } catch {
    errorMessage.value = UI.errorResumePlayback
  }
}

async function maybeAutoplayReadyChunk(currentSession) {
  if (currentSession !== sessionId) return

  if (pendingSeekMs !== null) {
    const pendingChunk = findPlayableChunkForTime(pendingSeekMs)
    if (pendingChunk) {
      const seekMs = Math.max(0, pendingSeekMs - pendingChunk.startMs)
      const autoplay = pendingAutoplay || isPlaying.value
      pendingAutoplay = false
      await playChunk(pendingChunk.index, { autoplay, seekMs, session: currentSession })
      return
    }
  }

  if (pendingAutoplay) {
    const firstReadyIndex = findFirstPlayableChunkIndex()
    if (firstReadyIndex >= 0) {
      pendingAutoplay = false
      await playChunk(firstReadyIndex, { autoplay: true, seekMs: 0, session: currentSession })
      return
    }
  }

  if (!isBuffering.value) return

  const nextPlayableIndex = findNextPlayableChunkIndex(currentChunkIndex.value)
  if (nextPlayableIndex >= 0) {
    await playChunk(nextPlayableIndex, { autoplay: true, seekMs: 0, session: currentSession })
  }
}

async function handleEnded() {
  isPlaying.value = false

  const chunk = audioChunks.value[currentChunkIndex.value]
  if (!chunk) return

  currentTimeMs.value = Math.max(currentTimeMs.value, chunk.endMs)
  updateActiveSegment(currentTimeMs.value)

  const nextIndex = findNextPlayableChunkIndex(currentChunkIndex.value)
  if (nextIndex < 0) {
    if (isGenerating.value) {
      return
    }
    isBuffering.value = false
    return
  }

  await playChunk(nextIndex, { autoplay: true, seekMs: 0, session: sessionId })
}

function syncPlayhead() {
  const audio = audioRef.value
  const chunk = audioChunks.value[currentChunkIndex.value]
  if (!audio || !chunk || chunk.startMs < 0) return
  currentTimeMs.value = Math.round(chunk.startMs + audio.currentTime * 1000)
  updateActiveSegment(currentTimeMs.value)
  if ((chunk.endMs - currentTimeMs.value) <= 1200) {
    scheduleUpcomingAudioPrefetch()
  }
}

function updateActiveSegment(timeMs) {
  if (!globalSegments.value.length) {
    activeSegmentIndex.value = -1
    return
  }

  const nextIndex = locateSegmentIndex(globalSegments.value, timeMs, activeSegmentIndex.value)
  if (nextIndex >= 0) {
    activeSegmentIndex.value = nextIndex
    return
  }

  activeSegmentIndex.value = timeMs >= playableDurationMs.value
    ? globalSegments.value.length - 1
    : -1
}

function locateSegmentIndex(segments, timeMs, previousIndex) {
  if (!segments.length) return -1

  const previous = segments[previousIndex]
  if (previous && timeMs >= previous.startMs && timeMs < previous.endMs) {
    return previousIndex
  }

  if (previousIndex >= 0 && previousIndex < segments.length) {
    if (previous && timeMs >= previous.endMs) {
      for (let index = previousIndex + 1; index < segments.length; index += 1) {
        const segment = segments[index]
        if (timeMs >= segment.startMs && timeMs < segment.endMs) {
          return index
        }
        if (timeMs < segment.startMs) {
          return -1
        }
      }
    } else if (previous && timeMs < previous.startMs) {
      for (let index = previousIndex - 1; index >= 0; index -= 1) {
        const segment = segments[index]
        if (timeMs >= segment.startMs && timeMs < segment.endMs) {
          return index
        }
        if (timeMs >= segment.endMs) {
          return -1
        }
      }
    }
  }

  let left = 0
  let right = segments.length - 1
  while (left <= right) {
    const middle = Math.floor((left + right) / 2)
    const segment = segments[middle]
    if (timeMs < segment.startMs) {
      right = middle - 1
      continue
    }
    if (timeMs >= segment.endMs) {
      left = middle + 1
      continue
    }
    return middle
  }

  return -1
}

async function restartAudio() {
  if (!hasSession.value) {
    currentTimeMs.value = 0
    activeSegmentIndex.value = -1
    return
  }

  const autoplay = isPlaying.value
  if (!canPlayChunk(audioChunks.value[0])) {
    currentTimeMs.value = 0
    activeSegmentIndex.value = -1
    pendingAutoplay = autoplay || !hasPlayableAudio.value
    return
  }

  await playChunk(0, { autoplay, seekMs: 0, session: sessionId })
}

async function handleSeek(event) {
  if (!hasPlayableAudio.value) return
  const autoplay = isPlaying.value
  const targetMs = Math.max(0, Math.min(Number(event.target.value), playableDurationMs.value))
  const chunk = findPlayableChunkForTime(targetMs)

  if (!chunk) {
    pendingSeekMs = targetMs
    currentChunkIndex.value = -1
    currentTimeMs.value = targetMs
    updateActiveSegment(targetMs)
    if (autoplay) {
      audioRef.value?.pause()
      isPlaying.value = false
      pendingAutoplay = true
    }
    if (isGenerating.value) {
      isBuffering.value = true
    }
    return
  }

  pendingSeekMs = null
  const localMs = Math.max(0, targetMs - chunk.startMs)
  await playChunk(chunk.index, { autoplay, seekMs: localMs, session: sessionId })
}

async function jumpToChar(index) {
  const globalIndex = index
  const segment = globalSegments.value.find(
    (item) => globalIndex >= item.charStart && globalIndex < item.charEnd,
  )
  if (!segment) return

  const autoplay = isPlaying.value
  const targetMs = segment.startMs
  const chunk = audioChunks.value.find(
    (item) => canPlayChunk(item) && targetMs >= item.startMs && targetMs < item.endMs,
  )
  if (!chunk) return

  await playChunk(chunk.index, {
    autoplay,
    seekMs: Math.max(0, targetMs - chunk.startMs),
    session: sessionId,
  })
}

function cycleRate() {
  const currentIndex = speedOptions.findIndex((option) => option === rate.value)
  rate.value = speedOptions[(currentIndex + 1) % speedOptions.length]
}

function cycleVoice() {
  if (!voices.value.length) return
  const currentIndex = voices.value.findIndex((voice) => voice.id === selectedVoiceId.value)
  const nextVoice = voices.value[(currentIndex + 1 + voices.value.length) % voices.value.length]
  selectedVoiceId.value = nextVoice.id

  if (hasSession.value || isGenerating.value || isPlaying.value) {
    const latestText = normalizeEditorText(getEditorText() || draftText.value)
    draftText.value = latestText || submittedText.value || draftText.value
    clearSession({ keepDraft: true, resetEditor: true })
  }
}

function handleEditorInput(event) {
  draftText.value = normalizeEditorText(event.target.innerText)
}

function handleEditorPaste(event) {
  event.preventDefault()
  const pastedText = event.clipboardData?.getData('text/plain') ?? ''
  if (document.queryCommandSupported?.('insertText')) {
    document.execCommand('insertText', false, pastedText)
    return
  }

  const selection = window.getSelection()
  if (!selection || !selection.rangeCount) return
  selection.deleteFromDocument()
  selection.getRangeAt(0).insertNode(document.createTextNode(pastedText))
  selection.collapseToEnd()
  draftText.value = normalizeEditorText(panelRef.value?.getEditorText('') || '')
}

function syncEditorContent() {
  if (!editorRef.value) return
  if (editorRef.value.innerText !== draftText.value) {
    editorRef.value.innerText = draftText.value
  }
}

function getEditorText() {
  return normalizeEditorText(editorRef.value?.innerText || draftText.value)
}

async function loadAudioSource(url, seekMs, options = {}) {
  const { awaitReady = true } = options
  const audio = audioRef.value
  if (!audio || !url) return

  const resolvedUrl = resolveAudioUrl(url)
  if (audio.src !== resolvedUrl) {
    audio.pause()
    audio.src = resolvedUrl
    audio.load()
    if (awaitReady) {
      await waitForAudioReady(audio)
    }
  }

  audio.currentTime = Math.max(0, seekMs / 1000)
}

function resolveAudioUrl(url) {
  if (/^https?:\/\//.test(url)) return url
  return new URL(url, window.location.origin).href
}

function resetAudioPrefetchPool() {
  audioPrefetchPool.forEach((audio) => {
    audio.pause()
    audio.removeAttribute('src')
    audio.load()
  })
  audioPrefetchPool.clear()
}

function scheduleUpcomingAudioPrefetch() {
  const desiredUrls = []
  const startIndex = currentChunkIndex.value >= 0
    ? currentChunkIndex.value + 1
    : findFirstPlayableChunkIndex() + 1

  if (startIndex <= 0) {
    syncPrefetchPool(desiredUrls)
    return
  }

  for (let index = startIndex; index < audioChunks.value.length; index += 1) {
    const chunk = audioChunks.value[index]
    if (!canPlayChunk(chunk)) {
      continue
    }

    const resolvedUrl = resolveAudioUrl(chunk.audioUrl)
    if (resolvedUrl) {
      desiredUrls.push(resolvedUrl)
    }
    if (desiredUrls.length >= audioPrefetchLookahead) {
      break
    }
  }

  syncPrefetchPool(desiredUrls)
}

function syncPrefetchPool(desiredUrls) {
  const desiredSet = new Set(desiredUrls)

  audioPrefetchPool.forEach((audio, url) => {
    if (desiredSet.has(url)) {
      return
    }
    audio.pause()
    audio.removeAttribute('src')
    audio.load()
    audioPrefetchPool.delete(url)
  })

  desiredUrls.forEach((url) => {
    if (audioPrefetchPool.has(url)) {
      return
    }

    const preloadAudio = new Audio()
    preloadAudio.preload = 'auto'
    preloadAudio.src = url
    preloadAudio.load()
    audioPrefetchPool.set(url, preloadAudio)
  })
}

function waitForAudioReady(audio) {
  if (isAudioElementReady(audio)) {
    return Promise.resolve()
  }

  return new Promise((resolve, reject) => {
    const cleanup = () => {
      audio.removeEventListener('loadedmetadata', handleReady)
      audio.removeEventListener('loadeddata', handleReady)
      audio.removeEventListener('canplay', handleReady)
      audio.removeEventListener('canplaythrough', handleReady)
      audio.removeEventListener('error', handleError)
    }
    const handleReady = () => {
      cleanup()
      resolve()
    }
    const handleError = () => {
      cleanup()
      reject(new Error(UI.errorAudioLoad))
    }
    audio.addEventListener('loadedmetadata', handleReady, { once: true })
    audio.addEventListener('loadeddata', handleReady, { once: true })
    audio.addEventListener('canplay', handleReady, { once: true })
    audio.addEventListener('canplaythrough', handleReady, { once: true })
    audio.addEventListener('error', handleError, { once: true })
  })
}

function isAudioElementReady(audio) {
  return !!(audio.currentSrc || audio.src) && audio.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA
}

function findFirstPlayableChunkIndex() {
  return audioChunks.value.findIndex((chunk) => canPlayChunk(chunk))
}

function findPlayableChunkForTime(targetMs) {
  return audioChunks.value.find(
    (chunk) => canPlayChunk(chunk) && targetMs >= chunk.startMs && targetMs < chunk.endMs,
  ) ?? null
}

function findNextPlayableChunkIndex(afterIndex) {
  const startIndex = Math.max(-1, Number.isFinite(afterIndex) ? afterIndex : -1)

  for (let index = startIndex + 1; index < audioChunks.value.length; index += 1) {
    if (canPlayChunk(audioChunks.value[index])) {
      return index
    }
  }

  if (isGenerating.value) {
    isBuffering.value = true
  }

  return -1
}

function resetAudioElement() {
  const audio = audioRef.value
  if (!audio) return
  audio.pause()
  audio.removeAttribute('src')
  audio.load()
}

async function parseJsonResponse(response) {
  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    return response.json()
  }
  const text = await response.text()
  return text ? { detail: text } : null
}

function sleep(ms) {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms)
  })
}

function resolvePollInterval(index) {
  return index === 0 && !firstChunkReady.value
    ? firstChunkPollIntervalMs
    : taskPollIntervalMs
}
</script>

<template>
  <div class="reader-shell">
    <div class="ambient ambient-left"></div>
    <div class="ambient ambient-right"></div>

    <ReaderToolbar
      :active-voice="activeVoice"
      :voices-count="normalizedVoices.length"
      :rate="rate"
      :is-playing="isPlaying"
      :is-generating="isGenerating"
      :has-playable-audio="hasPlayableAudio"
      @restart="restartAudio"
      @toggle-playback="togglePlayback"
      @cycle-rate="cycleRate"
      @cycle-voice="cycleVoice"
    />

    <ReaderTimeline
      :formatted-current-time="formattedCurrentTime"
      :formatted-duration="formattedDuration"
      :playable-duration-ms="playableDurationMs"
      :current-time-ms="currentTimeMs"
      :playhead-percent="playheadPercent"
      @seek="handleSeek"
    />

    <main class="reader-main">
      <section class="reader-stage">
        <section class="reader-grid">
          <section class="reader-card">
            <div class="card-label">{{ UI.editorTitle }}</div>
            <div class="editor-wrap">
              <div
                ref="editorRef"
                class="reader-editor"
                contenteditable="true"
                spellcheck="false"
                :data-placeholder="UI.editorPlaceholder"
                @input="handleEditorInput"
                @paste="handleEditorPaste"
              ></div>
            </div>
          </section>

          <section class="reader-card">
            <div class="card-label">{{ UI.previewTitle }}</div>
          <ReaderPanel
            ref="panelRef"
            :reading-paragraphs="readingParagraphs"
            :active-segment="displayActiveSegment"
            :active-sentence-start="activeSentenceStart"
            @jump="jumpToChar"
          />
          </section>
        </section>
      </section>

      <p v-if="errorMessage" class="error-message">{{ errorMessage }}</p>
    </main>

    <audio ref="audioRef" class="hidden-audio" preload="auto"></audio>
  </div>
</template>
