import type { MfaKeyManagementSnapshot, MfaRotationStatus, PolicyConfig } from '../../src/views/pms/auth-policy/api'
import { mockTime } from '../fixtures.ts'
import { MockError, object } from '../protocol.ts'
import { at, checkVersion, route, text } from './common.ts'

const defaults: PolicyConfig = {
  mfa: { required: false, keyRotationDays: 90, keyReminderDays: 14 },
  captcha: { required: true, ttlSeconds: 60 },
  login: { localEnabled: true, domainEnabled: false },
  password: {
    minLength: 12,
    requiredCharacterTypes: [
      'uppercase',
      'lowercase',
      'digit',
      'special',
    ],
    rejectUsername: true,
    rejectCurrent: true,
    maxAgeSeconds: 0,
    temporaryTtlSeconds: 86400,
  },
  bootstrap: { requirePasswordChange: true },
  session: { idleTimeoutSeconds: 1800, absoluteTimeoutSeconds: 28800, allowConcurrent: true },
  rateLimit: {
    enabled: true,
    windowSeconds: 60,
    loginPerIp: 30,
    loginPerAccount: 10,
    captchaPerIp: 30,
    changePasswordPerAccount: 5,
  },
}

export function authPolicyRoutes() {
  const base = '/system-policy/auth-security'
  const snapshot = {
    code: 'AUTH_SECURITY',
    revision: 1,
    schemaVersion: 1,
    config: structuredClone(defaults),
    defaults,
    updatedAt: mockTime(-5),
    updatedBy: null as number | null,
  }
  const keys: MfaKeyManagementSnapshot = {
    revision: 1,
    configured: true,
    targetKeyId: 'mock-initial',
    lastPublishedAt: mockTime(-5),
    versions: [
      {
        id: 'mock-initial',
        status: 'PUBLISHED',
        eligible: true,
        createdAt: mockTime(-5),
        publishedAt: mockTime(-5),
      },
    ],
  }
  const rotation: MfaRotationStatus = {
    activeKeyId: 'mock-initial',
    targetKeyId: 'mock-initial',
    activeSince: mockTime(-5),
    lastCompletedAt: null,
    nextRotationAt: mockTime(85),
    reminderAt: mockTime(71),
    reminderDue: false,
    overdue: false,
    status: 'IDLE',
    errorCode: null,
    pendingBindings: 0,
    canStart: false,
  }
  function save(config: PolicyConfig) {
    Object.assign(snapshot, {
      config,
      revision: snapshot.revision + 1,
      updatedAt: mockTime(),
      updatedBy: 1,
    })
    return snapshot
  }
  return [
    route('GET', base, () => snapshot),
    route('PUT', base, (request) => {
      checkVersion(request, snapshot.revision, 'revision')
      const input = object(request.body.config)
      const config = structuredClone(defaults)
      for (const [group, fields] of Object.entries(config)) {
        const values = object(input[group] ?? {})
        for (const [field, value] of Object.entries(fields)) {
          const next = values[field]
          if (Array.isArray(value)) {
            if (Array.isArray(next))
              fields[field] = value.filter(item => next.includes(item))
          }
          else if (typeof next === typeof value && (typeof next === 'number' || typeof next === 'boolean')) {
            fields[field] = next
          }
        }
      }
      if (!config.login.localEnabled && !config.login.domainEnabled)
        throw new MockError(400, '至少启用一种登录方式')
      return save(config)
    }),
    route('POST', `${base}/reset`, (request) => {
      checkVersion(request, snapshot.revision, 'revision')
      return save(structuredClone(defaults))
    }),
    route('GET', `${base}/mfa-keys`, () => keys),
    route('POST', `${base}/mfa-keys/generate`, () => {
      const bytes = crypto.getRandomValues(new Uint8Array(32))
      return {
        key: btoa(String.fromCharCode(...bytes)),
      }
    }),
    route('POST', `${base}/mfa-keys`, ({ body }) => {
      const id = text(body.keyId).trim()
      if (!id || !body.key || keys.versions.some(version => version.id === id))
        throw new MockError(400, '密钥编号无效或已存在')
      keys.versions.push({
        id,
        status: 'DRAFT',
        eligible: true,
        createdAt: mockTime(),
        publishedAt: null,
      })
      keys.revision++
      return keys
    }),
    route('POST', at(`${base}/mfa-keys/refresh`), (request) => {
      checkVersion(request, keys.revision, 'revision')
      const selected = keys.versions.find(version => version.id === request.body.targetKeyId)
      if (request.body.activate && selected) {
        selected.status = 'PUBLISHED'
        selected.publishedAt = mockTime()
        keys.targetKeyId = selected.id
        keys.lastPublishedAt = selected.publishedAt
        rotation.targetKeyId = selected.id
        rotation.canStart = selected.id !== rotation.activeKeyId
      }
      keys.revision++
      return keys
    }),
    route('GET', `${base}/mfa-rotation`, () => rotation),
    route('POST', `${base}/mfa-rotation/start`, () => {
      if (!rotation.canStart)
        throw new MockError(409, '请选择新的轮转目标')
      Object.assign(rotation, {
        activeKeyId: rotation.targetKeyId,
        activeSince: mockTime(),
        lastCompletedAt: mockTime(),
        status: 'COMPLETED',
        canStart: false,
      })
      return rotation
    }),
  ]
}
