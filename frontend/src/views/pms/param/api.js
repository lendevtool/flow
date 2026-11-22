import { request } from '@/utils'

const base = '/param/groups'

export default {
  groups: params => request.get(base, { params, needTip: false }),
  groupOrder: () => request.get(`${base}/order`),
  sortGroups: ids => request.patch(`${base}/sort`, { ids }),
  group: id => request.get(`${base}/${id}`),
  createGroup: data => request.post(base, data),
  updateGroup: (id, data) => request.patch(`${base}/${id}`, data),
  deleteGroup: id => request.delete(`${base}/${id}`),
  items: (groupId, params) => request.get(`${base}/${groupId}/items`, { params, needTip: false }),
  item: (groupId, id) => request.get(`${base}/${groupId}/items/${id}`),
  createItem: (groupId, data) => request.post(`${base}/${groupId}/items`, data),
  updateItem: (groupId, id, data) => request.patch(`${base}/${groupId}/items/${id}`, data),
  deleteItem: (groupId, id) => request.delete(`${base}/${groupId}/items/${id}`),
  order: groupId => request.get(`${base}/${groupId}/items/order`),
  sort: (groupId, ids) => request.patch(`${base}/${groupId}/sort`, { ids }),
  refresh: () => request.post('/param/cache/refresh'),
}
