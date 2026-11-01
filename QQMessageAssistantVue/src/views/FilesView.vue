<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { FileSpreadsheet } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHead from '@/components/PageHead.vue'
import Sheet from '@/components/Sheet.vue'
import Skeleton from '@/components/Skeleton.vue'
import SheetHead from '@/components/SheetHead.vue'
import StatusPill from '@/components/StatusPill.vue'
import { listFileTasks } from '@/api/files'
import type { FileTask } from '@/api/types'
import { useRefresh } from '@/composables/useRefresh'
import { useToast } from '@/composables/useToast'
import { formatDateTime } from '@/utils/datetime'
import { FILE_GROUPS, fileStatusMeta } from '@/utils/meta'

const toast = useToast()

const tasks = ref<FileTask[]>([])
const loading = ref(true)
const error = ref('')

const selected = ref<FileTask | null>(null)
const sheetOpen = ref(false)

/** silent = 下拉刷新：保留当前列表，不闪骨架屏 */
async function load(silent = false) {
  if (!silent) {
    loading.value = true
    error.value = ''
  }
  try {
    tasks.value = await listFileTasks()
  } catch (e) {
    const message = e instanceof Error ? e.message : '文件任务读取失败'
    if (silent) toast.error(message)
    else error.value = message
  } finally {
    loading.value = false
  }
}

onMounted(load)

useRefresh(() => load(true))

const groups = computed(() =>
  FILE_GROUPS.map((group) => ({
    key: group.key,
    label: group.label,
    items: tasks.value
      .filter((task) => (group.statuses as readonly string[]).includes(task.status))
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)),
  })).filter((group) => group.items.length),
)

function open(task: FileTask) {
  selected.value = task
  sheetOpen.value = true
}

/* ── 状态详情：后端塞的是一坨 JSON，直接摆出来没人看得懂 ──── */

const DETAIL_LABELS: Record<string, string> = {
  sheet: '工作表',
  sheetName: '工作表',
  headerRow: '表头行',
  totalRows: '总行数',
  rows: '行数',
  filled: '已填写',
  fills: '已填写',
  rowsFilled: '已填行数',
  missing: '待补字段',
  columns: '列',
  template: '模板',
  progress: '进度',
  confidence: '把握',
  needsConfirm: '待确认',
  message: '说明',
  error: '错误',
  filePath: '文件路径',
  outputPath: '产物路径',
  step: '当前步骤',
  notes: '备注',
}

interface DetailRow {
  key: string
  label: string
  value: string
}

function formatDetailValue(key: string, value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'number') {
    // 0–1 之间的小数多半是把握 / 进度，直接换算成百分比更好读
    if (key === 'confidence' || key === 'progress') return `${Math.round(value * 100)}%`
    return String(value)
  }
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value.map((item) => formatDetailValue(key, item)).join('、')
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

const detailRows = computed<DetailRow[]>(() => {
  const detail = selected.value?.detail
  if (!detail || typeof detail !== 'object' || Array.isArray(detail)) return []
  return Object.entries(detail as Record<string, unknown>)
    .filter(([, value]) => value !== null && value !== undefined && value !== '')
    .map(([key, value]) => ({
      key,
      label: DETAIL_LABELS[key] ?? key,
      value: formatDetailValue(key, value),
    }))
})

const rawDetail = computed(() => {
  const detail = selected.value?.detail
  if (!detail) return ''
  if (typeof detail === 'string') return detail
  try {
    return JSON.stringify(detail, null, 2)
  } catch {
    return String(detail)
  }
})

const selectedMeta = computed(() => fileStatusMeta(selected.value?.status ?? ''))
</script>

