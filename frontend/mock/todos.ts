import type { CalendarTodo, Todo } from '../src/views/workbench/api'
import type { MockUser } from './admin/directory'
import type { MockRoute } from './protocol'
import { formatBusinessToday, shiftDays } from '../src/utils/businessDate.ts'
import { baseTodo, mockTime } from './fixtures.ts'
import { get, inRange, matches, MockError, slicePage } from './protocol.ts'
import { demoUser } from './session.ts'

const todoPath = '/workbench/todos'
const open = (todo: Todo) => todo.status === 'OPEN' || todo.status === 'IN_PROGRESS'
const assigned = (todo: Todo) => todo.assigneeUserId === demoUser.id
const owned = (todo: Todo) => todo.ownerUserId === demoUser.id

const priorityRank = { HIGH: 0, NORMAL: 1, LOW: 2 }

function dueRank(todo: Todo, today: string) {
  if (!open(todo))
    return 4
  if (!todo.dueDate)
    return 3
  if (todo.dueDate === today)
    return 1
  if (todo.dueDate < today)
    return 0
  return 2
}

function visibleStatus(todo: Todo, includeCompleted: boolean) {
  return open(todo) || (includeCompleted && todo.status === 'DONE')
}

function statusMatches(todo: Todo, status: string | null, includeCompleted: boolean, today: string) {
  switch (status) {
    case 'OVERDUE': return open(todo) && !!todo.dueDate && todo.dueDate < today
    case 'TODAY': return open(todo) && todo.dueDate === today
    case 'UNSCHEDULED': return visibleStatus(todo, includeCompleted) && !todo.dueDate && !todo.startDate
    default: return !status || status === todo.status
  }
}

function filteredTodos(todos: Iterable<Todo>, query: URLSearchParams) {
  const today = formatBusinessToday()
  const keyword = query.get('keyword')
  const origin = query.get('origin')
  const status = query.get('status')
  const includeCompleted = query.get('includeCompleted') === 'true'
  return Array.from(todos).filter(todo => (
    (assigned(todo) || owned(todo))
    && matches(todo.title, keyword)
    && (!origin || todo.origin === origin)
    && statusMatches(todo, status, includeCompleted, today)
  )).sort((a, b) => (
    dueRank(a, today) - dueRank(b, today)
    || priorityRank[a.priority] - priorityRank[b.priority]
    || (a.dueDate ?? '').localeCompare(b.dueDate ?? '')
    || a.id - b.id
  ))
}

function planOverlaps(todo: Todo, from: string | null, to: string | null) {
  return !!todo.startDate && !!todo.endDate && (!from || todo.endDate >= from) && (!to || todo.startDate <= to)
}

function dateTimestamp(value: string) {
  if (!/^[1-9]\d{3}-\d{2}-\d{2}$/.test(value))
    return Number.NaN
  const date = new Date(`${value}T00:00:00Z`)
  const timestamp = date.getTime()
  return Number.isFinite(timestamp) && date.toISOString().slice(0, 10) === value
    ? timestamp
    : Number.NaN
}

function calendarTodos(todos: Iterable<Todo>, query: URLSearchParams): CalendarTodo[] {
  const from = query.get('fromDate') ?? ''
  const to = query.get('toDate') ?? ''
  const span = (dateTimestamp(to) - dateTimestamp(from)) / 86_400_000
  if (!Number.isFinite(span) || span < 0 || span >= 42)
    throw new MockError(400, '请选择有效的日历日期区间，最多 42 天')

  const scope = query.get('scope') === 'created'
    ? owned
    : assigned
  const includeCompleted = query.get('includeCompleted') === 'true'
  return filteredTodos(todos, query)
    .filter(todo => scope(todo) && visibleStatus(todo, includeCompleted))
    .filter(todo => todo.startDate
      ? planOverlaps(todo, from, to)
      : !!todo.dueDate && inRange(todo.dueDate, from, to))
    .map(({ id, title, status, startDate, endDate, dueDate }) => ({
      id,
      title,
      status,
      startDate,
      endDate,
      dueDate,
    }))
}

export function createTodos(reviewer: Pick<MockUser, 'id' | 'nameCn'>) {
  const today = formatBusinessToday()
  const examples: Todo[] = [
    {
      ...baseTodo(1, '整理本周项目进展', mockTime(-1), 2),
      startDate: shiftDays(today, -1),
      endDate: shiftDays(today, 1),
      priority: 'HIGH',
    },
    {
      ...baseTodo(2, '确认演示环境访问权限', mockTime(-3), 3),
      status: 'DONE',
      closedAt: mockTime(-2),
    },
    {
      ...baseTodo(3, '收集下次演示反馈', mockTime(-1), 0),
      scheduleMode: 'NONE',
      dueDate: null,
    },
    {
      ...baseTodo(4, '复核演示用例', mockTime(-1), 1),
      assigneeUserId: reviewer.id,
      assigneeName: reviewer.nameCn,
    },
    {
      ...baseTodo(5, '整理演示素材', mockTime(-3), 0),
      scheduleMode: 'NONE',
      dueDate: null,
      status: 'DONE',
      closedAt: mockTime(-2),
    },
  ]
  const todos = new Map(examples.map(item => [item.id, item]))
  const routes: MockRoute[] = [
    {
      method: 'GET',
      path: todoPath,
      handle: ({ query }) => {
        const from = query.get('fromDate')
        const to = query.get('toDate')
        const items = filteredTodos(todos.values(), query).filter((todo) => {
          if (!from && !to)
            return true
          if (query.get('dateField') === 'plan')
            return planOverlaps(todo, from, to)
          return !!todo.dueDate && inRange(todo.dueDate, from, to)
        })
        const includeCompleted = query.get('status') === 'UNSCHEDULED' && query.get('includeCompleted') === 'true'
        const groups = {
          mine: items.filter(todo => assigned(todo) && visibleStatus(todo, includeCompleted)),
          created: items.filter(owned),
          done: items.filter(todo => assigned(todo) && todo.status === 'DONE'),
          all: items,
        }
        const scope = query.get('scope') ?? 'mine'
        const records = Object.hasOwn(groups, scope)
          ? groups[scope as keyof typeof groups]
          : groups.mine
        return {
          records: slicePage(records, query.get('page'), query.get('size')),
          total: records.length,
          scopeCounts: {
            mine: groups.mine.length,
            created: groups.created.length,
            done: groups.done.length,
            all: groups.all.length,
          },
        }
      },
    },
    {
      method: 'GET',
      path: `${todoPath}/calendar`,
      handle: ({ query }) => calendarTodos(todos.values(), query),
    },
    {
      method: 'GET',
      path: /^\/workbench\/todos\/(\d+)$/,
      handle: ({ params }) => get(todos, params[1]),
    },
    {
      method: 'GET',
      path: `${todoPath}/public-dimensions`,
      handle: () => [],
    },
    {
      method: 'GET',
      path: `${todoPath}/personal-dimensions`,
      handle: () => [],
    },
  ]
  return { todos, routes }
}
