<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { Check, X } from 'lucide-vue-next'
import StatusPill from '@/components/StatusPill.vue'
import { confirmDraft, rejectDraft } from '@/api/drafts'
import type { Draft, DraftType } from '@/api/types'
import { useToast } from '@/composables/useToast'
import { formatDateTime } from '@/utils/datetime'
import { draftStatusMeta, draftTypeIcon, draftTypeLabel } from '@/utils/meta'

const props = defineProps<{ draft: Draft }>()

const emit = defineEmits<{ resolved: [id: number, status: 'confirmed' | 'rejected'] }>()

const toast = useToast()
const busy = ref(false)
const confirming = ref(false)
let confirmTimer = 0

/** 每种草稿确认后真正发生的事，写清楚，别让人猜 */
const ACTION_LABEL: Record<DraftType, string> = {
  REPLY: '发送',
  FILE: '发送',
  MEMO: '记下',
  FACT: '记下',
}

/** 回复和文件是「以你的身份发出去」，发出去就收不回，必须先问一句 */
const IRREVERSIBLE: DraftType[] = ['REPLY', 'FILE']

const payload = computed<Record<string, unknown>>(() => props.draft.payload ?? {})
const isPending = computed(() => props.draft.status === 'PENDING')
const typeIcon = computed(() => draftTypeIcon(props.draft.type))
const statusMeta = computed(() => draftStatusMeta(props.draft.status))
const needsConfirm = computed(() => IRREVERSIBLE.includes(props.draft.type))
const confirmHint = computed(() =>
  props.draft.type === 'FILE' ? '文件会直接发到对应会话，发出后无法撤回。' : '这条回复会以你的身份发出，发出后无法撤回。',
)

onBeforeUnmount(() => window.clearTimeout(confirmTimer))

function requestConfirm() {
  if (!needsConfirm.value) {
    void resolve('confirm')
    return
  }
  confirming.value = true
  window.clearTimeout(confirmTimer)
  confirmTimer = window.setTimeout(() => (confirming.value = false), 6000)
}

function cancelConfirm() {
  window.clearTimeout(confirmTimer)
  confirming.value = false
}

function field(key: string): string {
  const value = payload.value[key]
  return typeof value === 'string' ? value : ''
}

/**
 * 回复草稿要让人看清「在回谁」。
 * 新草稿把对方那句话存在 payload.sourceContent；老草稿没有，只能退回 title——
 * 但 title 要是和正文一样（旧版本拿正文前 20 字当标题），就不能再显示一遍。
 */
const context = computed(() => {
  if (props.draft.type !== 'REPLY') return ''
  const source = field('sourceContent').trim()
  if (source) return source
  const title = (props.draft.title ?? '').trim()
  return title && title !== field('content').trim() ? title : ''
})

const headline = computed(() => {
  const { title, type } = props.draft
  // 回复的主句永远是「要发出去的那句话」，标题只是摘要，不能替代它
  if (type === 'REPLY') return field('content') || title || '一条回复'
  if (title) return title
  if (type === 'MEMO') return field('title') || '一条备忘'
  if (type === 'FACT') return `${field('factKey') || '事实'}：${field('factValue')}`
  if (type === 'FILE') return field('fileName') || '一个文件'
  return draftTypeLabel(type)
})

const preview = computed(() => {
  const { type } = props.draft
  // REPLY 的正文已经是主句，再放一遍预览就是同一句话出现两遍
  if (type === 'REPLY') return ''
  if (type === 'MEMO') {
    const parts = [field('content'), field('dueTime') ? formatDateTime(field('dueTime')) : '']
    return parts.filter(Boolean).join(' ／ ')
  }
  if (type === 'FACT') {
    const confidence = payload.value.confidence
    return typeof confidence === 'number' ? `把握 ${Math.round(confidence * 100)}%` : ''
  }
  if (type === 'FILE') return field('taskRef') ? `任务 ${field('taskRef')}` : ''
  return ''
})

