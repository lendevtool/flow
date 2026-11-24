<template>
  <CommonPage>
    <template #action>
      <n-radio-group
        :value="openingMode"
        :disabled="saving"
        :aria-label="t('商品详情打开方式')"
        class="product-opening-modes"
        @update:value="changeOpeningMode"
      >
        <n-radio v-for="(label, mode) in openingModes" :key="mode" :value="mode">
          {{ t(label) }}
        </n-radio>
      </n-radio-group>
    </template>

    <div class="product-layout" :class="{ 'product-layout--split': split }">
      <div class="min-h-0 min-w-0 flex flex-col">
        <n-alert v-if="listFailed" type="error" class="mb-12 shrink-0">
          {{ t('加载商品失败') }}
          <NButton text class="ml-8" @click="refresh">
            {{ t('重试') }}
          </NButton>
        </n-alert>
        <MeCrud
          ref="table"
          :scroll-x="split ? 350 : 920"
          :page-size="20"
          :columns="columns"
          :get-data="api.list"
          class="min-h-0 flex-1"
          @update:query-failed="listFailed = $event"
        />
      </div>
      <aside v-if="split && previewId" class="product-preview" :aria-label="t('商品详情')">
        <div class="mb-12 flex items-center justify-between">
          <h3 class="font-medium">
            {{ t('商品详情') }}
          </h3>
          <NButton size="small" :disabled="saving" @click="closePreview">
            {{ t('收起详情') }}
          </NButton>
        </div>
        <ProductDetails :id="previewId" :key="previewId" @saved="refresh" @busy="saving = $event" />
      </aside>
    </div>

    <n-modal
      v-if="openingMode === 'modal'"
      :show="previewId !== null"
      preset="card"
      :title="t('商品详情')"
      :closable="!saving"
      :close-on-esc="!saving"
      :mask-closable="!saving"
      :content-style="{ flex: '1 1 auto', minHeight: 0, overflowY: 'auto' }"
      style="width: min(1000px, calc(100vw - 32px)); height: min(720px, calc(100dvh - 32px))"
      @update:show="visible => !visible && closePreview()"
    >
      <ProductDetails v-if="previewId" :id="previewId" :key="previewId" @saved="refresh" @busy="saving = $event" />
    </n-modal>

    <n-drawer
      v-if="openingMode === 'drawer'"
      :show="previewId !== null"
      width="min(860px, 100vw)"
      :close-on-esc="!saving"
      :mask-closable="!saving"
      @update:show="visible => !visible && closePreview()"
    >
      <n-drawer-content :title="t('商品详情')" :closable="!saving">
        <ProductDetails v-if="previewId" :id="previewId" :key="previewId" @saved="refresh" @busy="saving = $event" />
      </n-drawer-content>
    </n-drawer>
  </CommonPage>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { ProductSummary } from './model'
import { useStorage } from '@vueuse/core'
import { NButton, NTag, useThemeVars } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import { t } from '@/i18n'
import { formatDateTime } from '@/utils'
import api from './api'
import { openingModes, productOpeningMode, productStatuses } from './model'
import ProductDetails from './ProductDetails.vue'

defineOptions({ name: 'ProductCenter' })

const router = useRouter()
const theme = useThemeVars()
const borderColor = computed(() => theme.value.borderColor)
const table = ref<InstanceType<typeof MeCrud> | null>(null)
const listFailed = ref(false)
const saving = ref(false)
const previewId = ref<number | null>(null)
const storedMode = useStorage('flow-product-detail-opening', 'page', undefined, { listenToStorageChanges: false })
const openingMode = computed(() => productOpeningMode(storedMode.value))
const split = computed(() => openingMode.value === 'split' && previewId.value !== null)

const columns = computed<DataTableColumns<ProductSummary>>(() => {
  const result: DataTableColumns<ProductSummary> = [
    {
      title: t('商品编号'),
      key: 'code',
      minWidth: 100,
      ellipsis: { tooltip: true },
    },
    {
      title: t('商品名称'),
      key: 'name',
      minWidth: 155,
      ellipsis: { tooltip: true },
      render: row => h(NButton, {
        'text': true,
        'type': 'primary',
        'class': 'product-name',
        'disabled': saving.value,
        'aria-current': split.value && previewId.value === row.id
          ? 'true'
          : undefined,
        'onClick': () => openProduct(row.id),
      }, () => h('span', row.name)),
    },
  ]
  if (!split.value) {
    result.push(
      {
        title: t('商品分类'),
        key: 'category',
        minWidth: 120,
        ellipsis: { tooltip: true },
      },
      {
        title: t('品牌'),
        key: 'brand',
        minWidth: 120,
        ellipsis: { tooltip: true },
        render: row => row.brand || '—',
      },
      {
        title: t('状态'),
        key: 'status',
        width: 90,
        render: row => h(NTag, {
          size: 'small',
          type: row.status === 'ENABLED'
            ? 'success'
            : 'default',
        }, () => t(productStatuses[row.status])),
      },
      {
        title: t('更新时间'),
        key: 'updatedAt',
        minWidth: 180,
        render: row => formatDateTime(row.updatedAt),
      },
    )
  }
  result.push({
    title: t('操作'),
    key: 'actions',
    width: 85,
    align: 'right',
    fixed: 'right',
    render: row => h(TableActions, {
      actions: [
        {
          key: 'detail',
          label: t('详情'),
          disabled: saving.value,
          onClick: () => openPage(row.id),
        },
      ],
    }),
  })
  return result
})

onMounted(refresh)

function refresh() {
  return table.value?.handleSearch(true)
}

function changeOpeningMode(value: string) {
  if (saving.value)
    return
  closePreview()
  storedMode.value = productOpeningMode(value)
}

function closePreview() {
  if (!saving.value)
    previewId.value = null
}

function openPage(id: number) {
  if (saving.value)
    return
  closePreview()
  void router.push({
    name: 'ProductCenterDetail',
    params: { id },
  })
}

function openProduct(id: number) {
  if (saving.value)
    return
  if (openingMode.value === 'page')
    openPage(id)
  else
    previewId.value = id
}
</script>

<style scoped>
.product-opening-modes {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 4px 12px;
  margin-inline-start: auto;
  padding: 8px 0 8px 12px;
}

.product-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  height: 100%;
}

.product-layout--split {
  grid-template-columns: minmax(300px, 0.55fr) minmax(0, 1fr);
}

.product-preview {
  min-width: 0;
  overflow: auto;
  padding-inline-start: 16px;
  margin-inline-start: 16px;
  border-inline-start: 1px solid v-bind(borderColor);
}

:deep(.product-name) {
  max-width: 100%;
  min-height: 24px;
}

:deep(.product-name .n-button__content),
:deep(.product-name .n-button__content span) {
  overflow: hidden;
  text-overflow: ellipsis;
}

:deep(.product-name[aria-current='true']) {
  font-weight: 600;
}

@media (max-width: 1000px) {
  .product-layout--split {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: minmax(270px, 45%) auto;
    height: auto;
    min-height: 100%;
  }

  .product-preview {
    overflow: visible;
    padding: 16px 0 0;
    margin: 16px 0 0;
    border-inline-start: 0;
    border-top: 1px solid v-bind(borderColor);
  }
}
</style>
