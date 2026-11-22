import { describe, expect, it } from 'vitest'
import { approvalSelectOptions, changeWrite, institutionSelectOptions, institutionWrite, routeId } from '@/views/business/institutions/model'

const options = [
  {
    id: 1,
    code: 'BANK',
    name: '银行',
    nameEn: 'Bank',
    enabled: true,
  },
  {
    id: 2,
    code: 'LEGACY',
    name: '历史项',
    nameEn: null,
    enabled: false,
  },
  {
    id: 3,
    code: 'RETIRED',
    name: '停用项',
    nameEn: null,
    enabled: false,
  },
]

describe('institution filters and writes', () => {
  it('offers approval enum codes that the live dictionary and published template both allow', () => {
    const current = [
      ...options,
      {
        id: 4,
        code: 'UNPUBLISHED',
        name: '新分类',
        nameEn: null,
        enabled: true,
      },
    ]
    expect(approvalSelectOptions(current, ['BANK', 'LEGACY']).map(option => option.value)).toEqual(['BANK'])
  })

  it('includes historical filter values and preserves only the existing inactive choice when editing', () => {
    expect(institutionSelectOptions(options).map(option => option.value)).toEqual([1, 2, 3])
    expect(institutionSelectOptions(options, null).map(option => option.value)).toEqual([1])
    expect(institutionSelectOptions(options, 2).map(option => option.value)).toEqual([1, 2])
  })

  it('sends dictionary IDs and explicit clears with the loaded version', () => {
    expect(institutionWrite({
      code: '  ACME ',
      nameZh: ' 金融机构 ',
      nameEn: '',
      typeItemId: 1,
      countryRegionItemId: 4,
      registrationId: '',
      shortNameZh: '',
      shortNameEn: '',
      officeAddress: '',
      website: '',
      remark: '  desk  ',
    }, 0)).toEqual({
      code: 'ACME',
      nameZh: '金融机构',
      nameEn: null,
      typeItemId: 1,
      countryRegionItemId: 4,
      registrationId: null,
      shortNameZh: null,
      shortNameEn: null,
      officeAddress: null,
      website: null,
      remark: 'desk',
      version: 0,
    })
  })

  it('sends just the selected approval codes and preserves an explicit optional clear', () => {
    expect(changeWrite(0, '  Rename and reclassify  ', [
      'institution_name_en',
      'institution_type',
    ], {
      institution_name_zh: '未勾选的值',
      institution_name_en: '  ',
      institution_type: 'BANK',
    })).toEqual({
      version: 0,
      reason: 'Rename and reclassify',
      fields: { institution_name_en: null, institution_type: 'BANK' },
    })
  })

  it('requires a single positive safe integer for each workflow source identifier', () => {
    expect(routeId('27')).toBe(27)
    for (const invalid of [
      ['27'],
      '0',
      '-1',
      '1.5',
      '1e2',
      '9007199254740992',
      null,
      undefined,
    ])
      expect(routeId(invalid)).toBeNull()
  })
})
