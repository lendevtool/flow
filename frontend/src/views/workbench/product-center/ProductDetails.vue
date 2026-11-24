<template>
  <n-alert v-if="loadError" type="error">
    {{ loadError }}
    <n-button text class="ml-8" @click="load">
      {{ t('重试') }}
    </n-button>
  </n-alert>
  <n-spin v-else-if="loading" class="py-28" />
  <div v-else-if="product">
    <div class="product-summary" :aria-label="t('商品主要信息')">
      <span class="font-mono">{{ product.code }}</span>
      <strong>{{ product.name }}</strong>
      <span>{{ product.category }}</span>
      <span>{{ product.brand || '—' }}</span>
      <n-tag size="small" :type="product.status === 'ENABLED' ? 'success' : 'default'">
        {{ t(productStatuses[product.status]) }}
      </n-tag>
    </div>

    <n-tabs v-model:value="tab" type="line" class="mt-8">
      <n-tab-pane name="basic" :tab="t('基本信息')" :tab-props="tabControl('basic')">
        <n-alert v-if="sectionError" type="error" class="mb-12">
          {{ t(sectionError) }}
          <n-button v-if="sectionError === conflictMessage" text class="ml-8" @click="reloadAfterConflict">
            {{ t('放弃未保存修改并重新加载') }}
          </n-button>
        </n-alert>
        <section :aria-labelledby="basicTitleId">
          <h3 :id="basicTitleId" class="mb-12 font-medium">
            {{ t('商品资料') }}
          </h3>
          <ProductFields :fields="basicFields" :values="basicDraft" :disabled="!!pending" @change="changeBasic" @submit="saveSection('basic')">
            <div class="flex justify-end">
              <n-button type="primary" attr-type="submit" :loading="pending === 'basic'" :disabled="!!pending">
                {{ t('保存商品资料') }}
              </n-button>
            </div>
          </ProductFields>
        </section>
        <n-divider />
        <section :aria-labelledby="packagingTitleId">
          <h3 :id="packagingTitleId" class="mb-12 font-medium">
            {{ t('包装与物流') }}
          </h3>
          <ProductFields :fields="packagingFields" :values="packagingDraft" :disabled="!!pending" @change="changePackaging" @submit="saveSection('packaging')">
            <div class="flex justify-end">
              <n-button type="primary" attr-type="submit" :loading="pending === 'packaging'" :disabled="!!pending">
                {{ t('保存包装与物流') }}
              </n-button>
            </div>
          </ProductFields>
        </section>
      </n-tab-pane>

      <n-tab-pane v-for="key in relatedTabs" :key="key" :name="key" :tab="t(relatedDefinitions[key].label)" :tab-props="tabControl(key)" display-directive="if">
        <div class="mb-12 flex items-center justify-between">
          <h3 class="font-medium">
            {{ t(relatedDefinitions[key].label) }}
          </h3>
          <n-button type="primary" size="small" :disabled="!!pending" @click="openEditor(key)">
            {{ t('新增') }}
          </n-button>
        </div>
        <ProductRelatedTable
          :product-id="product.id"
          :version="product.version"
          :tab="key"
          :busy="!!pending"
          :sku-options="skuOptions"
          @edit="openEditor(key, $event)"
        />
      </n-tab-pane>
    </n-tabs>
  </div>

  <n-modal
    v-if="editor"
    :show="true"
    preset="card"
    :title="editorTitle"
    :mask-closable="false"
    :close-on-esc="!pending"
    :closable="!pending"
    style="width: min(660px, calc(100vw - 32px))"
    @update:show="visible => !visible && closeEditor()"
  >
    <n-alert v-if="recordError" type="error" class="mb-12">
      {{ t(recordError) }}
      <n-button v-if="recordError === conflictMessage" text class="ml-8" @click="reloadAfterConflict">
        {{ t('放弃未保存修改并重新加载') }}
      </n-button>
    </n-alert>
    <ProductFields
      :fields="relatedDefinitions[editor.tab].fields"
      :values="editor.values"
      :sku-options="skuOptions"
      :disabled="!!pending"
      @change="changeRecord"
      @submit="saveRecord"
    >
      <n-space justify="end">
        <n-button :disabled="!!pending" @click="closeEditor">
          {{ t('取消') }}
        </n-button>
        <n-button type="primary" attr-type="submit" :loading="pending === 'record'" :disabled="!!pending">
          {{ t('保存') }}
        </n-button>
      </n-space>
    </ProductFields>
  </n-modal>
</template>

