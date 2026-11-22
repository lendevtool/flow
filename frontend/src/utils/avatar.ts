const legacyDefaultAvatar = 'https://wpimg.wallstcn.com/f778738c-e4f8-4870-b634-56703b4acafe.gif?imageView2/1/w/80/h/80'

/** 旧版系统头像由前端图标替代，保留用户设置的头像链接。 */
export function avatarImageSrc(value?: string | null): string | undefined {
  const url = value?.trim()
  return url && url !== legacyDefaultAvatar
    ? url
    : undefined
}
