import { defineStore } from 'pinia'
import { userDisplayName } from '@/i18n/names'

export const useUserStore = defineStore('user', {
  state: () => ({
    userInfo: null,
  }),
  getters: {
    userId() {
      return this.userInfo?.id
    },
    username() {
      return this.userInfo?.username
    },
    displayName() {
      return userDisplayName(this.userInfo)
    },
    nickName() {
      return this.userInfo?.nickName
    },
    avatar() {
      return this.userInfo?.avatar
    },
    currentRole() {
      return this.userInfo?.currentRole || {}
    },
    roles() {
      return this.userInfo?.roles || []
    },
  },
  actions: {
    setUser(user) {
      this.userInfo = user
    },
    updateNameEn(id, nameEn) {
      if (this.userInfo?.id === id)
        this.userInfo.nameEn = nameEn
    },
    updateRoleNames({ id, name, nameEn }) {
      for (const role of [...this.roles, this.currentRole]) {
        if (role.id === id)
          Object.assign(role, { name, nameEn })
      }
    },
    resetUser() {
      this.$reset()
    },
  },
})
