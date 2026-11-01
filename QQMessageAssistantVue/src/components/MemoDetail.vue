<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { BellOff } from 'lucide-vue-next'
import Sheet from '@/components/Sheet.vue'
import SheetHead from '@/components/SheetHead.vue'
import ImportanceMark from '@/components/ImportanceMark.vue'
import { listMemoHistory, listReminders, patchMemo } from '@/api/memos'
import type { Memo, MemoChangeLog, MemoReminder } from '@/api/types'
import { useToast } from '@/composables/useToast'
import {
  dayDiff,
  formatDateTime,
  formatHeadDate,
  formatTime,
  parseLocal,
  relativeUntil,
} from '@/utils/datetime'
import { importanceLabel, memoStatusMeta, reminderStatusMeta } from '@/utils/meta'

const props = defineProps<{
  open: boolean
  memo: Memo | null
  vtName?: string
  instant?: boolean
}>()

const emit = defineEmits<{
  close: []
  edit: [memo: Memo]
  changed: [memo: Memo]
}>()

const toast = useToast()

const reminders = ref<MemoReminder[]>([])
const loadingReminders = ref(false)
const history = ref<MemoChangeLog[]>([])
const loadingHistory = ref(false)
const busy = ref(false)
const confirmingCancel = ref(false)

const OFFSET_LABELS: Record<string, string> = {
  '24h': '提前 24 小时',
  '2h': '提前 2 小时',
  '1h': '提前 1 小时',
  '30min': '提前 30 分钟',
  当天: '当天 09:00',
}

const isClosed = computed(
  () => props.memo?.status === 'DONE' || props.memo?.status === 'CANCELLED',
)

const statusMeta = computed(() => memoStatusMeta(props.memo?.status ?? ''))
const pendingCount = computed(
  () => reminders.value.filter((reminder) => reminder.status === 'PENDING').length,
)

/* 截止时间是这张抽屉里唯一该被放大看的东西：
 * 日期走衬线、时间和编辑抽屉里那行保持同一套写法（日 · 时），
 * 下面补一句相对时间，省得用户自己算「还有多久」。
 * 星期拆出来单独排小字——和日期同号会变成「11月2日 周一」一坨。 */
const dueParts = computed(() => {
  const date = parseLocal(props.memo?.dueTime)
  if (!date) return null
  const days = dayDiff(date, new Date())
  const relative = days === 0 ? '今天' : days === 1 ? '明天' : days === 2 ? '后天' : ''
  if (relative) return { day: relative, week: '', time: formatTime(date) }
  const [day, week] = formatHeadDate(date).split(' ')
  return { day, week, time: formatTime(date) }
})

const dueRelative = computed(() => relativeUntil(props.memo?.dueTime))

/* 空态文案不能靠猜。原来那句「未设置截止时间，或重要性为 1 级」
 * 在「有截止时间、重要性 2 级、但没排上提醒」的备忘上自相矛盾，
 * 干脆按实际情况分开说。 */
const emptyPlanText = computed(() => {
  const memo = props.memo
  if (!memo) return '没有提醒'
  if (!memo.dueTime) return '没设截止时间，这条不会提醒'
  if (memo.importance <= 1) return '1 级只存档，不排提醒'
  if (isClosed.value) return '已结束，剩下的提醒都撤了'
  return '这条没排上提醒'
})

function offsetLabel(label: string) {
  return OFFSET_LABELS[label] ?? label
}

/* ── 变更记录 ───────────────────────────────────────────────
 * 后端存的是备忘快照 JSON（beforeJson / afterJson），差异在这里算，
 * 界面只说「改了什么」，不把原始 JSON 摆给用户看。 */

interface MemoSnapshot {
  title?: unknown
  content?: unknown
  importance?: unknown
  category?: unknown
  dueTime?: unknown
  status?: unknown
}

