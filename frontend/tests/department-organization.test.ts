import type { Department, Member } from '@/views/pms/department/api'
import { describe, expect, it } from 'vitest'
import { buildOrganizationTrees } from '@/views/pms/department/organizationTrees'

function department(id: number, name: string, parentId: number | null, leaderUserId: number | null): Department {
  return {
    id,
    name,
    parentId,
    leaderUserId,
    leaderName: null,
    sortOrder: id,
    enabled: true,
  }
}

function member(id: number, name: string, departments: Department[]): Member {
  return {
    id,
    username: name,
    nameCn: name,
    nameEn: null,
    departmentId: departments[0]?.id ?? null,
    departmentName: departments[0]?.name ?? null,
    departmentVersion: 0,
    departments: departments.map(({ id, name }) => ({ id, name })),
    matchedDepartments: [],
    enabled: true,
  }
}

const research = department(10, '研发部', null, 1)
const platform = department(11, '平台组', 10, 2)
const tools = department(12, '工具组', 11, null)
const operations = department(13, '运维组', 11, 2)
const departments = [research, platform, tools, operations]
const members = [
  member(1, '张主管', []),
  member(2, '李主管', [research]),
  member(3, '王同事', [tools, platform]),
  member(4, '赵同事', []),
  member(5, '钱同事', [operations]),
]

describe('organization tree views', () => {
  it('shows primary and secondary memberships under their departments and includes external leaders', () => {
    const { organization } = buildOrganizationTrees(departments, members)
    expect(organization).toMatchObject([
      {
        label: '研发部',
        children: [
          { userId: 1, leader: true },
          { userId: 2, leader: false },
          {
            label: '平台组',
            children: [
              { userId: 2, leader: true },
              { userId: 3, leader: false },
              {
                label: '工具组',
                children: [
                  { userId: 3, leader: false },
                ],
              },
              {
                label: '运维组',
                children: [
                  { userId: 2, leader: true },
                  { userId: 5, leader: false },
                ],
              },
            ],
          },
        ],
      },
      {
        label: '未分配部门',
        children: [
          { userId: 1 },
          { userId: 4 },
        ],
      },
    ])
  })

  it('puts people under their leaders without self reports or duplicate reports to the same leader', () => {
    const { people } = buildOrganizationTrees(departments, members)
    expect(people).toMatchObject([
      {
        userId: 1,
        leader: true,
        children: [
          {
            userId: 2,
            leader: true,
            children: [
              { userId: 3 },
              { userId: 5 },
            ],
          },
        ],
      },
      { userId: 4, leader: false },
    ])
  })
})
