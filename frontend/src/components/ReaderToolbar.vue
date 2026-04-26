<script setup>
import {
  Pause,
  Play,
  RotateCcw,
} from 'lucide-vue-next'

const UI = {
  titleSwitchVoice: '\u5207\u6362\u97f3\u8272',
  titleCurrentVoice: '\u5f53\u524d\u97f3\u8272',
}

defineProps({
  activeVoice: {
    type: Object,
    required: true,
  },
  voicesCount: {
    type: Number,
    default: 0,
  },
  rate: {
    type: Number,
    required: true,
  },
  isPlaying: {
    type: Boolean,
    default: false,
  },
  isGenerating: {
    type: Boolean,
    default: false,
  },
  hasPlayableAudio: {
    type: Boolean,
    default: false,
  },
  headline: {
    type: String,
    default: '\u672c\u5730 TTS \u9605\u8bfb\u5668',
  },
})

defineEmits(['restart', 'toggle-playback', 'cycle-rate', 'cycle-voice'])
</script>

<template>
  <header class="toolbar">
    <div class="toolbar-brand">
      <strong class="toolbar-title">{{ headline }}</strong>
    </div>

    <div class="toolbar-center">
      <div class="voice-stack">
        <button
          class="voice-badge"
          type="button"
          :style="{ '--voice-color': activeVoice.accentColor }"
          :title="voicesCount > 1 ? `${UI.titleSwitchVoice}: ${activeVoice.name}` : `${UI.titleCurrentVoice}: ${activeVoice.name}`"
          @click="$emit('cycle-voice')"
        >
          <span>{{ activeVoice.initials }}</span>
        </button>
        <span class="voice-stack-name">{{ activeVoice.name }}</span>
      </div>

      <button class="toolbar-icon" type="button" aria-label="Restart" @click="$emit('restart')">
        <RotateCcw :size="24" />
      </button>

      <button
        class="play-button"
        type="button"
        :disabled="isGenerating && !hasPlayableAudio"
        @click="$emit('toggle-playback')"
      >
        <Pause v-if="isPlaying" :size="28" />
        <Play v-else :size="28" />
      </button>

      <button class="speed-button" type="button" @click="$emit('cycle-rate')">{{ rate }}x</button>
    </div>

    <div class="toolbar-spacer" aria-hidden="true"></div>
  </header>
</template>
