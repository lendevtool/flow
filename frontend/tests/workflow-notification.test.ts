// @vitest-environment jsdom

import type { WorkflowDefinition } from '@/views/pms/workflow/api'
import { expect, it } from 'vitest'
import { fromWorkflowDefinition } from '@/views/pms/workflow/presentation'

const definition: WorkflowDefinition = {
  id: 29,
  revision: 2,
  version: 4,
  status: 'RETIRED',
  publishedAt: null,
  code: 'approval',
  name: 'Approval',
  fields: [
    { code: 'department' },
  ],
  nodes: [
    {
      key: 'review',
      parentKey: null,
      order: 1,
      title: 'Review',
      roleCode: 'REVIEWER',
      mode: 'PRIMARY',
      editableFields: null,
      requiredFields: [],
      dueOffsetDays: null,
      onApprovedSceneCode: 'workflow.review_complete',
      onRejectedSceneCode: null,
    },
  ],
}

it('copies the scene bindings and source version from a historical workflow definition', () => {
  const draft = fromWorkflowDefinition(definition, true)
  expect(draft.sourceDefinitionId).toBe(29)
  expect(draft.nodes[0]).toMatchObject({
    onApprovedSceneCode: 'workflow.review_complete',
    onRejectedSceneCode: null,
  })
  draft.nodes[0].onApprovedSceneCode = null
  expect(definition.nodes[0].onApprovedSceneCode).toBe('workflow.review_complete')
})

it('preserves omitted notification fields when editing a definition returned by an older server', () => {
  const oldNode = { ...definition.nodes[0] }
  delete oldNode.onApprovedSceneCode
  delete oldNode.onRejectedSceneCode
  const draft = fromWorkflowDefinition({
    ...definition,
    nodes: [oldNode],
  }, false)
  expect(draft.version).toBe(4)
  expect(draft.sourceDefinitionId).toBeUndefined()
  expect(draft.nodes[0]).not.toHaveProperty('onApprovedSceneCode')
  expect(draft.nodes[0]).not.toHaveProperty('onRejectedSceneCode')
})
