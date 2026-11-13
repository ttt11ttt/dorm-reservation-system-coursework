<script setup lang="ts">
defineProps<{ tone?: 'success' | 'error' | 'info'; title?: string }>()
const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <div v-if="title" class="notice" :class="`notice-${tone ?? 'info'}`" role="status">
    <span>{{ title }}</span><button class="notice-close" type="button" aria-label="关闭提示" @click="emit('close')">×</button>
  </div>
</template>
