<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { X } from 'lucide-vue-next'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    description?: string
    /** 占屏高度上限，默认 88% */
    height?: string
    /** View Transitions 的共享元素名 */
    vtName?: string
    /** 共享元素接管动画时，跳过自己的进出场 */
    instant?: boolean
    /** 叠在另一个抽屉上面（选择器之类）：不锁背景，Esc 只关自己 */
    nested?: boolean
  }>(),
  { description: '', height: '88%', vtName: '', instant: false, nested: false },
)

const emit = defineEmits<{ close: [] }>()

const panel = ref<HTMLElement | null>(null)
let lastFocused: HTMLElement | null = null
let scroller: HTMLElement | null = null
let appRoot: HTMLElement | null = null

/* ── 下拉关闭 ─────────────────────────────────────────────── */

const dragY = ref(0)
const dragging = ref(false)
let startY = 0
let lastY = 0
let lastTime = 0
let velocity = 0

function onDragStart(event: PointerEvent) {
  if (event.pointerType === 'mouse' && event.button !== 0) return
  dragging.value = true
  startY = event.clientY
  lastY = event.clientY
  lastTime = performance.now()
  velocity = 0
  ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
}

function onDragMove(event: PointerEvent) {
  if (!dragging.value) return
  const now = performance.now()
  const dt = now - lastTime || 16
  velocity = (event.clientY - lastY) / dt
  lastY = event.clientY
  lastTime = now
  dragY.value = Math.max(0, event.clientY - startY)
}

function onDragEnd() {
  if (!dragging.value) return
  dragging.value = false
  // 位移够远，或者甩得够快，就关掉
  if (dragY.value > 120 || velocity > 0.55) {
    dragY.value = 0
    close()
  } else {
    dragY.value = 0
  }
}

const panelStyle = computed(() => ({
  transform: dragY.value ? `translateY(${dragY.value}px)` : undefined,
  viewTransitionName: props.vtName || undefined,
}))

const scrimStyle = computed(() => ({
  opacity: dragY.value ? Math.max(0.2, 1 - dragY.value / 420) : 1,
}))

/* ── 键盘避让 ─────────────────────────────────────────────── */

const kbInset = ref(0)

function onViewportResize() {
  const vv = window.visualViewport
  if (!vv) return
  kbInset.value = Math.max(0, window.innerHeight - vv.height - vv.offsetTop)
}

/* ── 打开 / 关闭 ──────────────────────────────────────────── */

function close() {
  emit('close')
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    // 嵌套时用捕获阶段先吃掉，否则会连着把下面那层抽屉一起关掉
    if (props.nested) event.stopImmediatePropagation()
    else event.stopPropagation()
    close()
    return
  }
  if (event.key !== 'Tab' || !panel.value) return

  const focusable = panel.value.querySelectorAll<HTMLElement>(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  )
  const items = Array.from(focusable).filter((el) => el.offsetParent !== null)
  if (!items.length) return

  const first = items[0]
  const last = items[items.length - 1]
  const active = document.activeElement as HTMLElement | null
  const outside = !panel.value.contains(active)

  if (event.shiftKey && (active === first || outside)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && (active === last || outside)) {
    event.preventDefault()
    first.focus()
  }
}

let locked = false

function lockBackground(lock: boolean) {
  if (!scroller) scroller = document.querySelector<HTMLElement>('.screen')
  if (!appRoot) appRoot = document.querySelector<HTMLElement>('#app > .app')
  if (scroller) scroller.style.overflowY = lock ? 'hidden' : ''
  // 让读屏和 Tab 都进不到背景
  if (appRoot) (appRoot as HTMLElement & { inert?: boolean }).inert = lock
}

watch(
  () => props.open,
  async (open) => {
    if (open) {
      lastFocused = document.activeElement as HTMLElement | null
      dragY.value = 0
      kbInset.value = 0
      // 嵌套抽屉不去动背景锁：下面那层还开着，锁是它的
      if (!props.nested) {
        lockBackground(true)
        locked = true
      }
      window.addEventListener('keydown', onKeydown, props.nested)
      window.visualViewport?.addEventListener('resize', onViewportResize)
      await nextTick()
      panel.value
        ?.querySelector<HTMLElement>('input, textarea, select, button')
        ?.focus({ preventScroll: true })
    } else {
      cleanup()
    }
  },
)

function cleanup() {
  if (locked) {
    lockBackground(false)
    locked = false
  }
  window.removeEventListener('keydown', onKeydown, props.nested)
  window.visualViewport?.removeEventListener('resize', onViewportResize)
  lastFocused?.focus?.({ preventScroll: true })
  lastFocused = null
}

onBeforeUnmount(cleanup)
</script>