function snapshot(json: string | null): MemoSnapshot {
  if (!json) return {}
  try {
    const parsed: unknown = JSON.parse(json)
    return parsed && typeof parsed === 'object' ? (parsed as MemoSnapshot) : {}
  } catch {
    return {}
  }
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function same(a: unknown, b: unknown) {
  return text(a) === text(b)
}

function dueLabel(value: unknown) {
  const iso = text(value)
  return iso ? formatDateTime(iso) : '未设置'
}

function changeText(entry: MemoChangeLog): string {
  const after = snapshot(entry.afterJson)
  if (entry.op === 'CREATE') return '记下了这条备忘'
  if (entry.op === 'CLOSE') {
    if (after.status === 'DONE') return '标记为已完成'
    if (after.status === 'CANCELLED') return '取消了这条备忘'
    return '关闭了这条备忘'
  }

  const before = snapshot(entry.beforeJson)
  const parts: string[] = []
  if (!same(before.dueTime, after.dueTime)) {
    parts.push(`截止时间 ${dueLabel(before.dueTime)} → ${dueLabel(after.dueTime)}`)
  }
  if (!same(before.status, after.status)) {
    parts.push(
      `状态 ${memoStatusMeta(text(before.status)).label} → ${memoStatusMeta(text(after.status)).label}`,
    )
  }
  if (!same(before.importance, after.importance)) {
    parts.push(
      `重要性 ${importanceLabel(Number(before.importance ?? 0))} → ${importanceLabel(
        Number(after.importance ?? 0),
      )}`,
    )
  }
  if (!same(before.title, after.title)) parts.push('改了标题')
  if (!same(before.category, after.category)) parts.push('改了分类')
  if (!same(before.content, after.content)) parts.push('改了正文')
  return parts.length ? parts.join('，') : '存了一次，内容没变'
}

watch(
  () => [props.open, props.memo?.id] as const,
  async ([open, id]) => {
    confirmingCancel.value = false
    if (!open || !id) return
    reminders.value = []
    history.value = []
    loadingReminders.value = true
    loadingHistory.value = true
    try {
      reminders.value = (await listReminders(id)) ?? []
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '提醒计划读取失败')
    } finally {
      loadingReminders.value = false
    }
    try {
      history.value = (await listMemoHistory(id)) ?? []
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '变更记录读取失败')
    } finally {
      loadingHistory.value = false
    }
  },
)

async function markDone() {
  const memo = props.memo
  if (!memo) return
  busy.value = true
  try {
    const updated = await patchMemo(memo.id, { status: 'DONE' })
    toast.success('已完成，剩下的提醒一并取消')
    emit('changed', updated)
    emit('close')
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    busy.value = false
  }
}

