<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Check } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHead from '@/components/PageHead.vue'
import Skeleton from '@/components/Skeleton.vue'
import { listChatSettings, putChatSetting } from '@/api/chatSettings'
import type { ChatSetting } from '@/api/chatSettings'
import { getSettings, putSettings } from '@/api/settings'
import { useRefresh } from '@/composables/useRefresh'
import { useToast } from '@/composables/useToast'

const toast = useToast()

const chats = ref<ChatSetting[]>([])
const loading = ref(true)
const error = ref('')

const digestEnabled = ref(false)
const aliases = ref('')
const focus = ref('')
/** 进首页时的整理门槛：消息条数、间隔分钟数，任一超过就跑一次 */
const minMessages = ref('100')
const minMinutes = ref('60')
const savingPrefs = ref(false)
const prefsSaved = ref(false)

const groups = computed(() => chats.value.filter((chat) => chat.kind === 'GROUP'))
// 私聊没有群号，会话键是对方 QQ；私聊不参与整理，只有自动回复一个开关
const privates = computed(() => chats.value.filter((chat) => chat.kind === 'PRIVATE'))
const enabledCount = computed(() => groups.value.filter((chat) => chat.digestEnabled).length)

onMounted(() => {
  void load()
})

/** silent = 下拉刷新：保留当前内容，不闪骨架屏 */
async function load(silent = false) {
  if (!silent) {
    loading.value = true
    error.value = ''
  }
  try {
    const [list, settings] = await Promise.all([listChatSettings(), getSettings()])
    chats.value = list
    digestEnabled.value = settings.digest_enabled === '1'
    aliases.value = settings.owner_aliases ?? ''
    focus.value = settings.digest_focus_prompt ?? ''
    minMessages.value = settings.digest_refresh_min_messages ?? '100'
    minMinutes.value = settings.digest_refresh_min_minutes ?? '60'
  } catch (e) {
    const message = e instanceof Error ? e.message : '加载失败'
    if (silent) toast.error(message)
    else error.value = message
  } finally {
    loading.value = false
  }
}

useRefresh(() => load(true))

/** 先改界面再发请求：失败了滚回去，别让人以为已经生效 */
async function toggle(chat: ChatSetting, field: 'digestEnabled' | 'autoReplyEnabled') {
  const before = chat[field]
  chat[field] = !before
  prefsSaved.value = false
  try {
    const saved = await putChatSetting({
      chatId: chat.chatId,
      kind: chat.kind,
      chatName: chat.chatName,
      [field]: chat[field],
    })
    chat.id = saved.id ?? chat.id
  } catch (e) {
    chat[field] = before
    toast.push(e instanceof Error ? e.message : '没保存上', 'error')
  }
}

async function savePrefs() {
  savingPrefs.value = true
  try {
    await putSettings({
      digest_enabled: digestEnabled.value ? '1' : '0',
      owner_aliases: aliases.value.trim(),
      digest_focus_prompt: focus.value.trim(),
      digest_refresh_min_messages: minMessages.value.trim() || '100',
      digest_refresh_min_minutes: minMinutes.value.trim() || '60',
    })
    prefsSaved.value = true
    toast.success('整理偏好已保存')
  } catch (e) {
    toast.push(e instanceof Error ? e.message : '保存失败', 'error')
  } finally {
    savingPrefs.value = false
  }
}

function chatLabel(chat: ChatSetting) {
  return chat.chatName || (chat.kind === 'PRIVATE' ? `QQ ${chat.chatId}` : `群 ${chat.chatId}`)
}
</script>

