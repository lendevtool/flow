import { request } from '@/utils'

export default {
  updateProfile: data => request.patch(`/user/profile/${data.id}`, data),
}
