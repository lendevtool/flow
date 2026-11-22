import { request } from '@/utils'

interface Response<T> {
  data: T
}

export interface Department {
  id: number
  parentId: number | null
  name: string
  sortOrder: number
  enabled: boolean
  leaderUserId: number | null
  leaderName: string | null
}

export interface DepartmentRef {
  id: number
  name: string
}

export interface VersionedUser {
  userId: number
  expectedVersion: number
}

export type MemberAction = 'ADD' | 'REMOVE' | 'TRANSFER'

export interface MemberChange {
  action: MemberAction
  sourceDepartmentId?: number
  targetDepartmentId?: number
  users: VersionedUser[]
}

interface Memberships {
  primaryDepartmentId: number | null
  departmentVersion: number
  departments: DepartmentRef[]
}

export interface Member {
  id: number
  username: string
  avatar?: string | null
  nameCn: string | null
  nameEn: string | null
  departmentId: number | null
  departmentName: string | null
  departmentVersion: number
  departments: DepartmentRef[]
  matchedDepartments: DepartmentRef[]
  enabled: boolean
}

export type DepartmentForm = Pick<Department, 'parentId' | 'name' | 'sortOrder' | 'enabled'>

interface MemberParams {
  departmentId?: number
  unassigned?: boolean
  includeDescendants?: boolean
  keyword?: string
  enable?: boolean
  pageNo: number
  pageSize: number
}

interface MemberPage {
  pageData: Member[]
  total: number
}

export default {
  list: (signal?: AbortSignal): Promise<Response<Department[]>> => request.get('/department', { signal }),
  members: (params: MemberParams, signal?: AbortSignal): Promise<Response<MemberPage>> => request.get('/department/members', { params, signal }),
  candidates: (params: MemberParams, signal?: AbortSignal): Promise<Response<MemberPage>> => request.get('/department/users', { params, signal }),
  create: (data: DepartmentForm) => request.post('/department', data),
  update: (id: number, data: DepartmentForm) => request.put(`/department/${id}`, data),
  setLeader: (id: number, userId: number | null) => request.put(`/department/${id}/leader`, { userId }),
  changeMembers: (data: MemberChange) => request.post('/department/member-changes', data),
  memberships: (userId: number): Promise<Response<Memberships>> => request.get(`/department/users/${userId}/memberships`),
}
