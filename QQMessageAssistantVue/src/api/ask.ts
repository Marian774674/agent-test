import { http } from './http'

/** 一条能点回原文的证据；id 带类型前缀（msg: / note: / memo: / file:） */
export interface Evidence {
  type: 'msg' | 'note' | 'memo' | 'file'
  id: string
  label: string
  content: string
  /** 出处，例如「三班群 · 小明 · 10-28 21:03」；老后端可能没有 */
  source?: string
  /** 第一条来源消息的原文，默认折叠展示 */
  quote?: string
}

export interface ClarifyCandidate {
  senderId: number
  alias: string
  relation: string | null
  /** 让用户认得出的线索，例如「QQ 1753800374」 */
  hint: string
}

/** 认不出人时的反问：alias 是模型抽出来的称呼，candidates 为空表示库里根本没这个人 */
export interface ClarifyState {
  alias: string
  candidates: ClarifyCandidate[]
  /** 该称呼在记录里出现的次数；老后端可能没有 */
  occurrences?: number
}

/** 一次问答的检索范围与计数（spec §7.8）；老后端可能不返回 */
export interface AskScope {
  /** 语义通道是否可用 */
  semantic: boolean
  /** 尚未建立向量的消息条数 */
  lag: number
  /** 实际跑了几轮检索 */
  rounds: number
  /** 去重、预算截断前的候选数 */
  candidateCnt: number
  /** 通道 -> 原始命中数；null 表示该路没跑或失败 */
  channels: Record<string, number | null>
  /** 给人看的一句话；空表示无需解释 */
  message: string
}

export interface AskResponse {
  answer: string
  /** true=模型引用了候选集之外的东西，结论作废，只列证据 */
  degraded: boolean
  evidence: Evidence[]
  needClarify: ClarifyState | null
  scope?: AskScope
}

export interface ClarifyAnswer {
  alias: string
  senderId: number
  /** 关系或用户原话，记成 contact.relation */
  from?: string
}

/** 认过人之后带 clarify 重发同一个问题 */
export function ask(question: string, clarify?: ClarifyAnswer) {
  return http.post<AskResponse>('/api/ask', clarify ? { question, clarify } : { question })
}

export interface Contact {
  id: number
  senderId: number
  alias: string
  relation: string | null
  importance: number
  note: string | null
  source: 'AUTO' | 'USER'
  createdAt?: string
  updatedAt: string
}

export function listContacts(senderId?: number) {
  return http.get<Contact[]>('/api/contacts', senderId ? { senderId } : undefined)
}

export function createContact(body: { senderId: number; alias: string; relation?: string }) {
  return http.post<Contact>('/api/contacts', body)
}

export function deleteContact(id: number) {
  return http.del<number>(`/api/contacts/${id}`)
}
