<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { ArrowUp, MessageCircleQuestion } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import PageHead from '@/components/PageHead.vue'
import {
  ask,
  type AskScope,
  type ClarifyCandidate,
  type ClarifyState,
  type Evidence,
} from '@/api/ask'
import { useToast } from '@/composables/useToast'

interface Bubble {
  id: number
  role: 'user' | 'assistant'
  text: string
  /** true=模型引用了候选集之外的东西，结论作废，只剩证据 */
  degraded?: boolean
  evidence?: Evidence[]
  clarify?: ClarifyState
  /** 触发这次反问的原问题：认完人要用它重问，存在气泡上而不是靠下标回找 */
  question?: string
  /** 这一次作答自己的检索范围，多轮之间互不影响 */
  scope?: AskScope
  /** 答不出时在气泡下展示通道计数；成功作答不展示 */
  showScope?: boolean
  /** 成功作答时后端附加的说明（如「未登记的联系人」），单独提示、不覆盖答案 */
  scopeNotice?: string
}

const toast = useToast()

const bubbles = ref<Bubble[]>([])
const question = ref('')
const loading = ref(false)
const flowEl = ref<HTMLElement | null>(null)
const inputEl = ref<HTMLInputElement | null>(null)

let seq = 0

const EXAMPLES = ['阿黄什么时候考试', '上周群里说的班会在哪开', '我学号是多少']

const canSend = computed(() => question.value.trim().length > 0 && !loading.value)

const EVIDENCE_LABELS: Record<Evidence['type'], string> = {
  msg: '原文',
  note: '事实',
  memo: '备忘',
  file: '文件',
}

/** 通道展示名与固定顺序；值为 null 的通道不展示，0 照常展示 */
const CHANNEL_LABELS: Array<[key: string, label: string]> = [
  ['note', '结构化事实'],
  ['msg-person', '对话记录'],
  ['msg-mention', '提及'],
  ['msg-lexical', '全文'],
  ['msg-semantic', '语义'],
  ['memo', '备忘'],
  ['file', '文件'],
]

/** 哪些证据的原文被展开了；键带气泡 id，多轮之间不会串 */
const openQuotes = ref<Record<string, boolean>>({})

function quoteKey(bubbleId: number, item: Evidence) {
  return `${bubbleId}:${item.type}:${item.id}`
}

/** 原文存在且与 content 不同才值得折叠；相同就不做一个多余的展开器 */
function expandableQuote(item: Evidence): string | null {
  const quote = item.quote?.trim()
  if (!quote) return null
  return quote === item.content?.trim() ? null : quote
}

function isQuoteOpen(key: string) {
  return !!openQuotes.value[key]
}

function toggleQuote(key: string) {
  openQuotes.value[key] = !openQuotes.value[key]
}

function channelCounts(scope: AskScope) {
  return CHANNEL_LABELS.flatMap(([key, label]) => {
    const value = scope.channels?.[key]
    return typeof value === 'number' ? [{ key, label, value }] : []
  })
}

/** 顶部提示只反映最近一次作答；更早的 scope 留在各自气泡上 */
const latestScope = computed<AskScope | null>(() => {
  for (let i = bubbles.value.length - 1; i >= 0; i -= 1) {
    const scope = bubbles.value[i].scope
    if (scope) return scope
  }
  return null
})

const semanticDisabled = computed(() => latestScope.value?.semantic === false)
const lagCount = computed(() => latestScope.value?.lag ?? 0)

function push(bubble: Omit<Bubble, 'id'>) {
  bubbles.value.push({ ...bubble, id: ++seq })
}

async function scrollToEnd() {
  await nextTick()
  flowEl.value?.lastElementChild?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
}

