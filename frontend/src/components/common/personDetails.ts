import { shallowRef } from 'vue'

interface PersonTarget {
  id: string
  label: string
}

export const personTarget = shallowRef<PersonTarget | null>(null)

export function openPersonDetails(id: number | string, label: string) {
  personTarget.value = {
    id: String(id),
    label,
  }
}

export function closePersonDetails() {
  personTarget.value = null
}