<template>
  <Teleport to="#app">
    <Transition name="sheet" :css="!instant">
      <div
        v-if="open"
        class="layer"
        :class="{ nested }"
        :style="{ paddingBottom: `${kbInset}px` }"
      >
        <div class="scrim" :style="scrimStyle" @click="close" />

        <section
          ref="panel"
          class="panel"
          :class="{ dragging }"
          role="dialog"
          aria-modal="true"
          :aria-label="title"
          :style="[{ maxHeight: height }, panelStyle]"
        >
          <button type="button" class="grip-close" aria-label="关闭" @click="close">
            <X :size="17" :stroke-width="2.2" aria-hidden="true" />
          </button>

          <div
            class="drag-zone"
            @pointerdown="onDragStart"
            @pointermove="onDragMove"
            @pointerup="onDragEnd"
            @pointercancel="onDragEnd"
          >
            <!--
              默认是「衬线标题 + 一行说明」。详情类抽屉需要更重的报头
              （印章 / 细线 / 日期 / 更大的标题），由使用方用 #head 接管。
            -->
            <header class="head">
              <slot name="head">
                <h2 class="title">{{ title }}</h2>
                <p v-if="description" class="desc">{{ description }}</p>
              </slot>
            </header>
          </div>

          <div class="body">
            <slot />
          </div>

          <footer v-if="$slots.footer" class="foot">
            <slot name="footer" />
          </footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.layer {
  position: absolute;
  inset: 0;
  z-index: 80;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  transition: padding-bottom 240ms var(--ease-out);
}

/* 叠在另一个抽屉上时抬一层，遮罩也浅一些，别把下面压成一片黑 */
.layer.nested {
  z-index: 90;
}

.layer.nested .scrim {
  background: rgba(28, 22, 16, 0.28);
  backdrop-filter: blur(1.5px);
  -webkit-backdrop-filter: blur(1.5px);
}

.scrim {
  position: absolute;
  inset: 0;
  background: rgba(28, 22, 16, 0.44);
  backdrop-filter: blur(3px);
  -webkit-backdrop-filter: blur(3px);
  transition: opacity 220ms var(--ease-out);
}

.panel {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  border-radius: var(--r-sheet) var(--r-sheet) 0 0;
  /*
   * 抽屉只有一种材质：纸。
   * 之前分「深色（看）/ 纸白（写）」两种，结果详情抽屉一拉开就是一整块近黑，
   * 和外面的暖纸页面对撞。材质统一后，深浅只由内容和留白决定。
   */
  background: linear-gradient(180deg, var(--bg-top) 0%, var(--bg) 42%, var(--bg) 100%);
  border-top: 1px solid var(--hairline);
  color: var(--ink);
  box-shadow: 0 -18px 60px -30px rgba(28, 22, 16, 0.55);
  transition: transform var(--dur-3) var(--ease-spring);
}

.panel.dragging {
  transition: none;
}

.grip-close {
  position: absolute;
  top: -20px;
  left: 50%;
  transform: translateX(-50%);
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 50%;
  background: var(--card);
  border: 1px solid var(--hairline);
  color: var(--ink);
  box-shadow: 0 6px 20px -10px rgba(46, 38, 26, 0.5);
  cursor: pointer;
  z-index: 2;
}

.drag-zone {
  padding: 22px 22px 0;
  touch-action: none;
  cursor: grab;
}

.drag-zone:active {
  cursor: grabbing;
}

.head {
  padding: 6px 0 14px;
}

/* 抽屉标题也用衬线：它和页头标题是同一层「展示级文字」，同一套字体 */
.title {
  font-family: var(--font-display);
  font-size: 23px;
  font-weight: 400;
  letter-spacing: -0.005em;
  line-height: 1.28;
  overflow-wrap: anywhere;
}

.desc {
  margin-top: 7px;
  font-size: 12.5px;
  line-height: 1.6;
  opacity: 0.6;
}

.body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 22px 8px;
}

/*
 * 动作区顶上一条发丝线。
 * 没有它，正文滚到底时最后一行会被硬生生切在半截，看着像渲染坏了；
 * 有线之后，同一件事读起来是「内容滚到一条栏下面」，是正常的滚动暗示。
 */
.foot {
  flex: none;
  display: flex;
  gap: 10px;
  padding: 14px 22px calc(18px + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid var(--hairline);
}

.foot :deep(.btn) {
  flex: 1;
}

.sheet-enter-active,
.sheet-leave-active {
  transition: opacity 0.3s var(--ease-out);
}

.sheet-enter-active .panel {
  transition: transform var(--dur-3) var(--ease-spring);
}

.sheet-leave-active .panel {
  transition: transform 300ms var(--ease-out);
}

.sheet-enter-from,
.sheet-leave-to {
  opacity: 0;
}

.sheet-enter-from .panel,
.sheet-leave-to .panel {
  transform: translateY(100%);
}
</style>
