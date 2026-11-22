import { t } from '@/i18n'
import paramApi from '../pms/param/api'
import { itemPayload } from '../pms/param/fields'
import { newWorkbenchKey } from './model'

const slotGroupCode = 'WORKBENCH_TODO_DIMENSION'

interface ParamGroup {
  id: number
  code: string
}

interface ParamItem {
  id: number
  code: string
  name: string
  nameEn: string | null
  enable: boolean
  sort: number
  value1?: string | null
}

interface PageResponse<T> {
  data: { pageData: T[], total: number }
}

interface EditableNames {
  name: string
  nameEn: string
  enable: boolean
}

interface OptionDraft extends EditableNames {
  code: string
  parameter?: ParamItem
}

export interface PublicDimensionDraft extends EditableNames {
  slot: number
  groupId: number
  parameter: ParamItem
  newDictionaryCode?: string
  newDictionaryId?: number
  options: OptionDraft[]
}

async function readPages<T>(load: (page: { pageNo: number, pageSize: number }) => Promise<PageResponse<T>>): Promise<T[]> {
  const pageSize = 100
  const records: T[] = []
  for (let pageNo = 1; ; pageNo++) {
    const { data } = await load({ pageNo, pageSize })
    records.push(...data.pageData)
    if (pageNo * pageSize >= data.total)
      return records
  }
}

async function findGroup(code: string) {
  const groups = await readPages<ParamGroup>(page => paramApi.groups({ ...page, keyword: code }))
  return groups.find(group => group.code === code)
}

function editableNames(parameter: ParamItem): EditableNames {
  return { name: parameter.name, nameEn: parameter.nameEn ?? '', enable: parameter.enable }
}

export async function loadPublicDimensionSettings(): Promise<PublicDimensionDraft[]> {
  const group = await findGroup(slotGroupCode)
  if (!group)
    throw new Error(t('待办公共维度参数尚未初始化。'))

  const parameters = await readPages<ParamItem>(page => paramApi.items(group.id, page))
  const drafts = await Promise.all(parameters.filter(item => /^DIMENSION_[1-5]$/.test(item.code)).map(async (parameter) => {
    const options = parameter.value1
      ? await readPages<ParamItem>(page => paramApi.items(Number(parameter.value1), page))
      : []
    return {
      ...editableNames(parameter),
      slot: Number(parameter.code.slice(-1)),
      groupId: group.id,
      parameter,
      options: options.map(option => ({
        ...editableNames(option),
        code: option.code,
        parameter: option,
      })),
    }
  }))
  return drafts.sort((a, b) => a.slot - b.slot)
}

function normalizedNames(draft: EditableNames): EditableNames {
  return {
    name: draft.name.trim(),
    nameEn: draft.nameEn.trim(),
    enable: draft.enable,
  }
}

function namesChanged(parameter: ParamItem, names: EditableNames) {
  return parameter.name !== names.name || (parameter.nameEn ?? '') !== names.nameEn || parameter.enable !== names.enable
}

function checkConcurrentEdit(previous: ParamItem, current: ParamItem, names: EditableNames) {
  if (namesChanged(current, editableNames(previous)) && namesChanged(current, names))
    throw new Error(t('维度参数已被修改，请重新打开配置。'))
}

async function createOption(groupId: number, draft: OptionDraft, sort: number): Promise<ParamItem> {
  try {
    const { data } = await paramApi.createItem(groupId, {
      code: draft.code,
      ...normalizedNames(draft),
      sort,
    })
    return data
  }
  catch (cause) {
    // 请求结果不确定时，通过本次草稿的唯一编码查找已提交的选项。
    const items = await readPages<ParamItem>(page => paramApi.items(groupId, { ...page, keyword: draft.code })).catch(() => [])
    const existing = items.find(item => item.code === draft.code)
    if (!existing)
      throw cause
    return existing
  }
}

export async function savePublicDimension(draft: PublicDimensionDraft): Promise<void> {
  const names = normalizedNames(draft)
  const { data: current }: { data: ParamItem } = await paramApi.item(draft.groupId, draft.parameter.id)
  if ((current.value1 ?? null) !== (draft.parameter.value1 ?? null)
    && (!draft.newDictionaryId || current.value1 !== String(draft.newDictionaryId))) {
    throw new Error(t('维度绑定已变更，请重新打开配置。'))
  }
  checkConcurrentEdit(draft.parameter, current, names)
  draft.parameter = current

  if (!draft.parameter.value1 && draft.options.length) {
    draft.newDictionaryCode ??= `WORKBENCH_TODO_DICT_${newWorkbenchKey()}`
    if (!draft.newDictionaryId) {
      const code = draft.newDictionaryCode
      const existing = await findGroup(code)
      const dictionary: ParamGroup = existing ?? (await paramApi.createGroup({
        code,
        name: names.name,
        nameEn: names.nameEn,
        enable: true,
      })).data
      draft.newDictionaryId = dictionary.id
    }
    // 绑定时保持槽位停用，待选项保存后再应用启用状态。
    const { data } = await paramApi.updateItem(draft.groupId, current.id, itemPayload({
      ...current,
      value1: String(draft.newDictionaryId),
      enable: false,
    }))
    draft.parameter = data
  }

  const dictionaryId = Number(draft.parameter.value1)
  let sort = draft.options.reduce((max, option) => Math.max(max, option.parameter?.sort ?? 0), 0)
  for (const option of draft.options) {
    option.parameter ??= await createOption(dictionaryId, option, ++sort)
    const optionNames = normalizedNames(option)
    if (namesChanged(option.parameter, optionNames)) {
      const { data: latest }: { data: ParamItem } = await paramApi.item(dictionaryId, option.parameter.id)
      checkConcurrentEdit(option.parameter, latest, optionNames)
      if (!namesChanged(latest, optionNames)) {
        option.parameter = latest
        continue
      }
      const { data } = await paramApi.updateItem(dictionaryId, latest.id, itemPayload({
        ...latest,
        ...optionNames,
      }))
      option.parameter = data
    }
  }

  if (namesChanged(draft.parameter, names)) {
    const { data } = await paramApi.updateItem(draft.groupId, current.id, itemPayload({
      ...draft.parameter,
      ...names,
    }))
    draft.parameter = data
  }
}
