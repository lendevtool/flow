<template>
  <CommonPage>
    <template #title-prefix>
      <n-button text class="mr-16" :disabled="saving" @click="returnToList">
        <template #icon>
          <i class="i-material-symbols:arrow-left-alt" aria-hidden="true" />
        </template>
        {{ t('返回商品中心') }}
      </n-button>
    </template>

    <ProductDetails v-if="productId" :id="productId" :key="productId" @busy="saving = $event" />
    <n-empty v-else :description="t('找不到该商品')" class="py-28" />
  </CommonPage>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { t } from '@/i18n'
import ProductDetails from './ProductDetails.vue'

defineOptions({ name: 'ProductCenterDetail' })

const route = useRoute()
const router = useRouter()
const saving = ref(false)
const productId = computed(() => {
  const id = route.params.id
  if (typeof id !== 'string' || !/^\d+$/.test(id))
    return null
  const value = Number(id)
  return Number.isSafeInteger(value) && value > 0
    ? value
    : null
})

function returnToList() {
  return router.push({ name: 'ProductCenter' })
}
</script>
