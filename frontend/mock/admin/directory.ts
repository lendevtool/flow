import type { Department } from '../../src/views/pms/department/api'
import { mockTime } from '../fixtures.ts'
import { MockError, object } from '../protocol.ts'
import { demoRole, demoUser } from '../session.ts'
import { collection, ids, number } from './common.ts'

export interface MockRole {
  id: number
  code: string
  name: string
  nameEn: string
  enable: boolean
  permissionIds: number[]
  primaryUserId: number | null
  backupUserIds: number[]
}

export interface MockUser {
  id: number
  username: string
  nameCn: string
  nameEn: string
  enable: boolean
  domainAccount: string
  mobile: string
  gender: number | null
  avatar: string | null
  createTime: string
  updateTime: string
  roleIds: number[]
  departmentIds: number[]
  departmentId: number | null
  departmentVersion: number
  loginMethod: string
}

export function createDirectory() {
  const roles = collection<MockRole>([
    {
      ...demoRole,
      enable: true,
      permissionIds: [],
      primaryUserId: 1,
      backupUserIds: [],
    },
    {
      id: 2,
      code: 'DEMO_REVIEWER',
      name: '示例审批角色',
      nameEn: 'Demo Reviewer',
      enable: true,
      permissionIds: [],
      primaryUserId: 2,
      backupUserIds: [],
    },
  ])
  const time = mockTime(-7)
  const users = collection<MockUser>([
    {
      id: demoUser.id,
      username: demoUser.username,
      nameCn: demoUser.name,
      nameEn: demoUser.nameEn,
      enable: true,
      domainAccount: '',
      mobile: '',
      gender: null,
      avatar: null,
      createTime: time,
      updateTime: time,
      roleIds: [demoRole.id],
      departmentIds: [2],
      departmentId: 2,
      departmentVersion: 1,
      loginMethod: 'LOCAL',
    },
    {
      id: 2,
      username: 'demo.reviewer',
      nameCn: '示例审批人',
      nameEn: 'Demo Reviewer',
      enable: true,
      domainAccount: '',
      mobile: '',
      gender: null,
      avatar: null,
      createTime: time,
      updateTime: time,
      roleIds: [2],
      departmentIds: [3],
      departmentId: 3,
      departmentVersion: 1,
      loginMethod: 'LOCAL',
    },
  ])
  const departments = collection<Department>([
    {
      id: 1,
      parentId: null,
      name: '示例公司',
      enabled: true,
      sortOrder: 1,
      leaderUserId: null,
      leaderName: null,
    },
    {
      id: 2,
      parentId: 1,
      name: '综合管理部',
      enabled: true,
      sortOrder: 1,
      leaderUserId: 1,
      leaderName: demoUser.name,
    },
    {
      id: 3,
      parentId: 1,
      name: '流程运营部',
      enabled: true,
      sortOrder: 2,
      leaderUserId: 2,
      leaderName: '示例审批人',
    },
  ])
  function departmentRef(id: number) {
    const { name } = departments.get(id)
    return { id, name }
  }
  function memberships(user: MockUser) {
    return {
      primaryDepartmentId: user.departmentId,
      departmentVersion: user.departmentVersion,
      departments: user.departmentIds.map(departmentRef),
    }
  }
  function membershipWrite(value: unknown, user?: MockUser) {
    const input = object(value)
    if (user && input.expectedVersion !== user.departmentVersion)
      throw new MockError(409, '部门归属已变化，请刷新后重试')
    const departmentIds = ids(input.departmentIds)
    departmentIds.forEach(id => departments.get(id))
    const departmentId = number(input.primaryDepartmentId, 0) || null
    if (departmentIds.length && (departmentId === null || !departmentIds.includes(departmentId)))
      throw new MockError(400, '请选择有效的主部门')
    return {
      departmentIds,
      departmentId: departmentIds.length ? departmentId : null,
      departmentVersion: (user?.departmentVersion ?? 0) + 1,
    }
  }
  function present(user: MockUser) {
    return {
      ...user,
      departmentName: user.departmentId === null
        ? null
        : departmentRef(user.departmentId).name,
      departments: user.departmentIds.map(departmentRef),
      roles: user.roleIds.filter(roles.has).map(roles.get),
    }
  }
  function clearLeadership(userId: number) {
    for (const department of departments.rows().filter(row => row.leaderUserId === userId)) {
      department.leaderUserId = null
      department.leaderName = null
    }
    for (const role of roles.rows()) {
      if (role.primaryUserId === userId)
        role.primaryUserId = null
      role.backupUserIds = role.backupUserIds.filter(id => id !== userId)
    }
  }
  return {
    users,
    roles,
    departments,
    present,
    memberships,
    membershipWrite,
    departmentRef,
    clearLeadership,
  }
}

export type MockDirectory = ReturnType<typeof createDirectory>
