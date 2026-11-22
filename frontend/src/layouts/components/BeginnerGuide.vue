<template>
  <n-tooltip trigger="hover">
    <template #trigger>
      <i
        class="i-fe:beginner mr-16 cursor-pointer text-20"
        @click="show = true"
      />
    </template>
    {{ t('操作指引') }}
  </n-tooltip>

  <Vue3IntroStep
    ref="myIntroStep"
    v-model:show="show"
    :config="config"
  >
    <template #prev="{ tipItem, index }">
      <NButton class="mr-12" type="primary" color="#fff" text-color="#fff" ghost round size="small" @click="prev(tipItem, index)">
        {{ t('上一步') }}
      </NButton>
    </template>
    <template #next="{ tipItem }">
      <NButton class="mr-12" type="primary" color="#fff" text-color="#fff" ghost round size="small" @click="next(tipItem)">
        {{ t('下一步') }}
      </NButton>
    </template>

    <template #skip>
      <NButton type="primary" color="#fff" text-color="#fff" ghost round size="small" @click="skip">
        {{ t('跳过') }}
      </NButton>
    </template>

    <template #done>
      <NButton type="primary" color="#fff" text-color="#fff" ghost round size="small" @click="done">
        {{ t('完成') }}
      </NButton>
    </template>
  </Vue3IntroStep>
</template>

<script setup>
import Vue3IntroStep from 'vue3-intro-step'
import { t } from '@/i18n'
import { useAppStore } from '@/store'

const appStore = useAppStore()
const myIntroStep = shallowRef(null)
const show = shallowRef(false)
const config = computed(() => ({
  backgroundOpacity: 0.8,
  titleStyle: {
    textAlign: 'left',
    fontSize: '18px',
  },
  contentStyle: {
    textAlign: 'left',
    fontSize: '14px',
  },
  tips: [
    {
      el: '#toggleTheme',
      tipPosition: 'bottom',
      title: t('切换系统主题'),
      content: t('一键开启护眼模式'),
    },
    {
      el: '#fullscreen',
      tipPosition: 'bottom',
      title: t('全屏/退出全屏'),
      content: t('一键开启全屏'),
    },
    {
      el: '#theme-setting',
      tipPosition: 'bottom',
      title: t('设置主题色'),
      content: t('调整为你喜欢的主题色'),
    },
    {
      el: '#user-dropdown',
      tipPosition: 'bottom',
      title: t('个人中心'),
      content: t('查看个人资料和退出系统'),
    },
    {
      el: '#menu-collapse',
      tipPosition: 'bottom',
      title: t('展开/收起菜单'),
      content: t('一键展开/收起菜单'),
    },
    {
      el: '#top-tab',
      tipPosition: 'bottom',
      title: t('标签栏'),
      content: t('鼠标滚轮滑动可调整至最佳视野'),
    },
    {
      el: '#layout-setting',
      tipPosition: 'left',
      title: t('调整系统布局'),
      content: t('将系统布局调整为你喜欢的样子'),
    },
  ].filter(tip => tip.el !== '#layout-setting' || appStore.layoutSettingVisible),
}))

function skip() {
  show.value = false
}

function done() {
  show.value = false
}

function next() {
  // tipItem当前的提示项信息
  // 调用vue3-intro-step的next方法 手动触发下一步
  myIntroStep.value.next()
}
function prev() {
  // 调用vue3-intro-step的prev方法 手动触发上一步
  myIntroStep.value.prev()
}
</script>
