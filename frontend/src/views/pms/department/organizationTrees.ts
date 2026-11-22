import type { TreeOption } from 'naive-ui'
import type { Department, Member } from './api'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'

interface OrganizationNode extends TreeOption {
  key: string
  label: string
  children?: OrganizationNode[]
  userId?: number
  username?: string
  leader?: boolean
  inactive?: boolean
  departments?: string[]
}

interface DepartmentBranch {
  department: Department
  children: DepartmentBranch[]
  members: Member[]
}

interface PersonBranch {
  node: OrganizationNode
  reports: Map<number, PersonBranch>
}

export function buildOrganizationTrees(departments: Department[], members: Member[]) {
  const byUser = new Map(members.map(member => [member.id, member]))
  const byDepartment = new Map<number, DepartmentBranch>(departments.map(department => [
    department.id,
    { department, children: [], members: [] },
  ]))
  const roots: DepartmentBranch[] = []
  for (const branch of byDepartment.values()) {
    const parent = branch.department.parentId == null
      ? undefined
      : byDepartment.get(branch.department.parentId)
    const siblings = parent?.children ?? roots
    siblings.push(branch)
  }

  const unassigned: Member[] = []
  for (const member of members) {
    let assigned = false
    for (const { id } of member.departments) {
      const branch = byDepartment.get(id)
      if (branch) {
        branch.members.push(member)
        assigned = true
      }
    }
    if (!assigned)
      unassigned.push(member)
  }

  function person(key: string, userId: number, leaderOf?: Department): OrganizationNode {
    const member = byUser.get(userId)
    return {
      key,
      userId,
      label: member
        ? displayName(member.nameCn || member.username, member.nameEn)
        : leaderOf?.leaderName || String(userId),
      username: member?.username,
      inactive: member
        ? !member.enabled
        : false,
      leader: leaderOf != null,
    }
  }

  function organizationBranch({ department, children, members }: DepartmentBranch): OrganizationNode {
    const key = `department:${department.id}`
    const leaderId = department.leaderUserId
    return {
      key,
      label: department.name,
      inactive: !department.enabled,
      children: [
        ...(leaderId == null
          ? []
          : [
              person(`${key}:person:${leaderId}`, leaderId, department),
            ]),
        ...members.filter(member => member.id !== leaderId).map(({ id }) => person(`${key}:person:${id}`, id)),
        ...children.map(organizationBranch),
      ],
    }
  }

  const organization = roots.map(organizationBranch)
  if (unassigned.length) {
    organization.push({
      key: 'unassigned',
      label: t('未分配部门'),
      children: unassigned.map(({ id }) => person(`unassigned:person:${id}`, id)),
    })
  }

  const people = new Map<number, PersonBranch>()
  const leaderIds = new Set(departments.map(department => department.leaderUserId))
  function addPerson(level: Map<number, PersonBranch>, parentKey: string, userId: number, leaderOf?: Department) {
    let entry = level.get(userId)
    if (!entry) {
      entry = {
        node: person(`${parentKey}:${userId}`, userId, leaderOf),
        reports: new Map(),
      }
      level.set(userId, entry)
    }
    if (leaderOf) {
      entry.node.leader = true
      entry.node.departments ??= []
      entry.node.departments.push(leaderOf.name)
    }
    return entry
  }

  function assignReports(
    { department, children, members }: DepartmentBranch,
    level: Map<number, PersonBranch>,
    parentKey: string,
    ancestors: ReadonlyMap<number, PersonBranch>,
  ) {
    let reports = level
    let reportKey = parentKey
    let leaders = ancestors
    const leaderId = department.leaderUserId
    if (leaderId != null) {
      // 同一人兼任上级和下级负责人时，沿用已有节点，避免出现自己向自己汇报的路径。
      const existing = ancestors.get(leaderId)
      const manager = existing ?? addPerson(level, parentKey, leaderId, department)
      if (existing)
        existing.node.departments?.push(department.name)
      reports = manager.reports
      reportKey = manager.node.key
      if (!existing)
        leaders = new Map(ancestors).set(leaderId, manager)
    }
    for (const { id } of members) {
      if (!leaders.has(id))
        addPerson(reports, reportKey, id)
    }
    for (const child of children)
      assignReports(child, reports, reportKey, leaders)
  }

  for (const root of roots)
    assignReports(root, people, 'people', new Map())
  for (const { id } of unassigned) {
    if (!leaderIds.has(id))
      addPerson(people, 'people', id)
  }

  function toNodes(level: Map<number, PersonBranch>): OrganizationNode[] {
    return [
      ...level.values(),
    ].map(({ node, reports }) => ({
      ...node,
      label: node.departments?.length
        ? `${node.label} · ${node.departments.join('、')}`
        : node.label,
      children: reports.size
        ? toNodes(reports)
        : undefined,
    }))
  }

  return {
    organization,
    people: toNodes(people),
  }
}