/** 一次提问 + 一次作答；clarify 只在用户刚认过人时带上 */
async function run(text: string, clarify?: { alias: string; senderId: number; from?: string }) {
  loading.value = true
  try {
    const response = await ask(text, clarify)
    // 每轮响应自带 scope，挂在产出它的气泡上，绝不跨轮复用
    const scope = response.scope
    if (response.needClarify) {
      push({
        role: 'assistant',
        text: '',
        clarify: response.needClarify,
        question: text,
        scope,
      })
    } else if (response.degraded) {
      // 结论不可追溯：保持原行为，只列证据，不拿 scope.message 覆盖
      push({
        role: 'assistant',
        text: '没找到能对上的原文，只列了候选。',
        degraded: true,
        evidence: response.evidence,
        scope,
      })
    } else if (!response.answer) {
      // 第 4–7 条终态：后端 scope.message 才是用户该看到的话，缺省才退回旧文案。
      // 它已经作为气泡正文出现，所以不再重复挂 scopeNotice。
      const message = scope?.message?.trim()
      push({
        role: 'assistant',
        text:
          message ||
          (response.evidence?.length
            ? '没有直接结论，下面是翻到的原文。'
            : '翻了一遍，没有关于这件事的记录。'),
        evidence: response.evidence,
        scope,
        showScope: true,
      })
    } else {
      // 成功作答也不吞掉后端说明（§7.2「未登记的联系人，按名字检索」），
      // 但只做旁注，不改变答案本身；快照在气泡上，不跨轮共享。
      const notice = scope?.message?.trim()
      push({
        role: 'assistant',
        text: response.answer,
        evidence: response.evidence,
        scope,
        scopeNotice: notice || undefined,
      })
    }
  } catch (e) {
    push({ role: 'assistant', text: e instanceof Error ? e.message : '查询失败' })
  } finally {
    loading.value = false
    await scrollToEnd()
    inputEl.value?.focus()
  }
}

async function submit() {
  if (!canSend.value) return
  const text = question.value.trim()
  question.value = ''
  push({ role: 'user', text })
  await run(text)
}

function useExample(example: string) {
  question.value = example
  inputEl.value?.focus()
}

async function pickCandidate(candidate: ClarifyCandidate, bubble: Bubble) {
  const state = bubble.clarify
  if (!state || loading.value) return
  const original = bubble.question
  if (!original) {
    toast.error('找不到原问题了，请重新问一次')
    return
  }
  bubble.clarify = undefined
  const who = candidate.relation ? `${candidate.alias}（${candidate.relation}）` : candidate.alias
  push({ role: 'user', text: `「${state.alias}」就是 ${who}` })
  await run(original, {
    alias: state.alias,
    senderId: candidate.senderId,
    from: candidate.relation ?? '',
  })
}

async function submitUnknown(bubble: Bubble, senderId: number, relation: string) {
  const state = bubble.clarify
  if (!state || loading.value) return
  const original = bubble.question
  if (!original) {
    toast.error('找不到原问题了，请重新问一次')
    return
  }
  bubble.clarify = undefined
  push({ role: 'user', text: `「${state.alias}」的 QQ 是 ${senderId}` })
  await run(original, { alias: state.alias, senderId, from: relation })
}

const unknownForm = ref({ senderId: '', relation: '' })
const unknownError = ref('')

async function confirmUnknown(bubble: Bubble) {
  if (!bubble.clarify) return
  const raw = unknownForm.value.senderId.trim()
  if (!/^\d{5,12}$/.test(raw)) {
    unknownError.value = 'QQ 号是 5–12 位数字'
    return
  }
  unknownError.value = ''
  const id = Number(raw)
  const relation = unknownForm.value.relation.trim()
  unknownForm.value = { senderId: '', relation: '' }
  await submitUnknown(bubble, id, relation)
}
</script>

