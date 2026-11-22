import { request } from '@/utils'

export default {
  toggleRole: data => request.post('/auth/role/toggle', data),
  login: data => request.post('/auth/login', data, { needToken: false }),
  options: () => request.get('/auth/login/options', { needToken: false, needTip: false }),
  logout: () => request.post('/auth/logout', {}, { needTip: false }),
}
