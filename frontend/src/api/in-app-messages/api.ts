import { request } from '@/utils'

interface Response<T> { data: T }

export interface InAppMessage {
  id: number
  messageType: string
  title: string
  summary: string | null
  createdAt: string
  readAt: string | null
  targetUrl: string | null
}

interface MessagePage {
  items: InAppMessage[]
  nextBeforeId: number | null
}

interface MessageQuery {
  unread?: boolean
  pageSize: number
  beforeId?: number
}

const base = '/in-app-messages'

export default {
  list(params: MessageQuery, signal: AbortSignal): Promise<Response<MessagePage>> {
    const config = { params, signal, needTip: false }
    return request.get(base, config)
  },
  unreadCount(signal: AbortSignal): Promise<Response<{ count: number }>> {
    const config = { signal, needTip: false }
    return request.get(`${base}/unread-count`, config)
  },
  async markRead(id: number, signal: AbortSignal): Promise<void> {
    const config = { signal, needTip: false }
    await request.put(`${base}/${id}/read`, undefined, config)
  },
}
