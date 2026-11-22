import { cloneDeep } from 'lodash-es'
import { t } from '@/i18n'
import { useForm, useModal } from '.'

const ACTIONS = {
  view: '查看{0}',
  edit: '编辑{0}',
  add: '新增{0}',
}

export function useCrud({ name, initForm = {}, doCreate, doDelete, doUpdate, refresh }) {
  const modalAction = ref('')
  const [modalRef, okLoading] = useModal()
  const [modalFormRef, modalForm, validation] = useForm(initForm)

  /** 新增 */
  function handleAdd(row = {}, title) {
    handleOpen({
      action: 'add',
      title,
      row: Object.assign({}, cloneDeep(initForm), cloneDeep(row)),
    })
  }

  /** 修改 */
  function handleEdit(row, title) {
    handleOpen({ action: 'edit', title, row })
  }

  /** 查看 */
  function handleView(row, title) {
    handleOpen({ action: 'view', title, row })
  }

  /** 打开modal */
  function handleOpen(options = {}) {
    const { action, row, title, onOk } = options
    modalAction.value = action
    modalForm.value = cloneDeep(row || {})
    modalRef.value?.open({
      ...options,
      async onOk() {
        if (typeof onOk === 'function') {
          return await onOk()
        }
        else {
          return await handleSave()
        }
      },
      title: title ?? (ACTIONS[action]
        ? t(ACTIONS[action], t(name))
        : t(name)),
    })
  }

  /** 保存 */
  async function handleSave(action) {
    if (!action && !['edit', 'add'].includes(modalAction.value)) {
      return false
    }
    await validation()
    const actions = {
      add: {
        api: () => doCreate(modalForm.value),
        cb: () => $message.success(t('新增成功')),
      },
      edit: {
        api: () => doUpdate(modalForm.value),
        cb: () => $message.success(t('保存成功')),
      },
    }

    action = action || actions[modalAction.value]

    try {
      okLoading.value = true
      const data = await action.api()
      action.cb()
      okLoading.value = false
      data && refresh(data)
    }
    catch (error) {
      console.error(error)
      okLoading.value = false
      return false
    }
  }

  /** 删除 */
  function handleDelete(id, confirmOptions) {
    if (!id && id !== 0)
      return
    const d = $dialog.warning({
      content: t('确定删除？'),
      title: t('提示'),
      positiveText: t('确定'),
      negativeText: t('取消'),
      async onPositiveClick() {
        try {
          d.loading = true
          const data = await doDelete(id)
          $message.success(t('删除成功'))
          d.loading = false
          refresh(data, true)
        }
        catch (error) {
          console.error(error)
          d.loading = false
        }
      },
      ...confirmOptions,
    })
  }

  return {
    modalRef,
    modalFormRef,
    modalAction,
    modalForm,
    okLoading,
    validation,
    handleAdd,
    handleDelete,
    handleEdit,
    handleView,
    handleOpen,
    handleSave,
  }
}