<template>
  <div class="page ask-page">
    <PageHead
      eyebrow="历史查询"
      title="Ask"
      sub="只在聊天记录、事实、备忘和文件里找答案；找不到就说找不到，不编。"
    />

    <!-- 顶部只反映最近一次作答的检索状态；多轮里旧答复的 scope 留在各自气泡上 -->
    <div v-if="semanticDisabled || lagCount > 0" class="scope-notices">
      <p v-if="semanticDisabled" class="scope-notice">
        语义检索未启用（模型未就绪），当前只做全文与结构化检索
      </p>
      <p v-if="lagCount > 0" class="scope-notice">
        有 {{ lagCount }} 条新消息尚未建立语义索引
      </p>
    </div>

    <form class="ask" @submit.prevent="submit">
      <MessageCircleQuestion
        :size="17"
        :stroke-width="1.9"
        class="ask-icon"
        aria-hidden="true"
      />
      <input
        ref="inputEl"
        v-model="question"
        type="text"
        class="ask-input"
        placeholder="谁什么时候说了什么…"
        enterkeyhint="send"
        maxlength="200"
        aria-label="要问的问题"
      />
      <button type="submit" class="ask-send" :disabled="!canSend" aria-label="发送">
        <ArrowUp :size="16" :stroke-width="2.6" aria-hidden="true" />
      </button>
    </form>

    <EmptyState
      v-if="!bubbles.length"
      :icon="MessageCircleQuestion"
      class="ask-empty"
      title="问一句，翻给你看"
      hint="谁哪天说过什么、班会在哪开、我学号是多少——都能翻到原文。"
    >
      <div class="examples">
        <button
          v-for="example in EXAMPLES"
          :key="example"
          type="button"
          class="example"
          @click="useExample(example)"
        >
          {{ example }}
        </button>
      </div>
    </EmptyState>

    <ol ref="flowEl" class="flow" aria-live="polite">
      <li v-for="bubble in bubbles" :key="bubble.id" class="turn" :class="bubble.role">
        <p v-if="bubble.text" class="bubble">{{ bubble.text }}</p>

        <!-- 成功作答时的附带说明：只做旁注，不顶替答案 -->
        <p v-if="bubble.scopeNotice" class="scope-note">{{ bubble.scopeNotice }}</p>

        <div v-if="bubble.clarify" class="clarify">
          <p class="clarify-q">
            <template v-if="bubble.clarify.occurrences && bubble.clarify.occurrences > 0">
              记录里出现过 {{ bubble.clarify.occurrences }} 次，能说说是谁吗？
            </template>
            <template v-else>
              你说的「{{ bubble.clarify.alias }}」是哪一个？
            </template>
          </p>

          <div v-if="bubble.clarify.candidates.length" class="choices">
            <button
              v-for="candidate in bubble.clarify.candidates"
              :key="candidate.senderId"
              type="button"
              class="choice"
              :disabled="loading"
              @click="pickCandidate(candidate, bubble)"
            >
              <span class="choice-name">
                {{ candidate.alias }}
                <span v-if="candidate.relation" class="choice-relation">{{ candidate.relation }}</span>
              </span>
              <span class="choice-hint num">{{ candidate.hint }}</span>
            </button>
          </div>

          <div v-else class="unknown">
            <p class="unknown-hint">我还不认识 TA，知道 QQ 号就能记住，下次不用再问。</p>
            <div class="unknown-form">
              <input
                v-model="unknownForm.senderId"
                type="text"
                inputmode="numeric"
                class="unknown-input"
                placeholder="QQ 号"
                maxlength="12"
                aria-label="QQ 号"
              />
              <input
                v-model="unknownForm.relation"
                type="text"
                class="unknown-input"
                placeholder="关系（可空）"
                maxlength="16"
                aria-label="关系"
              />
              <button
                type="button"
                class="btn btn-sm btn-primary"
                :disabled="loading"
                @click="confirmUnknown(bubble)"
              >
                记住
              </button>
            </div>
            <p v-if="unknownError" class="unknown-error" role="alert">{{ unknownError }}</p>
          </div>
        </div>

        <ul v-if="bubble.evidence?.length" class="evidence">
          <li v-for="item in bubble.evidence" :key="`${item.type}:${item.id}`" class="ev">
            <!-- 有原文时整张证据卡就是展开按钮：鼠标点卡片、键盘 Tab + Enter 都能展开 -->
            <component
              :is="expandableQuote(item) ? 'button' : 'div'"
              :type="expandableQuote(item) ? 'button' : undefined"
              class="ev-card"
              :class="{ 'ev-card-toggle': !!expandableQuote(item) }"
              :aria-expanded="
                expandableQuote(item) ? isQuoteOpen(quoteKey(bubble.id, item)) : undefined
              "
              @click="expandableQuote(item) && toggleQuote(quoteKey(bubble.id, item))"
            >
              <span class="ev-head">
                <span class="ev-type">{{ EVIDENCE_LABELS[item.type] ?? item.type }}</span>
                <span class="ev-label">{{ item.label }}</span>
              </span>
              <span v-if="item.source" class="ev-source">{{ item.source }}</span>
              <span class="ev-body">{{ item.content }}</span>
              <span
                v-if="expandableQuote(item)"
                class="ev-quote"
                :class="{ open: isQuoteOpen(quoteKey(bubble.id, item)) }"
                :aria-hidden="!isQuoteOpen(quoteKey(bubble.id, item))"
              >{{ expandableQuote(item) }}</span>
              <span v-if="expandableQuote(item)" class="ev-quote-hint" aria-hidden="true">
                {{ isQuoteOpen(quoteKey(bubble.id, item)) ? '收起原文' : '展开原文' }}
              </span>
            </component>
          </li>
        </ul>

        <!-- 答不出时补上各通道命中数；scope.message 已作为气泡文案呈现，不重复。
             所有通道都是 null 时整块不渲染，免得留下一条空发丝线。 -->
        <div
          v-if="bubble.showScope && bubble.scope && channelCounts(bubble.scope).length"
          class="scope"
        >
          <ul class="scope-channels" aria-label="各检索通道命中数">
            <li
              v-for="channel in channelCounts(bubble.scope)"
              :key="channel.key"
              class="scope-channel"
            >
              <span class="scope-channel-label">{{ channel.label }}</span>
              <span class="scope-channel-count num">{{ channel.value }}</span>
            </li>
          </ul>
        </div>
      </li>

      <li v-if="loading" class="turn assistant">
        <p class="bubble thinking">正在翻记录…</p>
      </li>
    </ol>
  </div>
