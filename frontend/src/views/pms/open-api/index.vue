<template>
  <CommonPage :title="t('开放 API')">
    <template v-if="selectedAppId !== null" #title-prefix>
      <n-button text class="mr-16" @click="selectedAppId = null">
        <template #icon>
          <i class="i-material-symbols:arrow-left-alt" aria-hidden="true" />
        </template>
        {{ t('返回应用列表') }}
      </n-button>
    </template>
    <template #action>
      <n-button v-if="selectedAppId === null && tab === 'apps' && can.apps" type="primary" @click="openEditor(null)">
        <template #icon>
          <i class="i-material-symbols:add" aria-hidden="true" />
        </template>
        {{ t('新增应用') }}
      </n-button>
      <DocsDownloadButton v-if="selectedAppId === null && tab === 'endpoints'" :loading="downloading" @download="downloadDocs" />
    </template>

    <n-tabs v-show="selectedAppId === null" v-model:value="tab" type="line" class="open-api-tabs">
      <n-tab-pane name="apps" :tab="t('接入应用')" display-directive="show:lazy">
        <AppList
          ref="apps"
          :can-manage="can.apps"
          @select="selectedAppId = $event.id"
          @edit="openEditor"
          @change-status="confirmStatus"
        />
      </n-tab-pane>
      <n-tab-pane name="endpoints" :tab="t('接口目录')" display-directive="show:lazy">
        <EndpointList :can-publish="can.endpoints" :downloading="downloading" @download="downloadDocs" />
      </n-tab-pane>
    </n-tabs>
    <AppDetail
      v-if="selectedAppId !== null"
      :key="selectedAppId"
      ref="detail"
      :app-id="selectedAppId"
      :can="can"
      @edit="openEditor"
      @change-status="confirmStatus"
    />
    <AppEditor v-if="editor" :initial="editor.app" @close="editor = null" @saved="appSaved" />
  </CommonPage>
</template>

<script setup lang="ts">
import type { DocumentFormat } from './documentDownload'
import type { App } from '@/api/open-api/api'
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/api/open-api/api'
import CommonPage from '@/components/common/CommonPage.vue'
import { t } from '@/i18n'
import { useUserStore } from '@/store'
import AppDetail from './AppDetail.vue'
import AppEditor from './AppEditor.vue'
import AppList from './AppList.vue'
import DocsDownloadButton from './DocsDownloadButton.vue'
import { documentFile } from './documentDownload'
import EndpointList from './EndpointList.vue'
import { isConflict, openApiPermissions } from './model'

defineOptions({ name: 'OpenApiView' })

const tab = ref('apps')
const selectedAppId = ref<number | null>(null)
const apps = ref<InstanceType<typeof AppList>>()
const detail = ref<InstanceType<typeof AppDetail>>()
const editor = ref<{ app: App | null } | null>(null)
const downloading = ref(false)
const route = useRoute()
const user = useUserStore()
const can = computed(() => openApiPermissions(user.currentRole.code, route.meta.btns))

function openEditor(app: App | null) {
  editor.value = { app }
}

function appSaved(app: App, created: boolean) {
  editor.value = null
  window.$message.success(t('操作成功'))
  void apps.value?.reload()
  if (created)
    selectedAppId.value = app.id
  else if (app.id === selectedAppId.value)
    detail.value?.apply(app)
}

function confirmStatus(app: App) {
  const enabled = !app.enabled
  let saving = false
  window.$dialog.warning({
    title: t(enabled ? '启用应用' : '停用应用'),
    content: enabled
      ? t('启用「{0}」后，接入方需重新获取令牌。', app.name)
      : t('停用「{0}」将立即停止令牌签发，并使该应用的现有令牌失效。', app.name),
    positiveText: t(enabled ? '启用' : '停用'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      if (saving)
        return false
      saving = true
      try {
        const { data } = await api.appStatus(app.id, enabled, app.version)
        appSaved(data, false)
        return true
      }
      catch (error) {
        if (isConflict(error)) {
          void apps.value?.reload()
          if (app.id === selectedAppId.value)
            void detail.value?.reload()
          return true
        }
        return false
      }
      finally {
        saving = false
      }
    },
  })
}

async function downloadDocs(format: DocumentFormat) {
  if (downloading.value)
    return
  downloading.value = true
  try {
    const { data } = await api.docs()
    let file
    try {
      file = await documentFile(data, format)
    }
    catch {
      window.$message.error(t('文档下载失败'))
      return
    }
    const url = URL.createObjectURL(file.blob)
    const link = document.createElement('a')
    link.href = url
    link.download = file.name
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  catch {
    // 统一请求提示已处理下载错误。
  }
  finally {
    downloading.value = false
  }
}
</script>

<style scoped>
.open-api-tabs {
  height: 100%;
  min-height: 420px;
}

.open-api-tabs :deep(.n-tab-pane) {
  flex: 1;
  min-height: 0;
}
</style>