<script setup lang="ts">
import type { ProductDetail, ProductRecord, ProductValues, RelatedTab } from './model'
import { computed, ref, useId, watch } from 'vue'
import { t } from '@/i18n'
import { workbenchError } from '../model'
import { useWorkbenchRequest } from '../useWorkbench'
import api from './api'
import { basicFields, formValues, packagingFields, productStatuses, relatedDefinitions, relatedTabs } from './model'
import ProductFields from './ProductFields.vue'
import ProductRelatedTable from './ProductRelatedTable.vue'

const props = defineProps<{ id: number }>()
const emit = defineEmits<{ saved: [], busy: [value: boolean] }>()

type Section = 'basic' | 'packaging'
type DetailTab = RelatedTab | 'basic'
interface RowEditor { tab: RelatedTab, id?: number, values: ProductValues }

const conflictMessage = '数据已变化，请刷新后重试'
const basicTitleId = useId()
const packagingTitleId = useId()
const read = useWorkbenchRequest<ProductDetail | null>(null)
const { data: product, error: loadError, loading } = read
const tab = ref<DetailTab>('basic')
const basicDraft = ref<ProductValues>({})
const packagingDraft = ref<ProductValues>({})
const editor = ref<RowEditor>()
const pending = ref<Section | 'record'>()
const sectionError = ref('')
const recordError = ref('')

const skuOptions = computed(() => (product.value?.records.skus ?? []).map(({ id, values }) => ({
  value: id,
  label: `${values.code} · ${values.specification}`,
})))
const editorTitle = computed(() => editor.value
  ? t(editor.value.id === undefined
      ? '新增{0}'
      : '编辑{0}', t(relatedDefinitions[editor.value.tab].label))
  : '')

watch(() => props.id, load, { immediate: true })

async function load() {
  read.reset()
  const id = props.id
  const result = await read.run(() => api.detail(id))
  if (!result)
    return
  basicDraft.value = formValues(basicFields, result)
  packagingDraft.value = formValues(packagingFields, result.packaging)
}

function reloadAfterConflict() {
  sectionError.value = ''
  recordError.value = ''
  closeEditor()
  return load()
}

function tabControl(key: DetailTab) {
  return {
    'role': 'button',
    'tabindex': 0,
    'aria-pressed': tab.value === key,
    onKeydown(event: KeyboardEvent) {
      if (event.key === ' ' || event.key === 'Enter') {
        event.preventDefault()
        tab.value = key
      }
    },
  }
}

function changeBasic(key: string, value: ProductValues[string]) {
  basicDraft.value[key] = value
}

function changePackaging(key: string, value: ProductValues[string]) {
  packagingDraft.value[key] = value
}

function changeRecord(key: string, value: ProductValues[string]) {
  if (editor.value)
    editor.value.values[key] = value
}

function openEditor(key: RelatedTab, row?: ProductRecord) {
  recordError.value = ''
  editor.value = {
    tab: key,
    id: row?.id,
    values: formValues(relatedDefinitions[key].fields, row?.values),
  }
}

function closeEditor() {
  if (!pending.value)
    editor.value = undefined
}

async function persist(
  target: Section | 'record',
  update: (current: ProductDetail) => Promise<{ data: ProductDetail }>,
) {
  const current = product.value
  if (!current || pending.value)
    return undefined

  const error = target === 'record'
    ? recordError
    : sectionError
  error.value = ''
  pending.value = target
  emit('busy', true)
  try {
    const { data } = await update(current)
    if (current.id !== props.id)
      return undefined
    product.value = data
    emit('saved')
    window.$message.success(t('保存成功'))
    return data
  }
  catch (cause) {
    error.value = workbenchError(cause, t('保存失败，请重试'))
  }
  finally {
    pending.value = undefined
    emit('busy', false)
  }
}

async function saveSection(section: Section) {
  const draft = section === 'basic'
    ? basicDraft
    : packagingDraft
  const result = await persist(section, current => api.saveSection(current.id, section, draft.value, current.version))
  if (result) {
    draft.value = section === 'basic'
      ? formValues(basicFields, result)
      : formValues(packagingFields, result.packaging)
  }
}

async function saveRecord() {
  const row = editor.value
  if (!row)
    return
  const result = await persist('record', current => row.id === undefined
    ? api.addRecord(current.id, row.tab, row.values, current.version)
    : api.updateRecord(current.id, row.tab, row.id, row.values, current.version))
  if (result)
    editor.value = undefined
}
</script>

<style scoped>
.product-summary {
  display: flex;
  align-items: center;
  gap: 16px;
  overflow-x: auto;
  padding: 8px 0;
  white-space: nowrap;
}
</style>
