const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']

/** 后端返回的 LocalDateTime 没有时区（2026-10-28T19:00:00），按本地时间解析 */
export function parseLocal(iso: string | null | undefined): Date | null {
  if (!iso) return null
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? null : date
}

export function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

export function formatTime(iso: string | Date | null | undefined): string {
  const date = typeof iso === 'string' ? parseLocal(iso) : iso
  if (!date) return '--:--'
  return `${pad2(date.getHours())}:${pad2(date.getMinutes())}`
}

export function formatMonthDay(date: Date): string {
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

export function formatDateTime(iso: string | null | undefined): string {
  const date = parseLocal(iso)
  if (!date) return '未设置'
  return `${formatMonthDay(date)} ${formatTime(date)}`
}

/** 列表里用的紧凑时间：今天只显示时分，其他显示月日 */
export function formatStamp(iso: string | null | undefined): string {
  const date = parseLocal(iso)
  if (!date) return ''
  return isSameDay(date, new Date()) ? formatTime(date) : formatMonthDay(date)
}

export function formatFullDate(date: Date): string {
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 星期${
    WEEKDAYS[date.getDay()]
  }`
}

/** 10月29日 周四 */
export function formatHeadDate(date: Date): string {
  return `${date.getMonth() + 1}月${date.getDate()}日 周${WEEKDAYS[date.getDay()]}`
}

export function dayKey(date: Date): string {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

export function isSameDay(a: Date, b: Date): boolean {
  return dayKey(a) === dayKey(b)
}

/**
 * 相差几个「自然日」（不是 24 小时）。
 * 按日历日算，否则 23:30 和次日 00:10 只差 40 分钟却会被算成 0 天。
 * 负数表示已经过去。
 */
export function dayDiff(a: Date, b: Date): number {
  const startOf = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
  return Math.round((startOf(a) - startOf(b)) / 86_400_000)
}

export function isToday(date: Date): boolean {
  return isSameDay(date, new Date())
}

/** 给 <input type="datetime-local"> 用的值 */
export function toDatetimeLocalValue(value: string | Date | null | undefined): string {
  const date = value instanceof Date ? value : parseLocal(value)
  if (!date) return ''
  return `${dayKey(date)}T${pad2(date.getHours())}:${pad2(date.getMinutes())}`
}

/** datetime-local 的值是本地时间，直接补秒，后端按本地时间存 */
export function fromDatetimeLocalValue(value: string): string | null {
  if (!value) return null
  return value.length === 16 ? `${value}:00` : value
}

/** 距离截止还有多久 */
export function relativeUntil(iso: string | null | undefined): string {
  const date = parseLocal(iso)
  if (!date) return ''
  const diffMs = date.getTime() - Date.now()
  const past = diffMs < 0
  const minutes = Math.round(Math.abs(diffMs) / 60000)

  let text: string
  if (minutes < 1) text = '刚刚'
  else if (minutes < 60) text = `${minutes} 分钟`
  else if (minutes < 60 * 24) {
    const hours = Math.floor(minutes / 60)
    const rest = minutes % 60
    text = rest ? `${hours} 小时 ${rest} 分` : `${hours} 小时`
  } else {
    const days = Math.floor(minutes / (60 * 24))
    const rest = Math.round((minutes - days * 60 * 24) / 60)
    text = rest ? `${days} 天 ${rest} 小时` : `${days} 天`
  }
  return past ? `已过 ${text}` : `还有 ${text}`
}
