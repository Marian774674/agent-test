import { http } from './http'

export interface DigestItem {
  id: number
  chatId: number
  chatName: string | null
  summary: string
  /** 1–5，越大越该先看 */
  importance: number
  mentionsMe: boolean
  /** 原文证据：message_log.id，逗号分隔 */
  sourceMsgIds: string
  periodEnd: string | null
  createdAt: string
}

/** 进首页时问后端：距上次整理够久或消息够多才真的跑一次整理 */
export interface DigestRefresh {
  enabled: boolean
  refreshed: boolean
  messageCount: number
  minutesSince: number
  saved: number
  windowStart: string | null
}

export function refreshDigest() {
  return http.post<DigestRefresh>('/api/digest/refresh')
}

/** 上次离开之后的整理结果；不传 since 时后端按最近 24 小时算 */
export function listDigest(since?: string) {
  return http.get<DigestItem[]>('/api/digest', { since })
}

/** 上次「已看过」的时间；没看过返回空串 */
export function getLastSeen() {
  return http.get<string>('/api/digest/last-seen')
}

export function markSeen() {
  return http.post<number>('/api/digest/seen')
}
