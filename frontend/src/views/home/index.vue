<template>
  <AppPage show-footer>
    <div class="flex">
      <n-card class="min-w-200 w-30%">
        <div class="flex items-center">
          <AccountAvatar round :size="60" :src="userStore.avatar" class="flex-shrink-0" />
          <div class="ml-20 flex-col">
            <span class="text-20 opacity-80">
              {{ t('你好，{0}', userStore.displayName) }}
            </span>
            <span class="mt-4 opacity-50">{{ t('当前角色：{0}', navigationLabel(userStore.currentRole?.code, userStore.currentRole?.name, userStore.currentRole?.nameEn)) }}</span>
          </div>
        </div>
      </n-card>
      <n-card class="ml-12 w-70%" :title="t('✨ 欢迎使用 Flow Admin')">
        <p class="opacity-60">
          {{ t('这是一款极简风格的后台管理模板，包含前后端解决方案，前端使用 Vite + Vue3 + Pinia + Unocss，后端使用 Java + MySql，简单易用，赏心悦目！！') }}
        </p>
      </n-card>
    </div>
    <div class="mt-12 flex">
      <n-card class="w-50%" :title="t('特性')" segmented>
        <template #header-extra>
          <span class="text-highlight opacity-90">👏👏👏</span>
        </template>

        <ul class="opacity-90">
          <li class="py-4">
            {{ t('🆒 使用') }}
            <b>Vue 3</b>
            {{ t('主流技术栈:') }}
            <span class="text-highlight">Vite + Vue 3 + Pinia</span>
          </li>
          <li class="py-4">
            {{ t('🍇 使用') }}
            <b>{{ t('原子CSS') }}</b>
            {{ t('框架:') }}
            <span class="text-highlight">UnoCSS</span>
            {{ t('，优雅、轻量、易用') }}
          </li>
          <li class="py-4">
            {{ t('🤹 使用主流的') }}
            <span class="text-highlight">Iconify + UnoCSS</span>
            {{ t('图标方案，支持自定义图标，支持动态渲染') }}
          </li>
          <li class="py-4">
            {{ t('🎨 使用 Naive UI，') }}
            <span class="text-highlight">{{ t('极致简洁的代码风格和清爽的页面设计') }}</span>
            {{ t('，审美在线，主题轻松定制') }}
          </li>
          <li class="py-4">
            {{ t('👏 先进且易于理解的文件结构设计，多个模块之间') }}
            <b>{{ t('零耦合') }}</b>
            {{ t('，单个业务模块删除不影响其他模块') }}
          </li>
          <li class="py-4">
            🚀
            <span class="text-highlight">{{ t('扁平化路由') }}</span>
            {{ t('设计，每一个组件都可以是一个页面，告别多级路由 KeepAlive 难实现问题') }}
          </li>

          <li class="py-4">
            🍒
            <span class="text-highlight">{{ t('基于权限动态生成路由') }}</span>
            {{ t('，无需额外定义路由，') }}
            <span class="text-highlight">{{ t('403和404可区分') }}</span>
            {{ t('，而不是无权限也跳404') }}
          </li>
        </ul>

        <n-divider class="mb-0! mt-12!" />
      </n-card>

      <n-card class="ml-12 w-50%" :title="t('🛠️ 技术栈')" segmented>
        <VChart :option="skillOption" autoresize />
      </n-card>
    </div>
  </AppPage>
</template>

<script setup>
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import * as echarts from 'echarts/core'
import { UniversalTransition } from 'echarts/features'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import AccountAvatar from '@/components/common/AccountAvatar.vue'
import { t } from '@/i18n'
import { navigationLabel } from '@/i18n/navigation'
import { useUserStore } from '@/store'

const userStore = useUserStore()

echarts.use([
  TooltipComponent,
  GridComponent,
  LegendComponent,
  BarChart,
  LineChart,
  CanvasRenderer,
  UniversalTransition,
  PieChart,
])

const skillOption = computed(() => ({
  tooltip: {
    trigger: 'item',
    formatter({ name, value }) {
      return `${name} ${value}%`
    },
  },
  legend: {
    left: 'center',
  },
  series: [
    {
      bottom: '12%',
      type: 'pie',
      radius: ['35%', '90%'],
      avoidLabelOverlap: true,
      itemStyle: {
        borderRadius: 10,
        borderColor: '#fff',
        borderWidth: 2,
      },
      label: {
        show: false,
        position: 'center',
      },
      emphasis: {
        label: {
          show: true,
          fontSize: 36,
          fontWeight: 'bold',
        },
      },
      labelLine: {
        show: false,
      },
      data: [
        { value: 38.0, name: 'Vue' },
        { value: 25.0, name: 'JavaScript' },
        { value: 10.0, name: 'TypeScript' },
        { value: 6.0, name: 'CSS' },
        { value: 6.0, name: 'HTML' },
        {
          value: 2.0,
          name: t('其他'),
        },
      ],
    },
  ],
}))
</script>
