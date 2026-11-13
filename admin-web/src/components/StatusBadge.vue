<script setup lang="ts">
defineProps<{ value: string }>()
</script>

<template>
  <span class="badge" :class="`badge-${value.replaceAll(/[^\u4e00-\u9fa5a-zA-Z0-9]/g, '-')}`">{{ value }}</span>
</template>
