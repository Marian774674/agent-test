<script setup lang="ts">
/**
 * 抽屉的报头：印章 → 细线 → 微标注，下面是衬线大标题。
 * 和页面页头（PageHead）是同一套排布，只是标题小一档。
 * 默认的「标题 + 一行说明」太薄，撑不起一张详情页。
 */
defineProps<{
  title: string
  /** 左端印章：分类、状态这类一句话标签 */
  stamp?: string
  /** 右端微标注：记录时间、任务编号之类 */
  meta?: string
}>()
</script>

<template>
  <p class="kicker">
    <span v-if="stamp" class="eyebrow">{{ stamp }}</span>
    <span class="kicker-rule" aria-hidden="true" />
    <span v-if="meta" class="kicker-meta">{{ meta }}</span>
  </p>
  <h2 class="sheet-title">{{ title }}</h2>
</template>

<style scoped>
.kicker {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.kicker-rule {
  flex: 1;
  min-width: 8px;
  height: 1px;
  background: linear-gradient(90deg, var(--rule), rgba(220, 214, 201, 0));
}

.kicker-meta {
  color: var(--ink-3);
  font-size: 10.5px;
  letter-spacing: 0.06em;
  white-space: nowrap;
}

.sheet-title {
  margin: 0;
  color: var(--ink);
  font-family: var(--font-display);
  font-size: 27px;
  font-weight: 400;
  line-height: 1.18;
  letter-spacing: -0.005em;
  text-wrap: balance;
  overflow-wrap: anywhere;
}
</style>
