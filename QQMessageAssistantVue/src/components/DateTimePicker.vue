<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'
import Sheet from '@/components/Sheet.vue'
import { formatHeadDate, isSameDay, parseLocal, toDatetimeLocalValue } from '@/utils/datetime'

/*
 * 自己的日期时间选择器。
 * 原生的 datetime-local 弹层是浏览器画的：蓝色选中块、蓝色的「清除 / 今天」，
 * 和这套纸面样式完全不是一个语言。这里用月历 + 两个滚轮重做一遍，
 * 选中落墨、今天标点，和表单里那些「小戳」是同一套规则。
 */

const props = defineProps<{
  open: boolean
  /** datetime-local 值，YYYY-MM-DDTHH:mm */
  modelValue: string
}>()

const emit = defineEmits<{ close: []; confirm: [value: string] }>()

const WEEK = ['一', '二', '三', '四', '五', '六', '日']
const HOURS = Array.from({ length: 24 }, (_, i) => i)
const MINUTES = Array.from({ length: 12 }, (_, i) => i * 5)
/** 滚轮一格的高度，必须和 CSS 里的 44px 对上 */
const ITEM_H = 44

const draft = ref(new Date())
const viewMonth = ref(startOfMonth(new Date()))
const hour = ref(9)
const minute = ref(0)

const hourEl = ref<HTMLElement | null>(null)
const minuteEl = ref<HTMLElement | null>(null)

function startOfMonth(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), 1)
}

const monthLabel = computed(
  () => `${viewMonth.value.getFullYear()}年${viewMonth.value.getMonth() + 1}月`,
)

/** 周一开头的 6×7 格，前后补上邻月的日子，行高才不会跳 */
const cells = computed(() => {
  const first = viewMonth.value
  const offset = (first.getDay() + 6) % 7
  const start = new Date(first)
  start.setDate(1 - offset)
  return Array.from({ length: 42 }, (_, i) => {
    const day = new Date(start)
    day.setDate(start.getDate() + i)
    return day
  })
})

const today = new Date()

function isOut(day: Date) {
  return day.getMonth() !== viewMonth.value.getMonth()
}

function pickDay(day: Date) {
  draft.value = new Date(day.getFullYear(), day.getMonth(), day.getDate())
  if (isOut(day)) viewMonth.value = startOfMonth(day)
}

function shiftMonth(step: number) {
  viewMonth.value = new Date(
    viewMonth.value.getFullYear(),
    viewMonth.value.getMonth() + step,
    1,
  )
}

/* ── 时间滚轮 ─────────────────────────────────────────────── */

function syncWheels() {
  if (hourEl.value) hourEl.value.scrollTop = hour.value * ITEM_H
  const minuteIndex = Math.max(0, MINUTES.indexOf(minute.value))
  if (minuteEl.value) minuteEl.value.scrollTop = minuteIndex * ITEM_H
}

let scrollTimer = 0

function onWheelScroll(kind: 'hour' | 'minute', event: Event) {
  const el = event.target as HTMLElement
  window.clearTimeout(scrollTimer)
  scrollTimer = window.setTimeout(() => {
    const index = Math.round(el.scrollTop / ITEM_H)
    if (kind === 'hour') hour.value = Math.min(23, Math.max(0, index))
    else minute.value = MINUTES[Math.min(MINUTES.length - 1, Math.max(0, index))]
  }, 90)
}

function setHour(value: number) {
  hour.value = value
  if (hourEl.value) hourEl.value.scrollTo({ top: value * ITEM_H, behavior: 'smooth' })
}

function setMinute(value: number) {
  minute.value = value
  const index = Math.max(0, MINUTES.indexOf(value))
  if (minuteEl.value) minuteEl.value.scrollTo({ top: index * ITEM_H, behavior: 'smooth' })
}

/* ── 开合 ─────────────────────────────────────────────────── */

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    const seed = parseLocal(props.modelValue) ?? new Date(Date.now() + 60 * 60 * 1000)
    draft.value = new Date(seed.getFullYear(), seed.getMonth(), seed.getDate())
    viewMonth.value = startOfMonth(seed)
    hour.value = seed.getHours()
    minute.value = MINUTES.reduce((best, m) => (Math.abs(m - seed.getMinutes()) < Math.abs(best - seed.getMinutes()) ? m : best), MINUTES[0])
    await nextTick()
    syncWheels()
  },
)

onBeforeUnmount(() => window.clearTimeout(scrollTimer))

function confirm() {
  const next = new Date(draft.value)
  next.setHours(hour.value, minute.value, 0, 0)
  emit('confirm', toDatetimeLocalValue(next))
  emit('close')
}

const preview = computed(() => {
  const next = new Date(draft.value)
  next.setHours(hour.value, minute.value, 0, 0)
  return `${formatHeadDate(next)} · ${String(hour.value).padStart(2, '0')}:${String(minute.value).padStart(2, '0')}`
})
</script>

