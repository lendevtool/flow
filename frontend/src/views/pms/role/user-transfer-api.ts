import type { DepartmentRef } from '../department/api'
import api from './api'

export interface TransferUser {
  id: number
  username: string
  nameCn?: string | null
  nameEn?: string | null
  departmentId?: number | null
  departmentName?: string | null
  departments?: DepartmentRef[]
}

interface UserFilter {
  roleId: number
  roleAssigned: boolean
  keyword: string
  pageNo: number
  pageSize: number
}

interface UserPage {
  pageData: TransferUser[]
  total: number
}

export function readTransferUsers(params: UserFilter, signal: AbortSignal): Promise<{ data: UserPage }> {
  return api.getAllUsers(params, { signal, needTip: false })
}