async function cancelMemo() {
  const memo = props.memo
  if (!memo) return
  busy.value = true
  try {
    const updated = await patchMemo(memo.id, { status: 'CANCELLED' })
    toast.push('已取消这条备忘')
    emit('changed', updated)
    emit('close')
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Sheet
    :open="open"
    :title="memo?.title ?? '备忘'"
    :vt-name="vtName"
    :instant="instant"
    @close="emit('close')"
  >
    <template #head>
      <SheetHead
        :title="memo?.title ?? '备忘'"
        :stamp="memo?.category ?? undefined"
        :meta="memo ? `记录于 ${formatDateTime(memo.createdAt)}` : ''"
      />
    </template>

    <template v-if="memo">
      <div class="meta">
        <ImportanceMark :importance="memo.importance" />
        <span class="meta-state" :class="`tone-${statusMeta.tone}`">
          <i class="dot" aria-hidden="true" />
          {{ statusMeta.label }}
        </span>
      </div>

      <p v-if="memo.content" class="content">{{ memo.content }}</p>

      <section class="blk" aria-label="截止时间">
        <h3 class="blk-title"><span>截止时间</span></h3>
        <p v-if="dueParts" class="due">
          <span class="num">{{ dueParts.day }}</span>
          <span v-if="dueParts.week" class="due-week">{{ dueParts.week }}</span>
          <span class="due-dot" aria-hidden="true" />
          <span class="num">{{ dueParts.time }}</span>
        </p>
        <p v-else class="blk-none">没设截止时间，也不会提醒</p>
        <p v-if="dueParts && dueRelative && !isClosed" class="due-rel">{{ dueRelative }}</p>
      </section>

      <!-- 没有截止时间就不可能有提醒，整段省掉，免得同一句话连说两遍 -->
      <section v-if="dueParts || reminders.length" class="blk" aria-labelledby="plan-title">
        <h3 id="plan-title" class="blk-title">
          <span>提醒计划</span>
          <span v-if="pendingCount" class="blk-count num">还剩 {{ pendingCount }} 次</span>
        </h3>

        <p v-if="loadingReminders" class="blk-none">正在读取…</p>
        <p v-else-if="!reminders.length" class="blk-none">
          <BellOff :size="14" aria-hidden="true" />
          <span>{{ emptyPlanText }}</span>
        </p>
        <ul v-else class="reminders">
          <li
            v-for="reminder in reminders"
            :key="reminder.id"
            class="reminder"
            :class="{ off: reminder.status === 'CANCELLED' }"
          >
            <span class="reminder-when">
              <span class="reminder-offset">{{ offsetLabel(reminder.offsetLabel) }}</span>
              <span class="reminder-at num">{{ formatDateTime(reminder.remindAt) }}</span>
            </span>
            <span class="reminder-state">{{ reminderStatusMeta(reminder.status).label }}</span>
          </li>
        </ul>
      </section>

      <section class="blk" aria-labelledby="history-title">
        <h3 id="history-title" class="blk-title">
          <span>变更记录</span>
          <span v-if="history.length" class="blk-count num">{{ history.length }} 次</span>
        </h3>

        <p v-if="loadingHistory" class="blk-none">正在读取…</p>
        <p v-else-if="!history.length" class="blk-none"><span>还没改过。</span></p>
        <ul v-else class="changes">
          <li v-for="entry in history" :key="entry.id" class="change">
            <span class="change-body">
              <span class="change-source" :class="{ ai: entry.source === 'AGENT' }">
                {{ entry.source === 'AGENT' ? 'QQ 里改的' : '手动改的' }}
              </span>
              <span class="change-text">{{ changeText(entry) }}</span>
            </span>
            <span class="change-at num">{{ formatDateTime(entry.createdAt) }}</span>
          </li>
        </ul>
      </section>

      <div v-if="confirmingCancel && !isClosed" class="confirm" role="alert">
        <p>取消后不会再提醒，这条备忘会留档。</p>
        <div class="confirm-actions">
          <button type="button" class="btn btn-sm btn-ghost" @click="confirmingCancel = false">
            算了
          </button>
          <button type="button" class="btn btn-sm btn-danger" :disabled="busy" @click="cancelMemo">
            确定取消
          </button>
        </div>
      </div>

      <button
        v-else-if="!isClosed"
        type="button"
        class="danger-link"
        @click="confirmingCancel = true"
      >
        取消这条备忘
      </button>
    </template>

    <template #footer>
      <button type="button" class="btn btn-ghost" :disabled="busy" @click="memo && emit('edit', memo)">
        编辑
      </button>
      <button
        v-if="!isClosed"
        type="button"
        class="btn btn-primary"
        :disabled="busy"
        @click="markDone"
      >
        标记完成
      </button>
      <!-- 只有「标记完成」是实心：已结束的备忘上，关闭不该比编辑更抢眼 -->
      <button v-else type="button" class="btn btn-ghost" @click="emit('close')">关闭</button>
    </template>
  </Sheet>
</template>

<style scoped>
/* 报头交给 SheetHead，这里只管正文。

   ── 署名行：重要性竖条 + 状态 ─────────────────────────────
   状态不用胶囊。纸面上一块浅色药丸会跳出来抢注意力，
   而这里的状态本来就该和重要性的五根竖条同属「微标注」。 */
.meta {
  display: flex;
  align-items: center;
  gap: 12px;
}

.meta-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding-left: 12px;
  border-left: 1px solid var(--rule);
  color: var(--ink-2);
  font-size: 11.5px;
  white-space: nowrap;
}

.meta-state .dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}

.meta-state.tone-warn {
  color: var(--warn);
}

.meta-state.tone-danger {
  color: var(--danger);
}

.meta-state.tone-quiet {
  color: var(--ink-3);
}

/* 正文不铺底板：纸面上再压一块更白的块，等于凭空多一层边框。
   一根左侧细线就够把它和下面的信息分开。 */
