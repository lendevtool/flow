import { request } from '@/utils'

export default {
  logs: params => request.get('/audit/logs', { params, needTip: false }),
  detail: (id, params) => request.get(`/audit/logs/${id}`, { params, needTip: false }),
  options: () => request.get('/audit/options', { needTip: false }),
  status: () => request.get('/audit/status', { needTip: false }),
  queue: params => request.get('/audit/outbox', { params, needTip: false }),
  retry: (id, rowVersion) => request.post(`/audit/outbox/${id}/retry`, { rowVersion }, { needTip: false }),
  export: params => request.get('/audit/logs/export', { params, needTip: false }),
}
