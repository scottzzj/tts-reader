<script setup>
defineProps({
  formattedCurrentTime: {
    type: String,
    required: true,
  },
  formattedDuration: {
    type: String,
    required: true,
  },
  playableDurationMs: {
    type: Number,
    required: true,
  },
  currentTimeMs: {
    type: Number,
    required: true,
  },
  playheadPercent: {
    type: Number,
    required: true,
  },
})

defineEmits(['seek'])
</script>

<template>
  <section class="timeline">
    <div class="timeline-labels">
      <span>{{ formattedCurrentTime }}</span>
      <span>{{ formattedDuration }}</span>
    </div>
    <input
      class="timeline-range"
      type="range"
      min="0"
      :max="Math.max(playableDurationMs, 1)"
      :value="Math.min(currentTimeMs, playableDurationMs)"
      @input="$emit('seek', $event)"
    />
    <div class="timeline-fill" :style="{ width: `${playheadPercent}%` }"></div>
  </section>
</template>
