<template>
  <section class="text-13">
    <p class="font-medium">
      {{ t('密码规则') }}
    </p>
    <ul v-if="rules" class="mt-4 list-disc pl-20">
      <li>{{ t('使用 {0}–{1} 个字符', rules.minLength, rules.maxLength) }}</li>
      <li v-if="rules.requiredCharacterTypes.length">
        {{ t('每类至少包含一个字符：{0}', characterTypes) }}
      </li>
      <li>{{ t('不能仅含空白字符，也不能包含控制字符') }}</li>
      <li v-if="rules.rejectUsername">
        {{ t('不能与账号名相同（忽略大小写）') }}
      </li>
      <li v-if="rules.rejectCurrent">
        {{ t('不能与原密码相同') }}
      </li>
    </ul>
    <p v-else-if="loading" role="status">
      {{ t('正在读取密码规则') }}
    </p>
    <div v-else class="flex flex-wrap items-center gap-x-8">
      <p role="status">
        {{ t('暂时无法读取密码规则') }}
      </p>
      <n-button text type="primary" @click="loadRules">
        {{ t('重试') }}
      </n-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { PasswordCharacterType, PasswordRules } from '@/api/password'
import { computed, onMounted, ref } from 'vue'
import { passwordApi } from '@/api/password'
import { language, t } from '@/i18n'

const labels: Record<PasswordCharacterType, string> = {
  uppercase: '大写字母',
  lowercase: '小写字母',
  digit: '数字字符',
  special: '特殊字符',
}
const rules = ref<PasswordRules>()
const loading = ref(true)
const characterTypes = computed(() => {
  const values = rules.value?.requiredCharacterTypes.map(type => t(labels[type])) ?? []
  return new Intl.ListFormat(language.value).format(values)
})

async function loadRules() {
  loading.value = true
  try {
    rules.value = (await passwordApi.rules()).data
  }
  catch {
    rules.value = undefined
  }
  finally {
    loading.value = false
  }
}

onMounted(loadRules)
</script>