.content {
  margin: 14px 0 0;
  padding-left: 13px;
  border-left: 1px solid var(--rule);
  color: var(--ink-2);
  font-size: 14.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* ── 小节 ──────────────────────────────────────────────── */
.blk {
  margin-top: 22px;
}

/* 小节标题走「微标签 + 引导线」，和页面上的栏目线同一套字号与字距 */
.blk-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 9px;
  color: var(--ink-3);
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.16em;
}

.blk-title::after {
  content: '';
  order: 1;
  flex: 1;
  min-width: 10px;
  height: 1px;
  background: var(--rule);
}

.blk-count {
  order: 2;
  color: var(--ink-2);
  font-family: var(--font-display);
  font-size: 13px;
  font-weight: 400;
  letter-spacing: 0;
}

.blk-none {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ink-3);
  font-size: 13px;
  line-height: 1.55;
}

/* 截止时间是这张抽屉里唯一该被放大看的东西：
   衬线、和编辑抽屉里那行同一套写法（日 · 时），下面补一句相对时间。 */
.due {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 9px;
  color: var(--ink);
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 400;
  line-height: 1.2;
  letter-spacing: -0.005em;
}

/* 星期用无衬线小字：中文衬线在 11px 会糊成一团，这条规矩和正文一样 */
.due-week {
  align-self: center;
  color: var(--ink-3);
  font-family: var(--font-ui);
  font-size: 11px;
  letter-spacing: 0.04em;
}

.due-dot {
  width: 3px;
  height: 3px;
  border-radius: 1px;
  background: var(--dot);
  transform: translateY(-4px);
}

.due-rel {
  margin-top: 7px;
  color: var(--ink-3);
  font-size: 11.5px;
}

/* 提醒与变更都不套圆角容器：这里本来就是一张「清单」，
   发丝线分隔已经够用，再包一层就回到旧版那种卡片堆叠。 */
.reminders,
.changes {
  margin: 0;
  padding: 0;
  list-style: none;
}

.reminder {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
}

.reminder + .reminder {
  border-top: 1px solid var(--hairline);
}

.reminder-when {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 10px;
  min-width: 0;
}

.reminder-offset {
  color: var(--ink);
  font-size: 13px;
}

.reminder-at {
  color: var(--ink-3);
  font-size: 12px;
}

.reminder-state {
  flex: none;
  color: var(--ink-2);
  font-size: 11.5px;
  white-space: nowrap;
}

.reminder.off .reminder-offset,
.reminder.off .reminder-at {
  color: var(--ink-3);
  text-decoration: line-through;
}

.reminder.off .reminder-state {
  color: var(--ink-3);
}

.change {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: baseline;
  gap: 0 10px;
  padding: 8px 0;
}

.change + .change {
  border-top: 1px solid var(--hairline);
}

.change-body {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 2px 8px;
  min-width: 0;
}

/* 谁改的不用胶囊也不用小方块，改字色就够了：
   纸底上一枚 3px 的方点会像一颗掉在行首的碎屑。 */
.change-source {
  color: var(--ink-3);
  font-size: 11px;
  letter-spacing: 0.02em;
  white-space: nowrap;
}

.change-source.ai {
  color: var(--accent-ink);
}

.change-text {
  color: var(--ink);
  font-size: 13px;
  overflow-wrap: anywhere;
}

.change-at {
  color: var(--ink-3);
  font-size: 11.5px;
  white-space: nowrap;
}

.confirm {
  margin-top: 20px;
  padding: 13px 14px;
  border: 1px solid rgba(191, 58, 43, 0.22);
  border-radius: var(--r-sm);
  background: var(--danger-wash);
  color: var(--danger);
  font-size: 12.5px;
  line-height: 1.55;
}

.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 11px;
}

.confirm .btn-ghost {
  color: var(--ink-2);
}

.confirm .btn-ghost:active {
  background: rgba(46, 38, 26, 0.06);
}

/* 确认区在红色浅底上，btn-danger 自带的浅红底会糊成一片，这里给实心 */
.confirm .btn-danger {
  border-color: transparent;
  background: var(--danger);
  color: #fff;
}

.danger-link {
  margin-top: 22px;
  padding: 6px 0;
  border: 0;
  background: transparent;
  color: var(--danger);
  font-size: 12.5px;
  cursor: pointer;
}

.danger-link:active {
  color: #a32f22;
}
</style>
