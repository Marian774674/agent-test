/** 与 Spring Boot 的统一响应体 `{code,message,data}` 对应 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export type MemoStatus =
  | 'ACTIVE'
  | 'REMINDED'
  | 'AWAITING_REPLY'
  | 'DONE'
  | 'CANCELLED'
  | 'OVERDUE'

export type Category = '学业' | '社交' | '健康' | '财务' | '日常' | '其他'

export const CATEGORIES: Category[] = ['学业', '社交', '健康', '财务', '日常', '其他']

export interface Memo {
  id: number
  senderId: number
  chatId: number | null
  title: string
  content: string | null
  importance: number
  category: Category | null
  dueTime: string | null
  status: MemoStatus
  sourceMsgId: number | null
  createdAt: string
  updatedAt: string
}

export type ReminderStatus = 'PENDING' | 'SENT' | 'CANCELLED'

export interface MemoReminder {
  id: number
  /** 档位标签，如 -1d@20:00 / -1h / 立即 */
  offsetLabel: string
  /** 后端翻译好的中文说明，如「前一天 20:00」；前端不再自己维护映射表 */
  offsetText: string
  remindAt: string
  status: ReminderStatus
  sentAt: string | null
}

/** 编辑器里的「将在这几个时刻提醒你」预览 */
export interface ReminderPlanItem {
  label: string
  text: string
  remindAt: string
}

export type MemoChangeSource = 'AGENT' | 'VUE'
export type MemoChangeOp = 'CREATE' | 'UPDATE' | 'CLOSE'

/** 一次改动的留痕；beforeJson/afterJson 是备忘快照的 JSON 字符串 */
export interface MemoChangeLog {
  id: number
  memoId: number
  senderId: number
  source: MemoChangeSource
  op: MemoChangeOp
  eventId: string | null
  beforeJson: string | null
  afterJson: string | null
  resultJson: string | null
  conversationId: string | null
  createdAt: string
}

export interface MemoDraftPayload {
  title?: string
  content?: string
  importance?: number
  category?: string
  dueTime?: string
}

export type DraftType = 'REPLY' | 'FILE' | 'MEMO' | 'FACT'
export type DraftStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED'

export interface Draft {
  id: number
  type: DraftType
  senderId: number
  chatId: number | null
  title: string | null
  status: DraftStatus
  payload: Record<string, unknown> | null
  confirmedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ProfileFact {
  id: number
  senderId: number
  factKey: string
  factValue: string
  confidence: number
  source: string | null
  createdAt: string
  updatedAt: string
}

export interface StyleProfile {
  id: number
  senderId: number
  profilePrompt: string
  lastSummarizedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface MemoCreateBody {
  senderId?: number
  chatId?: number | null
  title: string
  content?: string | null
  importance: number
  category?: Category | null
  dueTime?: string | null
}

export interface MemoPatchBody {
  title?: string
  content?: string
  importance?: number
  category?: Category
  dueTime?: string | null
  clearDueTime?: boolean
  status?: 'DONE' | 'CANCELLED'
}

/* ── 消息日志 ─────────────────────────────────────────────── */

export type LogDirection = 'IN' | 'OUT'

export interface MessageLog {
  id: number
  senderId: number
  chatId: number | null
  conversationId: string | null
  messageId: string | null
  dedupeKey: string | null
  direction: LogDirection
  msgType: string
  content: string | null
  createdAt: string
}

/* ── 文件任务 ─────────────────────────────────────────────── */

export type FileTaskStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'NEED_INPUT'
  | 'AWAITING_CONFIRM'
  | 'SENT'
  | 'FAILED'
  | 'CANCELLED'

export interface FileTask {
  id: number
  taskRef: string
  senderId: number
  chatId: number | null
  fileName: string | null
  status: FileTaskStatus
  detail: unknown
  createdAt: string
  updatedAt: string
}

/* ── 拦截规则 ─────────────────────────────────────────────── */

export interface InterceptRule {
  id: number
  name: string
  keywords: string | null
  regex: string | null
  replyTemplate: string | null
  priority: number
  enabled: boolean
  createdAt?: string
  updatedAt?: string
}

export interface InterceptRuleBody {
  name: string
  keywords?: string | null
  regex?: string | null
  replyTemplate?: string | null
  priority?: number
  enabled?: boolean
}