<template>
  <div class="page">
    <PageHead
      :back="{ to: '/more', label: '更多' }"
      title="Files"
      sub="LangBot 那边每处理一份表格都会镜像到这里，只读。"
    />

    <ErrorState v-if="error" :message="error" hint="也可以下拉重试。" @retry="load" />

    <Skeleton v-else-if="loading" :rows="3" />

    <template v-else-if="groups.length">
      <section v-for="group in groups" :key="group.key" class="group">
        <h2 class="group-title">{{ group.label }} <span class="num">{{ group.items.length }}</span></h2>
        <ul class="stack">
          <li v-for="task in group.items" :key="task.id">
            <button type="button" class="task" @click="open(task)">
              <span class="task-icon" aria-hidden="true">
                <FileSpreadsheet :size="17" :stroke-width="1.8" />
              </span>
              <span class="task-main">
                <span class="task-name">{{ task.fileName || '未命名文件' }}</span>
                <span class="task-meta mono">{{ task.taskRef }}</span>
              </span>
              <span class="task-side">
                <StatusPill
                  :label="fileStatusMeta(task.status).label"
                  :tone="fileStatusMeta(task.status).tone"
                />
                <span class="task-time num">{{ formatDateTime(task.updatedAt) }}</span>
              </span>
            </button>
          </li>
        </ul>
      </section>
    </template>

    <EmptyState
      v-else
      title="还没有文件任务"
      hint="在 QQ 里发一份 xlsx 或 docx，处理进度会出现在这里。如果刚发过，先去消息日志看看机器人有没有收到。"
    >
      <RouterLink to="/logs" class="btn btn-sm btn-quiet">看消息日志</RouterLink>
    </EmptyState>

    <Sheet
      :open="sheetOpen"
      :title="selected?.fileName || '文件任务'"
      @close="sheetOpen = false"
    >
      <template #head>
        <SheetHead
          :title="selected?.fileName || '文件任务'"
          :stamp="selectedMeta.label"
          :meta="selected?.taskRef"
        />
      </template>
      <template v-if="selected">
        <div class="facts">
          <div class="fact">
            <span class="fact-label">状态</span>
            <StatusPill :label="selectedMeta.label" :tone="selectedMeta.tone" />
          </div>
          <div class="fact">
            <span class="fact-label">会话</span>
            <span class="fact-value num">{{ selected.chatId ?? '私聊' }}</span>
          </div>
          <div class="fact">
            <span class="fact-label">创建</span>
            <span class="fact-value">{{ formatDateTime(selected.createdAt) }}</span>
          </div>
          <div class="fact">
            <span class="fact-label">更新</span>
            <span class="fact-value">{{ formatDateTime(selected.updatedAt) }}</span>
          </div>
        </div>

        <template v-if="detailRows.length">
          <h3 class="detail-title">状态详情</h3>
          <dl class="detail-list">
            <div v-for="row in detailRows" :key="row.key" class="detail-row">
              <dt>{{ row.label }}</dt>
              <dd>{{ row.value }}</dd>
            </div>
          </dl>
          <details class="raw">
            <summary>原始数据</summary>
            <pre class="detail-json">{{ rawDetail }}</pre>
          </details>
        </template>

        <template v-else-if="rawDetail">
          <h3 class="detail-title">状态详情</h3>
          <pre class="detail-json">{{ rawDetail }}</pre>
        </template>
      </template>

      <template #footer>
        <button type="button" class="btn btn-ghost" @click="sheetOpen = false">关闭</button>
      </template>
    </Sheet>
  </div>
</template>

<style scoped>

.group + .group {
  margin-top: 22px;
}

/* 分节标题由 style.css 的「栏目线」统一实现，这里只管块间距 */
.group-title {
  margin-bottom: 10px;
}

.stack {
  margin: 0;
  padding: 0;
  list-style: none;
}

.task {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) auto;
  align-items: center;
  gap: 11px;
  width: 100%;
  padding: 13px 14px;
  border: 0;
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-card);
  text-align: left;
  cursor: pointer;
  transition: transform var(--dur) var(--ease);
}

.task:active {
  transform: scale(0.985);
}

.task-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--ink-2);
}

.task-main {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.task-name {
  font-size: 14.5px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-meta {
  color: var(--ink-3);
}

.task-side {
  display: grid;
  justify-items: end;
  gap: 4px;
}

.task-time {
  color: var(--ink-3);
  font-size: 10.5px;
}

/* 详情清单不套白卡：纸面上再加一层白块，等于凭空多一道边框。
   发丝线分隔就够，和备忘详情里那两张清单是同一套。 */
.facts {
  display: grid;
}

.fact {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 0;
  color: var(--ink);
}

.fact + .fact {
  border-top: 1px solid var(--hairline);
}

.fact-label {
  color: var(--ink-3);
  font-size: 12.5px;
}

.fact-value {
  font-size: 13.5px;
}

.detail-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 22px 0 9px;
  color: var(--ink-3);
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.16em;
}

.detail-title::after {
  content: '';
  flex: 1;
  min-width: 10px;
  height: 1px;
  background: var(--rule);
}

.detail-list {
  display: grid;
  margin: 0;
}

.detail-row {
  display: grid;
  grid-template-columns: minmax(0, auto) minmax(0, 1fr);
  align-items: baseline;
  gap: 12px;
  padding: 8px 0;
  color: var(--ink);
}

.detail-row + .detail-row {
  border-top: 1px solid var(--hairline);
}

.detail-row dt {
  color: var(--ink-3);
  font-size: 12.5px;
  white-space: nowrap;
}

.detail-row dd {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  text-align: right;
  overflow-wrap: anywhere;
}

.raw {
  margin-top: 10px;
}

.raw summary {
  padding: 4px 0;
  color: var(--ink-3);
  font-size: 11.5px;
  cursor: pointer;
}

.raw .detail-json {
  margin-top: 6px;
}

.detail-json {
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-sm);
  background: rgba(255, 253, 250, 0.7);
  color: var(--ink-2);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

</style>
