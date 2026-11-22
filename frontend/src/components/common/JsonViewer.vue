<template>
  <div>
    <div class="mb-4 flex justify-end">
      <CopyButton :text="code" />
    </div>
    <n-code :code="code" language="json" word-wrap />
  </div>
</template>

<script setup>
const props = defineProps({
  value: { default: null },
})
const code = computed(() => JSON.stringify(props.value, null, 2) ?? '')
</script>