<template>
  <div class="page">
    <PageHead
      :back="{ to: '/more', label: '更多' }"
      title="Chats"
      sub="选哪些群参与整理；偏好开关决定后端要不要每小时真的去整理。"
    />

    <ErrorState v-if="error" :message="error" hint="也可以下拉重试。" @retry="load" />
    <Skeleton v-else-if="loading" :rows="3" />

    <template v-else>
      <section class="block">
        <h2 class="block-title">整理偏好</h2>

        <div class="card card-pad">
          <label class="switch-row">
            <span class="switch-text">
              <span class="switch-label">开启消息整理</span>
              <span class="switch-hint">
                关着的时候整段流程不跑，群里的开关也暂时不生效。
              </span>
            </span>
            <input
              v-model="digestEnabled"
              type="checkbox"
              class="switch"
              role="switch"
              :aria-checked="digestEnabled"
            />
          </label>

          <div class="field">
            <label for="owner-aliases">我在群里叫什么</label>
            <input
              id="owner-aliases"
              v-model="aliases"
              class="input"
              type="text"
              autocomplete="off"
              placeholder="小明,明哥"
            />
            <p class="field-hint">
              逗号分隔。这些名字出现在消息里，那条就会被标成「在叫你」，整理时不会漏掉。
            </p>
          </div>

          <div class="field">
            <label for="digest-focus">我想留意什么</label>
            <textarea
              id="digest-focus"
              v-model="focus"
              class="input textarea"
              rows="3"
              placeholder="导师和实习相关的通知优先；游戏群里的活动可以忽略"
            />
            <p class="field-hint">写进整理提示词。留空就按「有时间地点的通知 &gt; 要我做的事 &gt; 提到我」取舍。</p>
          </div>

          <div class="field-pair">
            <div class="field">
              <label for="digest-min-messages">多少条消息算一段</label>
              <input
                id="digest-min-messages"
                v-model="minMessages"
                class="input"
                type="number"
                min="1"
                inputmode="numeric"
              />
            </div>
            <div class="field">
              <label for="digest-min-minutes">隔多久算一段（分钟）</label>
              <input
                id="digest-min-minutes"
                v-model="minMinutes"
                class="input"
                type="number"
                min="1"
                inputmode="numeric"
              />
            </div>
          </div>
          <p class="field-hint">
            每次进首页都会看一眼：这段时间收到超过 {{ minMessages || '?' }} 条消息，或距上次整理超过
            {{ minMinutes || '?' }} 分钟，任一满足就整理一次；都没到就攒着，下次一起算。
          </p>

          <button
            type="button"
            class="btn btn-block"
            :class="prefsSaved ? 'btn-quiet' : 'btn-primary'"
            :disabled="savingPrefs"
            @click="savePrefs"
          >
            <Check v-if="prefsSaved" :size="15" :stroke-width="2.6" aria-hidden="true" />
            {{ savingPrefs ? '保存中…' : prefsSaved ? '已保存' : '保存偏好' }}
          </button>
        </div>
      </section>

      <section class="block">
        <h2 class="block-title">
          群列表
          <span class="num">{{ enabledCount }}/{{ groups.length }} 已开启整理</span>
        </h2>
        <p class="block-hint">
          列表来自机器人见过的群消息；还没配置过的群默认两个开关都是关的。
          自动回复还有一个总开关，在「设置 → 自动回复」——它关着时这里怎么开都不会说话。
        </p>

        <EmptyState
          v-if="!groups.length"
          title="机器人还没在任何群里说过话"
          hint="先把它拉进群，收到几条消息后这里就会出现。"
        />

        <ul v-else class="chats">
          <li v-for="chat in groups" :key="`${chat.kind}:${chat.chatId}`" class="chat">
            <p class="chat-name">{{ chatLabel(chat) }}</p>
            <div class="chat-switches">
              <label class="mini">
                <input
                  type="checkbox"
                  class="switch switch-sm"
                  role="switch"
                  :checked="chat.digestEnabled"
                  :aria-checked="chat.digestEnabled"
                  @change="toggle(chat, 'digestEnabled')"
                />
                <span>整理</span>
              </label>
              <label class="mini">
                <input
                  type="checkbox"
                  class="switch switch-sm"
                  role="switch"
                  :checked="chat.autoReplyEnabled"
                  :aria-checked="chat.autoReplyEnabled"
                  @change="toggle(chat, 'autoReplyEnabled')"
                />
                <span>自动回复</span>
              </label>
            </div>
          </li>
        </ul>
        <p v-if="groups.length" class="faint note">
          自动回复管的是<span>替你说日常话</span>；备忘、查资料、文件填写不受这个开关影响，照常工作。
        </p>
      </section>

      <section class="block">
        <h2 class="block-title">私聊</h2>
        <p class="block-hint">
          私聊只有自动回复这一个开关，不做整理。开着才替你说日常话，关闭时你说的事照记不误。
        </p>

        <EmptyState
          v-if="!privates.length"
          title="还没人私聊过机器人"
          hint="有人私聊它一句，这里就会出现。"
        />

        <ul v-else class="chats">
          <li v-for="chat in privates" :key="`PRIVATE:${chat.chatId}`" class="chat">
            <p class="chat-name">{{ chatLabel(chat) }}</p>
            <div class="chat-switches">
              <label class="mini">
                <input
                  type="checkbox"
                  class="switch switch-sm"
                  role="switch"
                  :checked="chat.autoReplyEnabled"
                  :aria-checked="chat.autoReplyEnabled"
                  @change="toggle(chat, 'autoReplyEnabled')"
                />
                <span>自动回复</span>
              </label>
            </div>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>

.block + .block {
  margin-top: 28px;
}

/* 分节标题由 style.css 的「栏目线」统一实现，这里只管块间距 */
.block-title {
  margin-bottom: 8px;
}

/*
 * 栏目线右端默认放「衬线数字」。这一处放的是「1/1 已开启整理」这种短语，
 * 衬线 14px 会把它变成一个奇怪的标题，所以退回微标签的字号与字距。
 */
.block-title .num {
  font-family: var(--font-ui);
  font-size: 10.5px;
  font-weight: 500;
  letter-spacing: 0.1em;
  color: var(--ink-3);
}

.block-hint {
  margin-bottom: 10px;
  color: var(--ink-3);
  font-size: 12.5px;
  line-height: 1.6;
}

.field {
  margin-top: 16px;
}

.field-pair {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.field label {
  display: block;
  margin-bottom: 6px;
  color: var(--ink-2);
  font-size: 12.5px;
  font-weight: 500;
}

.textarea {
  min-height: 84px;
  resize: vertical;
}

.field-hint {
  margin: 6px 0 0;
  color: var(--ink-3);
  font-size: 12px;
  line-height: 1.55;
}

/* 开关本体样式在全局 style.css（设置页也用同一套） */

.chats {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.chat {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--card);
  box-shadow: var(--shadow-card);
}

.chat-name {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-switches {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-left: auto;
}

.mini {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--ink-3);
  font-size: 11.5px;
  cursor: pointer;
}

.note {
  margin: 12px 0 0;
  line-height: 1.55;
}
</style>
