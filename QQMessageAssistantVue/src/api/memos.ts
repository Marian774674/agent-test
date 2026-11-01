import { http } from './http'
import type {
  Memo,
  MemoChangeLog,
  MemoCreateBody,
  MemoPatchBody,
  MemoReminder,
  MemoStatus,
  ReminderPlanItem,
} from './types'

export function listMemos(params: { status?: MemoStatus; senderId?: number } = {}) {
  return http.get<Memo[]>('/api/memos', params)
}

export function getMemo(id: number) {
  return http.get<Memo>(`/api/memos/${id}`)
}

export function listReminders(id: number) {
  return http.get<MemoReminder[]>(`/api/memos/${id}/reminders`)
}

/** 试算还没保存的备忘会怎么提醒；与真实排程用同一份策略，不会出现「预览说的」和「实际排的」不一致 */
export function previewReminderPlan(params: {
  category?: string | null
  dueTime?: string | null
  importance?: number
}) {
  return http.get<ReminderPlanItem[]>('/api/memos/reminder-preview', {
    category: params.category,
    dueTime: params.dueTime,
    importance: params.importance,
  })
}

/** 按当前策略重排未发提醒（旧档置 CANCELLED，不删） */
export function replanReminders(id: number) {
  return http.post<MemoReminder[]>(`/api/memos/${id}/replan`)
}

export function listMemoHistory(id: number) {
  return http.get<MemoChangeLog[]>(`/api/memos/${id}/history`)
}

export function createMemo(body: MemoCreateBody) {
  return http.post<Memo>('/api/memos', body)
}

export function patchMemo(id: number, body: MemoPatchBody) {
  return http.patch<Memo>(`/api/memos/${id}`, body)
}
