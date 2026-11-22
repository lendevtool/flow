<template>
  <n-descriptions label-placement="left" :column="1" bordered>
    <n-descriptions-item :label="t('查询参数')">
      <ul v-if="parameters.length" class="m-0 list-none p-0 space-y-4">
        <li v-for="parameter in parameters" :key="parameter.name">
          <code class="break-all">{{ parameter.name }}</code>
          <span class="ml-8">{{ parameter.format || parameter.type }} · {{ t(parameter.required ? '必填' : '可选') }}</span>
        </li>
      </ul>
      <span v-else>{{ t('无查询参数') }}</span>
    </n-descriptions-item>
    <n-descriptions-item :label="t('响应结构')">
      <span v-if="page">{{ t('分页对象（pageData、total）') }}</span>
      <span v-else>{{ t(collection ? '对象列表' : '单个对象') }}</span>
      <span v-if="wholeResponse"> · {{ t('使用来源接口的完整响应') }}</span>
    </n-descriptions-item>
    <n-descriptions-item v-if="fields && !wholeResponse" :label="t('响应字段')">
      <ul class="m-0 list-none p-0 space-y-4">
        <li v-for="field in fields" :key="field.name">
          <code class="break-all">{{ field.name }}</code>
          <span class="ml-8">{{ field.format || field.type }}{{ field.nullable ? ` · ${t('可为空')}` : '' }}</span>
        </li>
      </ul>
    </n-descriptions-item>
  </n-descriptions>
</template>

<script setup lang="ts">
import type { SourceField, SourceParameter } from '@/api/open-api/api'
import { t } from '@/i18n'

defineProps<{ parameters: SourceParameter[], fields?: SourceField[], collection: boolean, page?: boolean, wholeResponse?: boolean }>()
</script>