</template>

<style scoped>
/* 空态时把提示放在剩余空间里居中，别让它挤在顶部留一大片空白 */
.ask-page {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}

.ask-empty {
  flex: 1;
  align-content: center;
  padding-bottom: 48px;
}

/* ── 检索能力提示 ─────────────────────────────────────────── */

/*
 * 只是说明「这次是怎么检索的」，不打断提问；用一条发丝线压住的纸底，
 * 和输入条共用材质，但更轻。
 */
.scope-notices {
  display: grid;
  gap: 3px;
  margin-bottom: 14px;
  padding: 8px 12px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-md);
  background: rgba(255, 253, 250, 0.6);
}

.scope-notice {
  color: var(--ink-3);
  font-size: 12px;
  line-height: 1.55;
}

/* 成功作答时的附带说明：比顶部的能力提示更轻，像贴在气泡下的一张小纸条 */
.scope-note {
  align-self: start;
  max-width: 86%;
  padding: 6px 10px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-sm);
  background: rgba(255, 253, 250, 0.6);
  color: var(--ink-3);
  font-size: 12px;
  line-height: 1.55;
}

/* ── 输入 ─────────────────────────────────────────────────── */

/*
 * 输入条和搜索框、卡片共用一套材质：发丝线压边 + 半透明纸底。
 * 原来是「白色大药丸 + 圆形发送键」，那是聊天 App 的形状语言——
 * 但这一页不是聊天，是「查档」：一条纸上的查询行更贴题。
 * 聚焦时才变实白、浮起来一点。
 */
.ask {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 6px 6px 14px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-md);
  background: rgba(255, 253, 250, 0.6);
  transition: background-color var(--dur-2) var(--ease-out),
    border-color var(--dur-2) var(--ease-out),
    box-shadow var(--dur-2) var(--ease-out);
}

.ask:focus-within {
  background: var(--card);
  border-color: var(--rule);
  box-shadow: var(--shadow-card);
}

.ask-icon {
  flex: none;
  color: var(--ink-3);
}

.ask-input {
  flex: 1;
  min-width: 0;
  min-height: 42px;
  border: 0;
  background: transparent;
  color: var(--ink);
  font-size: 15px;
  letter-spacing: -0.01em;
  outline: none;
}

.ask-input::placeholder {
  color: var(--ink-3);
}

.ask-send {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: var(--r-sm);
  background: linear-gradient(158deg, #221f26 0%, #16151a 70%);
  color: var(--on-dark);
  cursor: pointer;
  transition: opacity var(--dur-1) var(--ease-out),
    transform var(--dur-1) var(--ease-spring);
  flex: none;
}

.ask-send:active:not(:disabled) {
  transform: scale(0.92);
}

.ask-send:disabled {
  opacity: 0.35;
  cursor: default;
}

/* ── 示例 ─────────────────────────────────────────────────── */

.examples {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  max-width: 290px;
}

/* 示例是「小戳」不是「药丸」：和页头印章标签同一形状，全站的圆角语言只留两种 */
.example {
  padding: 7px 11px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-xs);
  background: transparent;
  color: var(--ink-2);
  font-size: 12px;
  letter-spacing: 0.01em;
  cursor: pointer;
  transition: background-color var(--dur-1) var(--ease-out),
    border-color var(--dur-1) var(--ease-out);
}

.example:active {
  background: var(--surface);
  border-color: transparent;
}

/* ── 消息流 ───────────────────────────────────────────────── */

.flow {
  display: grid;
  gap: 14px;
  margin: 20px 0 0;
  padding: 0;
  list-style: none;
}

.turn {
  display: grid;
  gap: 8px;
}

.turn.user {
  justify-items: end;
}

