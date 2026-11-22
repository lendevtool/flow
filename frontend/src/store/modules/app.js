import { generate, getRgbStr } from '@arco-design/color'
import { useColorMode } from '@vueuse/core'
import { defineStore } from 'pinia'
import { computed } from 'vue'
import { defaultLayout, defaultPrimaryColor, naiveThemeOverrides } from '@/settings'
import { updateFavicon } from '@/utils/favicon'

function readSessionAppearance() {
  try {
    // 升级前外观偏好保存在会话中，已有长期偏好会在 Pinia 恢复时覆盖这里的初始值。
    return JSON.parse(sessionStorage.getItem('app')) ?? {}
  }
  catch {
    return {}
  }
}

export const useAppStore = defineStore('app', {
  state: () => {
    const previous = readSessionAppearance()
    // 账号偏好在登录时恢复，其他标签页的浏览器缓存不能覆盖当前选择。
    const colorMode = useColorMode({ emitAuto: true, listenToStorageChanges: false })
    return {
      collapsed: false,
      colorMode,
      isDark: computed(() => colorMode.state.value === 'dark'),
      layout: ['simple', 'normal', 'full', 'empty'].includes(previous.layout)
        ? previous.layout
        : defaultLayout,
      tableSize: previous.tableSize ?? 'medium',
      primaryColor: previous.primaryColor ?? defaultPrimaryColor,
      loadingPalette: null,
      layoutSettingVisible: true,
      naiveThemeOverrides: {
        common: { ...naiveThemeOverrides.common },
      },
    }
  },
  actions: {
    switchCollapsed() {
      this.collapsed = !this.collapsed
    },
    setCollapsed(b) {
      this.collapsed = b
    },
    toggleDark() {
      this.colorMode = this.isDark ? 'light' : 'dark'
    },
    setLayout(v) {
      this.layout = v
    },
    setPrimaryColor(color) {
      this.primaryColor = color
    },
    setThemeColor(color = this.primaryColor, isDark = this.isDark) {
      const colors = generate(color, {
        list: true,
        dark: isDark,
      })
      if (this.loadingPalette?.source !== color) {
        // 首屏加载页在 Vue 启动前读取两种主色，以便自动模式跟随当前系统外观。
        const otherModeColors = generate(color, { list: true, dark: !isDark })
        this.loadingPalette = {
          source: color,
          light: isDark ? otherModeColors[5] : colors[5],
          dark: isDark ? colors[5] : otherModeColors[5],
        }
      }
      document.body.style.setProperty('--primary-color', getRgbStr(colors[5]))
      updateFavicon(getRgbStr(colors[5]))
      this.naiveThemeOverrides.common = Object.assign(this.naiveThemeOverrides.common || {}, {
        primaryColor: colors[5],
        primaryColorHover: colors[4],
        primaryColorSuppl: colors[4],
        primaryColorPressed: colors[6],
      })
    },
  },
  persist: [
    {
      pick: ['collapsed'],
      storage: sessionStorage,
    },
    {
      key: 'flow-appearance',
      pick: [
        'layout',
        'tableSize',
        'primaryColor',
        'loadingPalette',
        'layoutSettingVisible',
      ],
      afterHydrate: ({ store }) => store.$persist(),
    },
  ],
})
