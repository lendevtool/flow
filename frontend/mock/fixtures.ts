import type { FieldDefinition } from '../src/views/pms/field/api'
import type { WorkflowDefinition, WorkflowNode } from '../src/views/pms/workflow/api'
import type { Todo } from '../src/views/workbench/api'
import { shiftDays } from '../src/utils/businessDate.ts'
import { newField } from '../src/views/pms/field/model.ts'
import { fieldExamples } from './field-fixtures.ts'
import { demoRole, demoUser } from './session.ts'

export function mockTime(dayOffset = 0) {
  const localTime = Date.now() + (8 + dayOffset * 24) * 60 * 60 * 1000
  return new Date(localTime).toISOString().replace('Z', '+08:00')
}

const summary = {
  ...newField('TEXT'),
  code: 'SUMMARY',
  name: '申请事由',
  nameEn: 'Request summary',
}

export function exampleFields(): FieldDefinition[] {
  const timestamp = mockTime(-7)
  return [summary, ...fieldExamples].map((field, index) => ({
    id: index + 1,
    config: {
      ...newField(field.dataType),
      ...structuredClone(field),
    },
    createdBy: demoUser.id,
    updatedBy: demoUser.id,
    createdAt: timestamp,
    updatedAt: mockTime(index % 7 - 6),
  }))
}

export function exampleDefinitions(): WorkflowDefinition[] {
  const node = (key: string, title: string, order: number, editable: string[]): WorkflowNode => ({
    key,
    title,
    order,
    parentKey: null,
    roleCode: demoRole.code,
    mode: 'PRIMARY',
    editableFields: editable,
    requiredFields: editable,
    dueOffsetDays: 1,
  })
  const published = (id: number, code: string, name: string, nodes: WorkflowNode[]): WorkflowDefinition => ({
    id,
    code,
    name,
    revision: 1,
    version: 1,
    status: 'PUBLISHED',
    // 模板接口返回 UTC 的 LocalDateTime，页面格式化时会补上 Z。
    publishedAt: new Date(Date.now() - 7 * 86_400_000).toISOString().slice(0, -1),
    fields: [
      { code: summary.code, config: summary, options: [] },
    ],
    nodes,
  })
  return [
    published(1, 'EXPENSE_DEMO', '费用报销', [
      node('fill', '填写报销申请', 1, ['SUMMARY']),
      node('review', '审批报销申请', 2, []),
    ]),
    published(2, 'PURCHASE_DEMO', '采购申请', [
      node('fill', '填写采购申请', 1, ['SUMMARY']),
      node('review', '审批采购申请', 2, []),
    ]),
  ]
}

export function baseTodo(id: number, title: string, createdAt: string, dueOffset: number): Todo {
  return {
    id,
    title,
    description: null,
    origin: 'MANUAL',
    priority: 'NORMAL',
    status: 'OPEN',
    ownerUserId: demoUser.id,
    ownerName: demoUser.name,
    assigneeUserId: demoUser.id,
    assigneeName: demoUser.name,
    sourceModule: null,
    businessType: null,
    businessId: null,
    sourceLabel: null,
    sourceUrl: null,
    scheduleMode: 'FIXED',
    startDate: null,
    endDate: null,
    dueDate: shiftDays(createdAt.slice(0, 10), dueOffset),
    dimensions: {},
    personalDimensions: {},
    seriesId: null,
    version: 1,
    createdAt,
    closedAt: null,
    allowedActions: [],
  }
}
