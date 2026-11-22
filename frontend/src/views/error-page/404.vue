<template>
  <CommonPage :show-header="false">
    <div class="wh-full flex">
      <n-result
        m-auto
        status="404"
        :title="t('404 您访问的页面不存在')"
        :description="t('生活总归带点荒谬')"
        size="large"
      >
        <template #footer>
          <n-button type="primary" ghost @click="replace('/')">
            {{ t('返回首页') }}
          </n-button>
        </template>
      </n-result>
    </div>
  </CommonPage>
</template>

<script setup>
import { t } from '@/i18n'

const { replace } = useRouter()
</script>