async function resolve(action: 'confirm' | 'reject') {
  busy.value = true
  try {
    if (action === 'confirm') {
      await confirmDraft(props.draft.id)
      toast.success('已确认')
      emit('resolved', props.draft.id, 'confirmed')
    } else {
      await rejectDraft(props.draft.id)
      toast.push('已拒绝，草稿留档')
      emit('resolved', props.draft.id, 'rejected')
    }
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <article class="draft" :class="{ closed: !isPending }">
    <header class="head">
      <span class="type">
        <component :is="typeIcon" :size="12" :stroke-width="2" aria-hidden="true" />
        {{ draftTypeLabel(draft.type) }}
      </span>
      <span class="time num">{{ formatDateTime(draft.createdAt) }}</span>
      <StatusPill v-if="!isPending" :label="statusMeta.label" :tone="statusMeta.tone" :dot="false" />
    </header>

    <p v-if="context" class="context">
      <span class="context-label">对方</span>
      {{ context }}
    </p>
    <p class="headline">{{ headline }}</p>
    <p v-if="preview" class="preview clamp-3">{{ preview }}</p>

    <div v-if="isPending && confirming" class="confirm">
      <p class="confirm-text">{{ confirmHint }}</p>
      <div class="actions">
        <button type="button" class="btn btn-sm btn-ghost" :disabled="busy" @click="cancelConfirm">
          再想想
        </button>
        <button type="button" class="btn btn-sm btn-primary" :disabled="busy" @click="resolve('confirm')">
          <Check :size="14" :stroke-width="2.6" aria-hidden="true" />
          确认{{ ACTION_LABEL[draft.type] }}
        </button>
      </div>
    </div>

    <div v-else-if="isPending" class="actions">
      <button type="button" class="btn btn-sm btn-primary" :disabled="busy" @click="requestConfirm">
        <Check :size="14" :stroke-width="2.6" aria-hidden="true" />
        {{ ACTION_LABEL[draft.type] }}
      </button>
      <button type="button" class="btn btn-sm btn-ghost" :disabled="busy" @click="resolve('reject')">
        <X :size="14" :stroke-width="2.4" aria-hidden="true" />
        拒绝
      </button>
    </div>
  </article>
</template>

<style scoped>
.draft {
  padding: 14px 15px 15px;
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-card);
}

.draft.closed {
  background: rgba(255, 255, 255, 0.62);
  box-shadow: none;
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 9px;
}

.type {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border-radius: var(--r-pill);
  background: var(--card-inverse);
  color: #fff;
  font-size: 11px;
  font-weight: 500;
}

.closed .type {
  background: rgba(46, 38, 26, 0.07);
  color: var(--ink-2);
}

.time {
  color: var(--ink-3);
  font-size: 11.5px;
}

.head .status {
  margin-left: auto;
}

/* 对方那句话：引线在左，压低一档，别和要发出去的正文抢注意力 */
.context {
  display: -webkit-box;
  margin-bottom: 7px;
  padding-left: 9px;
  border-left: 2px solid var(--hairline);
  color: var(--ink-3);
  font-size: 12.5px;
  line-height: 1.55;
  overflow: hidden;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.context-label {
  margin-right: 5px;
  font-weight: 600;
}

.headline {
  font-size: 15.5px;
  font-weight: 500;
  line-height: 1.42;
  letter-spacing: -0.01em;
  overflow-wrap: anywhere;
}

.closed .headline {
  color: var(--ink-2);
}

.preview {
  margin-top: 5px;
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.confirm {
  margin-top: 12px;
  padding: 11px 12px 12px;
  border-radius: var(--r-md);
  background: var(--danger-wash);
}

.confirm-text {
  color: var(--danger);
  font-size: 12.5px;
  line-height: 1.55;
}

.confirm .actions {
  margin-top: 10px;
}
</style>