.bubble {
  max-width: 86%;
  padding: 11px 14px;
  border-radius: var(--r-lg);
  font-size: 14.5px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.turn.user .bubble {
  background: var(--card-inverse);
  color: var(--on-dark);
  border-bottom-right-radius: 6px;
}

.turn.assistant .bubble {
  background: var(--card);
  box-shadow: var(--shadow-card);
  border-bottom-left-radius: 6px;
}

.turn.assistant .bubble.thinking {
  color: var(--ink-3);
}

/* ── 反问 ─────────────────────────────────────────────────── */

.clarify {
  width: 100%;
  padding: 14px;
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-card);
}

.clarify-q {
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 500;
}

.choices {
  display: grid;
  gap: 8px;
}

.choice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 44px;
  padding: 9px 12px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-md);
  background: transparent;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}

.choice:active {
  background: var(--surface);
}

.choice:disabled {
  opacity: 0.55;
  cursor: default;
}

.choice-name {
  font-size: 14.5px;
}

.choice-relation {
  margin-left: 6px;
  color: var(--ink-3);
  font-size: 12px;
}

.choice-hint {
  color: var(--ink-3);
  font-size: 11.5px;
}

.unknown-hint {
  color: var(--ink-3);
  font-size: 12.5px;
  line-height: 1.6;
}

.unknown-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.unknown-input {
  flex: 1;
  min-width: 110px;
  min-height: 38px;
  padding: 0 12px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-md);
  background: transparent;
  color: var(--ink);
  font-size: 14px;
  outline: none;
}

.unknown-error {
  margin-top: 8px;
  color: var(--danger);
  font-size: 12px;
}

/* ── 证据 ─────────────────────────────────────────────────── */

.evidence {
  display: grid;
  gap: 12px;
  width: 100%;
  margin: 2px 0 0;
  padding: 0;
  list-style: none;
}

/* 证据不套卡片，改成一条细竖线引出，像引文；气泡已经够白了，再堆盒子会吵 */
.ev {
  padding: 1px 0 1px 12px;
  border-left: 2px solid rgba(46, 38, 26, 0.08);
}

/*
 * 证据卡本身可以是 <button>（有原文可展开时），也可以只是 <div>。
 * 先抹平按钮的默认外观，让两者长得一模一样；只在可展开时才给手型。
 */
.ev-card {
  display: block;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
}

.ev-card-toggle {
  cursor: pointer;
}

.ev-head {
  display: flex;
  align-items: baseline;
  gap: 7px;
  margin-bottom: 3px;
}

.ev-type {
  color: var(--ink-2);
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.ev-label {
  color: var(--ink-3);
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 出处是二级信息：压在摘要下面一行，太长就省略 */
.ev-source {
  display: block;
  margin-bottom: 3px;
  color: var(--ink-3);
  font-size: 10.5px;
  letter-spacing: 0.01em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ev-body {
  display: block;
  color: var(--ink-2);
  font-size: 12.8px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

/*
 * 原文默认折叠：用 max-height + opacity 过渡，点击时再展开。
 * 全局的 prefers-reduced-motion 已把过渡压到 0.001ms，这里再兜一道。
 */
.ev-quote {
  display: block;
  padding-left: 9px;
  border-left: 2px solid rgba(46, 38, 26, 0.12);
  color: var(--ink-3);
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
  max-height: 0;
  margin-top: 0;
  opacity: 0;
  overflow: hidden;
  transition: max-height var(--dur-2) var(--ease-out),
    opacity var(--dur-2) var(--ease-out), margin-top var(--dur-2) var(--ease-out);
}

.ev-quote.open {
  max-height: 60rem;
  margin-top: 7px;
  opacity: 1;
}

.ev-quote-hint {
  display: block;
  margin-top: 5px;
  color: var(--ink-3);
  font-size: 10.5px;
}

@media (prefers-reduced-motion: reduce) {
  .ev-quote {
    transition: none;
  }
}

/* ── 检索范围计数 ─────────────────────────────────────────── */

/*
 * 答不出时补一行「各路找了多少」，帮用户判断是该换问法还是库里真没有。
 * 一条发丝线把它和上面的答复隔开，不做卡片，免得喧宾夺主。
 */
.scope {
  width: 100%;
  padding-top: 8px;
  border-top: 1px solid var(--hairline);
}

.scope-channels {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.scope-channel {
  display: flex;
  align-items: baseline;
  gap: 5px;
  color: var(--ink-3);
  font-size: 11px;
}

.scope-channel-count {
  color: var(--ink-2);
  font-size: 11.5px;
  font-weight: 600;
}
</style>