<template>
  <Sheet
    :open="open"
    nested
    height="88%"
    title="选择时间"
    :description="preview"
    @close="emit('close')"
  >
    <div class="cal">
      <div class="cal-head">
        <button type="button" class="cal-nav" aria-label="上个月" @click="shiftMonth(-1)">
          <ChevronLeft :size="16" :stroke-width="2" aria-hidden="true" />
        </button>
        <span class="cal-month num">{{ monthLabel }}</span>
        <button type="button" class="cal-nav" aria-label="下个月" @click="shiftMonth(1)">
          <ChevronRight :size="16" :stroke-width="2" aria-hidden="true" />
        </button>
      </div>

      <div class="cal-week" aria-hidden="true">
        <span v-for="w in WEEK" :key="w">{{ w }}</span>
      </div>

      <div class="cal-grid">
        <button
          v-for="day in cells"
          :key="day.getTime()"
          type="button"
          class="cal-day num"
          :class="{ out: isOut(day), today: isSameDay(day, today) }"
          :aria-pressed="isSameDay(day, draft)"
          :aria-label="`${day.getMonth() + 1}月${day.getDate()}日`"
          @click="pickDay(day)"
        >
          {{ day.getDate() }}
        </button>
      </div>
    </div>

    <div class="time-block">
      <span class="block-label">时刻</span>
      <div class="wheels">
        <span class="wheel-band" aria-hidden="true" />
        <div
          ref="hourEl"
          class="wheel"
          role="listbox"
          aria-label="小时"
          @scroll.passive="onWheelScroll('hour', $event)"
        >
          <span class="wheel-gap" aria-hidden="true" />
          <button
            v-for="h in HOURS"
            :key="h"
            type="button"
            class="wheel-item num"
            role="option"
            :aria-selected="hour === h"
            @click="setHour(h)"
          >
            {{ String(h).padStart(2, '0') }}
          </button>
          <span class="wheel-gap" aria-hidden="true" />
        </div>

        <span class="wheel-colon" aria-hidden="true">:</span>

        <div
          ref="minuteEl"
          class="wheel"
          role="listbox"
          aria-label="分钟"
          @scroll.passive="onWheelScroll('minute', $event)"
        >
          <span class="wheel-gap" aria-hidden="true" />
          <button
            v-for="m in MINUTES"
            :key="m"
            type="button"
            class="wheel-item num"
            role="option"
            :aria-selected="minute === m"
            @click="setMinute(m)"
          >
            {{ String(m).padStart(2, '0') }}
          </button>
          <span class="wheel-gap" aria-hidden="true" />
        </div>
      </div>
    </div>

    <template #footer>
      <button type="button" class="btn btn-ghost" @click="emit('close')">取消</button>
      <button type="button" class="btn btn-primary" @click="confirm">确定</button>
    </template>
  </Sheet>
</template>

<style scoped>
/* ── 月历 ─────────────────────────────────────────────────── */

.cal-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 0 10px;
}

.cal-month {
  flex: 1;
  font-family: var(--font-display);
  font-size: 17px;
  font-weight: 400;
  letter-spacing: 0.01em;
  text-align: center;
}

.cal-nav {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 1px solid var(--rule);
  border-radius: 50%;
  background: transparent;
  color: var(--ink-2);
  cursor: pointer;
  transition: border-color var(--dur-1) var(--ease-out), color var(--dur-1) var(--ease-out);
}

.cal-nav:active {
  border-color: var(--ink);
  color: var(--ink);
}

.cal-week {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 2px;
  padding-bottom: 4px;
}

.cal-week span {
  color: var(--ink-3);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-align: center;
}

.cal-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 2px;
}

.cal-day {
  position: relative;
  height: 40px;
  border: 0;
  border-radius: var(--r-xs);
  background: transparent;
  color: var(--ink);
  font-size: 14px;
  cursor: pointer;
  transition: background-color var(--dur-1) var(--ease-out), color var(--dur-1) var(--ease-out);
}

/* 今天：数字下面一个点，不抢选中块的墨 */
.cal-day.today::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: 7px;
  width: 3px;
  height: 3px;
  margin-left: -1.5px;
  border-radius: 50%;
  background: var(--accent);
}

.cal-day.out {
  color: var(--ink-3);
  opacity: 0.5;
}

.cal-day[aria-pressed="true"] {
  background: var(--card-inverse);
  color: var(--card);
}

.cal-day[aria-pressed="true"]::after {
  background: var(--card);
}

/* ── 时刻滚轮 ─────────────────────────────────────────────── */

.time-block {
  margin-top: 14px;
  padding-top: 11px;
  border-top: 1px solid var(--hairline);
}

.block-label {
  display: block;
  margin-bottom: 2px;
  color: var(--ink-3);
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.16em;
}

.wheels {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: max-content;
  margin: 0 auto;
  padding: 0 18px;
  height: 132px;
}

/* 中间那条选中带：两条发丝线，位置和滚轮的 44px 格子对齐 */
.wheel-band {
  position: absolute;
  left: 18px;
  right: 18px;
  top: 44px;
  height: 44px;
  border-top: 1px solid var(--rule);
  border-bottom: 1px solid var(--rule);
  pointer-events: none;
}

.wheel {
  width: 76px;
  height: 132px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scroll-snap-type: y mandatory;
  scrollbar-width: none;
}

.wheel::-webkit-scrollbar {
  display: none;
}

.wheel-gap {
  display: block;
  height: 44px;
}

.wheel-item {
  display: grid;
  place-items: center;
  width: 100%;
  height: 44px;
  border: 0;
  background: transparent;
  color: var(--ink-3);
  font-family: var(--font-display);
  font-size: 20px;
  font-variant-numeric: lining-nums tabular-nums;
  scroll-snap-align: center;
  cursor: pointer;
  transition: color var(--dur-1) var(--ease-out);
}

.wheel-item[aria-selected="true"] {
  color: var(--ink);
}

.wheel-colon {
  width: 14px;
  color: var(--ink-3);
  font-family: var(--font-display);
  font-size: 20px;
  text-align: center;
}
</style>
