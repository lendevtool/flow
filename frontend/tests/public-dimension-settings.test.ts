import { beforeEach, expect, it, vi } from 'vitest'
import paramApi from '../src/views/pms/param/api'
import { loadPublicDimensionSettings, savePublicDimension } from '../src/views/workbench/publicDimensionSettings'

vi.mock('../src/views/pms/param/api', () => ({
  default: {
    groups: vi.fn(),
    items: vi.fn(),
    item: vi.fn(),
    createGroup: vi.fn(),
    createItem: vi.fn(),
    updateItem: vi.fn(),
  },
}))

const slot = {
  id: 10,
  code: 'DIMENSION_1',
  name: '维度 1',
  nameEn: 'Dimension 1',
  enable: false,
  sort: 1,
  value1: null,
}
const option = {
  id: 20,
  code: 'MARKET',
  name: '市场',
  nameEn: 'Market',
  enable: true,
  sort: 1,
  value2: 'legacy',
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(paramApi.groups).mockResolvedValue({
    data: {
      pageData: [
        { id: 1, code: 'WORKBENCH_TODO_DIMENSION' },
      ],
      total: 1,
    },
  })
})

it('updates an existing option by ID without losing parameter fields', async () => {
  const bound = {
    ...slot,
    value1: '2',
    extra: { bindingLocked: true },
  }
  vi.mocked(paramApi.items).mockImplementation(async groupId => ({
    data: {
      pageData: groupId === 1
        ? [bound]
        : [option],
      total: 1,
    },
  }))
  vi.mocked(paramApi.item).mockImplementation(async groupId => ({
    data: groupId === 1
      ? bound
      : { ...option, value2: 'updated elsewhere' },
  }))
  vi.mocked(paramApi.updateItem).mockResolvedValue({
    data: { ...option, enable: false },
  })

  const [draft] = await loadPublicDimensionSettings()
  draft.options[0].enable = false
  await savePublicDimension(draft)

  expect(paramApi.updateItem).toHaveBeenCalledExactlyOnceWith(2, 20, expect.objectContaining({
    code: 'MARKET',
    nameEn: 'Market',
    enable: false,
    value2: 'updated elsewhere',
  }))
  expect(paramApi.createGroup).not.toHaveBeenCalled()
})

it('binds a new dictionary and reuses saved options after a partial failure', async () => {
  const bound = { ...slot, value1: '2' }
  vi.mocked(paramApi.items).mockResolvedValue({
    data: {
      pageData: [slot],
      total: 1,
    },
  })
  vi.mocked(paramApi.item).mockResolvedValueOnce({ data: slot }).mockResolvedValue({ data: bound })
  vi.mocked(paramApi.createGroup).mockResolvedValue({
    data: { id: 2 },
  })
  vi.mocked(paramApi.updateItem).mockResolvedValueOnce({ data: bound }).mockResolvedValue({
    data: { ...bound, enable: true },
  })
  vi.mocked(paramApi.createItem)
    .mockResolvedValueOnce({
      data: {
        ...option,
        code: 'A',
        name: '市场',
        nameEn: '',
      },
    })
    .mockRejectedValueOnce(new Error('write failed'))
    .mockResolvedValueOnce({
      data: {
        ...option,
        id: 21,
        code: 'B',
        name: '产品',
        nameEn: '',
      },
    })

  const [draft] = await loadPublicDimensionSettings()
  draft.enable = true
  draft.options.push(
    {
      code: 'A',
      name: '市场',
      nameEn: '',
      enable: true,
    },
    {
      code: 'B',
      name: '产品',
      nameEn: '',
      enable: true,
    },
  )
  await expect(savePublicDimension(draft)).rejects.toThrow('write failed')
  await savePublicDimension(draft)

  expect(paramApi.createGroup).toHaveBeenCalledTimes(1)
  expect(vi.mocked(paramApi.createItem).mock.calls.map(([, payload]) => payload.code)).toEqual(['A', 'B', 'B'])
  expect(paramApi.updateItem).toHaveBeenNthCalledWith(1, 1, 10, expect.objectContaining({ value1: '2', enable: false }))
  expect(paramApi.updateItem).toHaveBeenNthCalledWith(2, 1, 10, expect.objectContaining({ value1: '2', enable: true }))
})
