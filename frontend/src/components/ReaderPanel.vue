<script setup>
import { computed, ref } from 'vue'
import { buildSentenceChars } from '../utils/readerText'

const props = defineProps({
  readingParagraphs: {
    type: Array,
    default: () => [],
  },
  activeSegment: {
    type: Object,
    default: null,
  },
  activeSentenceStart: {
    type: Number,
    default: -1,
  },
})

const emit = defineEmits(['jump'])

const surfaceRef = ref(null)
const currentSegment = computed(() => props.activeSegment)

function overlapsSentence(sentence) {
  const segment = currentSegment.value
  return !!segment
    && segment.charStart < sentence.charEnd
    && segment.charEnd > sentence.charStart
}

function getSentenceChars(sentence) {
  return buildSentenceChars(sentence, currentSegment.value)
}

defineExpose({
  surfaceRef,
})
</script>

<template>
  <div
    ref="surfaceRef"
    class="reading-surface"
  >
    <p
      v-for="paragraph in readingParagraphs"
      :key="paragraph.key"
      class="reading-paragraph"
    >
      <span
        v-if="paragraph.empty"
        class="reading-empty-line"
      >&nbsp;</span>

      <template v-else>
        <span
          v-for="sentence in paragraph.sentences"
          :key="sentence.key"
          class="reading-sentence"
          :class="{ active: overlapsSentence(sentence), current: sentence.charStart === activeSentenceStart }"
          :data-sentence-start="sentence.charStart"
          @click.stop="emit('jump', sentence.charStart)"
        >
          <template v-if="overlapsSentence(sentence)">
            <span
              v-for="char in getSentenceChars(sentence)"
              :key="char.index"
              class="reading-char"
              :class="{ active: char.active }"
              :data-char-index="char.index"
              @click.stop="emit('jump', char.index)"
            >
              {{ char.character }}
            </span>
          </template>
          <template v-else>{{ sentence.text }}</template>
        </span>
      </template>
    </p>
  </div>
</template>
