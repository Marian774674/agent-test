<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { Pencil, Plus, Trash2 } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHead from '@/components/PageHead.vue'
import Segmented from '@/components/Segmented.vue'
import Sheet from '@/components/Sheet.vue'
import Skeleton from '@/components/Skeleton.vue'
import {
  createFact,
  deleteFact,
  getStyleProfile,
  listFacts,
  updateFact,
  updateStyleProfile,
} from '@/api/memory'
import { getSettings, putSettings } from '@/api/settings'
import { createContact, deleteContact, listContacts, type Contact } from '@/api/ask'
import type { ProfileFact, StyleProfile } from '@/api/types'
import { useOwner } from '@/composables/useOwner'
import { useRefresh } from '@/composables/useRefresh'
import { useToast } from '@/composables/useToast'
import { formatDateTime } from '@/utils/datetime'

type Frequency = 'DAILY_7D' | 'DAILY_TODAY' | 'WEEKLY'

const owner = useOwner()
const toast = useToast()

const facts = ref<ProfileFact[]>([])
const loadingFacts = ref(true)
const factsError = ref('')

const profile = ref<StyleProfile | null>(null)
const profileText = ref('')
const profileError = ref('')
const savingProfile = ref(false)
const profileEl = ref<HTMLTextAreaElement | null>(null)

/** 画像通常是一段话，固定 7 行会留一大片空；跟着内容长高，最多到 420px 再滚 */
function autoGrow() {
  const el = profileEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 420)}px`
}

watch(profileText, () => void nextTick(autoGrow), { flush: 'post' })

const frequency = ref<Frequency>('DAILY_7D')
const savingFrequency = ref(false)

const sheetOpen = ref(false)
const editing = ref<ProfileFact | null>(null)
const form = reactive({ factKey: '', factValue: '' })
const saving = ref(false)
const formError = ref('')
const confirmingDelete = ref(false)

const FREQUENCIES = [
  { key: 'DAILY_7D' as const, label: '每日·近7天' },
  { key: 'DAILY_TODAY' as const, label: '每日·当天' },
  { key: 'WEEKLY' as const, label: '每周' },
]

const senderId = computed(() => owner.senderId)

/* ── 认识的人 ─────────────────────────────────────────────── */

const contacts = ref<Contact[]>([])
const loadingContacts = ref(true)
const contactsError = ref('')
const contactSheetOpen = ref(false)
const contactForm = reactive({ senderId: '', alias: '', relation: '' })
const contactFormError = ref('')
const savingContact = ref(false)
const confirmingContactId = ref<number | null>(null)

// 通讯录是全局的（谁的称呼都记在一起），不按当前主人过滤
async function loadContacts(silent = false) {
  if (!silent) {
    loadingContacts.value = true
    contactsError.value = ''
  }
  try {
    contacts.value = (await listContacts()) ?? []
  } catch (e) {
    const message = e instanceof Error ? e.message : '人物读取失败'
    if (silent) toast.error(message)
    else contactsError.value = message
  } finally {
    loadingContacts.value = false
  }
}

function openContactSheet() {
  contactForm.senderId = ''
  contactForm.alias = ''
  contactForm.relation = ''
  contactFormError.value = ''
  contactSheetOpen.value = true
}

async function saveContact() {
  const raw = contactForm.senderId.trim()
  const alias = contactForm.alias.trim()
  if (!/^\d{5,12}$/.test(raw)) {
    contactFormError.value = 'QQ 号是 5–12 位数字'
    return
  }
  if (!alias) {
    contactFormError.value = '称呼不能为空'
    return
  }
  savingContact.value = true
  contactFormError.value = ''
  try {
    const created = await createContact({
      senderId: Number(raw),
      alias,
      relation: contactForm.relation.trim() || undefined,
    })
    contacts.value = [created, ...contacts.value.filter((row) => row.id !== created.id)]
    contactSheetOpen.value = false
    toast.success('记住了')
  } catch (e) {
    contactFormError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    savingContact.value = false
  }
}

async function removeContact(contact: Contact) {
  try {
    await deleteContact(contact.id)
    contacts.value = contacts.value.filter((row) => row.id !== contact.id)
    toast.push(`已删除「${contact.alias}」`)
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '删除失败')
  } finally {
    confirmingContactId.value = null
  }
}

async function loadFacts(silent = false) {
  if (!senderId.value) {
    loadingFacts.value = false
    return
  }
  if (!silent) {
    loadingFacts.value = true
    factsError.value = ''
  }
  try {
    facts.value = (await listFacts(senderId.value)) ?? []
  } catch (e) {
    const message = e instanceof Error ? e.message : '事实读取失败'
    if (silent) toast.error(message)
    else factsError.value = message
  } finally {
    loadingFacts.value = false
  }
}

async function loadProfile() {
  if (!senderId.value) return
  profileError.value = ''
  try {
    profile.value = await getStyleProfile(senderId.value)
    profileText.value = profile.value?.profilePrompt ?? ''
    await nextTick()
    autoGrow()
  } catch {
    // 没生成过画像时后端会报错，这里不是异常，只是还没有内容
    profile.value = null
    profileText.value = ''
  }
}

async function loadFrequency() {
  try {
    const kv = await getSettings()
    const raw = kv['summary_frequency']
    if (raw === 'DAILY_7D' || raw === 'DAILY_TODAY' || raw === 'WEEKLY') frequency.value = raw
  } catch {
    // 读不到就保持默认，不打扰
  }
}

/** silent = 下拉刷新：保留当前内容，不闪骨架屏 */
async function loadAll(silent = false) {
  void loadContacts(silent)
  if (!senderId.value) {
    loadingFacts.value = false
    return
  }
  await Promise.all([loadFacts(silent), loadProfile(), loadFrequency()])
}

onMounted(() => void loadAll())
watch(senderId, () => void loadAll())

useRefresh(() => loadAll(true))

function openAdd() {
  editing.value = null
  form.factKey = ''
  form.factValue = ''
  formError.value = ''
  confirmingDelete.value = false
  sheetOpen.value = true
}

function openEdit(fact: ProfileFact) {
  editing.value = fact
  form.factKey = fact.factKey
  form.factValue = fact.factValue
  formError.value = ''
  confirmingDelete.value = false
  sheetOpen.value = true
}

async function save() {
  if (!senderId.value) return
  if (!form.factKey.trim() || !form.factValue.trim()) {
    formError.value = '键和值都要填'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    if (editing.value) {
      const updated = await updateFact(editing.value.id, {
        factKey: form.factKey.trim(),
        factValue: form.factValue.trim(),
      })
      facts.value = facts.value.map((item) => (item.id === updated.id ? updated : item))
      toast.success('已更新')
    } else {
      const created = await createFact({
        senderId: senderId.value,
        factKey: form.factKey.trim(),
        factValue: form.factValue.trim(),
      })
      facts.value = [...facts.value, created]
      toast.success('已记下')
    }
    sheetOpen.value = false
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}

async function removeFact() {
  const fact = editing.value
  if (!fact) return
  saving.value = true
  try {
    await deleteFact(fact.id)
    facts.value = facts.value.filter((item) => item.id !== fact.id)
    sheetOpen.value = false
    toast.push('已删除这条事实')
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '删除失败'
  } finally {
    saving.value = false
  }
}

async function saveProfile() {
  if (!senderId.value) return
  savingProfile.value = true
  profileError.value = ''
  try {
    profile.value = await updateStyleProfile({
      senderId: senderId.value,
      profilePrompt: profileText.value,
    })
    toast.success('风格画像已保存')
  } catch (e) {
    profileError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    savingProfile.value = false
  }
}

async function changeFrequency(value: Frequency) {
  const previous = frequency.value
  frequency.value = value
  savingFrequency.value = true
  try {
    await putSettings({ summary_frequency: value })
    toast.success('总结频率已更新')
  } catch (e) {
    frequency.value = previous
    toast.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    savingFrequency.value = false
  }
}
</script>

<template>
  <div class="page">
    <PageHead
      :back="{ to: '/more', label: '更多' }"
      eyebrow="记忆总控台"
      title="Memory"
      sub="事实是即时生效的硬信息；风格画像只在手动保存或定时总结时更新。"
    />

    <p v-if="!owner.loaded" class="faint">正在读取设置…</p>

    <EmptyState
      v-else-if="!senderId"
      title="还不知道你是谁"
      hint="先填上你的 QQ 号，才能查看和整理属于你的记忆。"
    >
      <RouterLink to="/more" class="btn btn-sm btn-quiet">去设置里填 QQ 号</RouterLink>
    </EmptyState>

    <template v-else>
      <section class="block" aria-labelledby="facts-title">
        <div class="block-head">
          <h2 id="facts-title" class="section-title">事实</h2>
          <button type="button" class="add-btn" @click="openAdd">
            <Plus :size="15" :stroke-width="2.2" aria-hidden="true" />
            添加
          </button>
        </div>

        <ErrorState v-if="factsError" :message="factsError" @retry="loadFacts" />
        <Skeleton v-else-if="loadingFacts" :rows="3" />

        <ul v-else-if="facts.length" class="facts">
          <li v-for="fact in facts" :key="fact.id">
            <button type="button" class="fact" @click="openEdit(fact)">
              <span class="fact-key">{{ fact.factKey }}</span>
              <span class="fact-value">{{ fact.factValue }}</span>
              <span class="fact-meta num">{{ formatDateTime(fact.updatedAt) }}</span>
              <Pencil :size="14" class="fact-icon" aria-hidden="true" />
            </button>
          </li>
        </ul>

        <EmptyState
          v-else
          title="还没有记录任何事实"
          hint="在 QQ 里说「记一下我的学号是 2023001」，或者点上面的添加。"
        />
      </section>

      <section class="block" aria-labelledby="contacts-title">
        <div class="block-head">
          <h2 id="contacts-title" class="section-title">认识的人</h2>
          <button type="button" class="add-btn" @click="openContactSheet">
            <Plus :size="15" :stroke-width="2.2" aria-hidden="true" />
            认一个
          </button>
        </div>
        <p class="block-hint">夜间归纳会自己认人；手动认过的称呼不会再被它覆盖。</p>

        <ErrorState v-if="contactsError" :message="contactsError" @retry="loadContacts" />
        <Skeleton v-else-if="loadingContacts" :rows="2" />

        <ul v-else-if="contacts.length" class="contacts">
          <li v-for="contact in contacts" :key="contact.id" class="contact">
            <div class="contact-main">
              <span class="contact-alias">
                {{ contact.alias }}
                <span v-if="contact.relation" class="contact-relation">{{ contact.relation }}</span>
                <span class="contact-source" :class="contact.source === 'USER' ? 'manual' : 'auto'">
                  {{ contact.source === 'USER' ? '手动' : '自动' }}
                </span>
              </span>
              <span class="contact-meta num">QQ {{ contact.senderId }}</span>
            </div>
            <div v-if="confirmingContactId === contact.id" class="contact-confirm">
              <button type="button" class="btn btn-sm btn-ghost" @click="confirmingContactId = null">
                算了
              </button>
              <button type="button" class="btn btn-sm btn-danger" @click="removeContact(contact)">
                删除
              </button>
            </div>
            <button
              v-else
              type="button"
              class="contact-del"
              :aria-label="`删除称呼 ${contact.alias}`"
              @click="confirmingContactId = contact.id"
            >
              <Trash2 :size="14" aria-hidden="true" />
            </button>
          </li>
        </ul>

        <EmptyState
          v-else
          title="还不认识任何人"
          hint="等夜间归纳自己认，或者点右上角「认一个」。"
        />
      </section>

      <section class="block" aria-labelledby="profile-title">
        <div class="block-head">
          <h2 id="profile-title" class="section-title">风格画像</h2>
          <span class="block-note">
            {{
              profile?.lastSummarizedAt
                ? `上次总结 ${formatDateTime(profile.lastSummarizedAt)}`
                : '还没生成过'
            }}
          </span>
        </div>

        <div class="profile-card">
          <textarea
            id="profile-text"
            ref="profileEl"
            v-model="profileText"
            class="profile-text"
            rows="4"
            placeholder="例如：说话简短，常用「行」「好嘞」，少用感叹号……"
            aria-label="画像提示词"
          />
          <div class="profile-foot">
            <span class="profile-hint">这段提示词决定机器人模仿你说话的语气，改完点保存才生效。</span>
            <button type="button" class="btn btn-sm btn-primary" :disabled="savingProfile" @click="saveProfile">
              {{ savingProfile ? '保存中…' : '保存' }}
            </button>
          </div>
        </div>
        <p v-if="profileError" class="inline-error" role="alert">{{ profileError }}</p>
      </section>

      <section class="block" aria-labelledby="freq-title">
        <div class="block-head">
          <h2 id="freq-title" class="section-title">总结频率</h2>
        </div>
        <p class="block-hint">定时任务按这个频率读消息日志、重写风格画像。聊天过程中它不会被自动改写。</p>
        <Segmented
          :model-value="frequency"
          :items="FREQUENCIES"
          label="总结频率"
          @update:model-value="changeFrequency"
        />
        <p v-if="savingFrequency" class="faint">正在保存…</p>
      </section>
    </template>

    <Sheet
      :open="sheetOpen"
        :title="editing ? '修改事实' : '添加事实'"
      description="比如学号、导员电话、常用地址。机器人下次对话就会用上。"
      @close="sheetOpen = false"
    >
      <div class="form">
        <div class="frow">
          <label class="frow-label" for="fact-key">键</label>
          <input
            id="fact-key"
            v-model="form.factKey"
            class="frow-input"
            type="text"
            placeholder="学号"
            maxlength="64"
          />
        </div>
        <div class="frow">
          <label class="frow-label" for="fact-value">值</label>
          <input
            id="fact-value"
            v-model="form.factValue"
            class="frow-input"
            type="text"
            placeholder="2023001"
            maxlength="255"
          />
        </div>
        <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>

        <div v-if="editing" class="delete-zone">
          <button
            v-if="!confirmingDelete"
            type="button"
            class="danger-link"
            @click="confirmingDelete = true"
          >
            <Trash2 :size="13" aria-hidden="true" />
            删除这条事实
          </button>
          <div v-else class="confirm">
            <p>删除后，机器人下次对话就不会再用它。确定吗？</p>
            <div class="confirm-actions">
              <button type="button" class="btn btn-sm btn-ghost" @click="confirmingDelete = false">
                算了
              </button>
              <button type="button" class="btn btn-sm btn-danger" :disabled="saving" @click="removeFact">
                确定删除
              </button>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <button type="button" class="btn btn-ghost" @click="sheetOpen = false">取消</button>
        <button type="button" class="btn btn-primary" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </template>
    </Sheet>

    <Sheet
      :open="contactSheetOpen"
        title="认一个人"
      description="记下称呼和 QQ 号，以后问起这个人就不用再猜。"
      @close="contactSheetOpen = false"
    >
      <div class="form">
        <div class="frow">
          <label class="frow-label" for="contact-alias">称呼</label>
          <input
            id="contact-alias"
            v-model="contactForm.alias"
            class="frow-input"
            type="text"
            placeholder="阿黄"
            maxlength="64"
          />
        </div>
        <div class="frow">
          <label class="frow-label" for="contact-qq">QQ 号</label>
          <input
            id="contact-qq"
            v-model="contactForm.senderId"
            class="frow-input"
            type="text"
            inputmode="numeric"
            placeholder="1753800374"
            maxlength="12"
          />
        </div>
        <div class="frow">
          <label class="frow-label" for="contact-relation">关系</label>
          <input
            id="contact-relation"
            v-model="contactForm.relation"
            class="frow-input"
            type="text"
            placeholder="室友（可空）"
            maxlength="64"
          />
        </div>
        <p v-if="contactFormError" class="form-error" role="alert">{{ contactFormError }}</p>
      </div>

      <template #footer>
        <button type="button" class="btn btn-ghost" @click="contactSheetOpen = false">取消</button>
        <button type="button" class="btn btn-primary" :disabled="savingContact" @click="saveContact">
          {{ savingContact ? '保存中…' : '保存' }}
        </button>
      </template>
    </Sheet>
  </div>
</template>

<style scoped>

.block + .block {
  margin-top: 30px;
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.block-note {
  color: var(--ink-3);
  font-size: 11.5px;
}

.block-hint {
  margin-bottom: 12px;
  color: var(--ink-3);
  font-size: 12.5px;
  line-height: 1.6;
}

.add-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 32px;
  padding: 0 12px;
  border: 0;
  border-radius: var(--r-pill);
  background: var(--card);
  color: var(--ink);
  font-size: 12.5px;
  font-weight: 500;
  box-shadow: var(--shadow-card);
  cursor: pointer;
}

/* ── 事实列表 ─────────────────────────────────────────────── */

.facts {
  margin: 0;
  padding: 0;
  list-style: none;
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}

.facts > li + li {
  border-top: 1px solid var(--hairline);
}

.fact {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 2px 10px;
  width: 100%;
  padding: 13px 15px;
  border: 0;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.fact:active {
  background: var(--surface);
}

.fact-key {
  grid-column: 1;
  color: var(--ink-3);
  font-size: 11.5px;
}

.fact-value {
  grid-column: 1;
  font-size: 15px;
  overflow-wrap: anywhere;
}

.fact-meta {
  grid-column: 1;
  color: var(--ink-3);
  font-size: 11px;
}

.fact-icon {
  grid-column: 2;
  grid-row: 1 / span 3;
  align-self: center;
  color: var(--ink-3);
}

/* ── 认识的人 ─────────────────────────────────────────────── */

.contacts {
  margin: 0;
  padding: 0;
  list-style: none;
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}

.contacts > li + li {
  border-top: 1px solid var(--hairline);
}

.contact {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 14px;
}

.contact-main {
  flex: 1;
  display: grid;
  gap: 2px;
  min-width: 0;
}

.contact-alias {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 15px;
}

.contact-relation {
  color: var(--ink-3);
  font-size: 11.5px;
}

.contact-source {
  padding: 1px 7px;
  border-radius: var(--r-pill);
  font-size: 10.5px;
}

.contact-source.manual {
  background: var(--card-inverse);
  color: var(--on-dark);
}

.contact-source.auto {
  background: var(--surface);
  color: var(--ink-3);
}

.contact-meta {
  color: var(--ink-3);
  font-size: 11.5px;
}

.contact-del {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: var(--r-pill);
  background: transparent;
  color: var(--ink-3);
  cursor: pointer;
  flex: none;
}

.contact-del:active {
  background: var(--surface);
}

.contact-confirm {
  display: flex;
  gap: 6px;
  flex: none;
}

/* ── 风格画像 ─────────────────────────────────────────────── */

.profile-card {
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}

.profile-text {
  width: 100%;
  min-height: 92px;
  max-height: 420px;
  padding: 15px;
  border: 0;
  background: transparent;
  font-size: 14px;
  line-height: 1.65;
  resize: none;
  overflow-y: auto;
  outline: none;
}

.profile-text::placeholder {
  color: var(--ink-3);
}

.profile-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px 12px 15px;
  border-top: 1px solid var(--hairline);
}

.profile-hint {
  flex: 1;
  color: var(--ink-3);
  font-size: 11px;
  line-height: 1.5;
}

/* ── 抽屉表单 ─────────────────────────────────────────────── */

/* 表单骨架在 style.css 里，和备忘、规则共用一套 */

.inline-error {
  margin-top: 10px;
  padding: 10px 14px;
  border-radius: var(--r-md);
  background: var(--danger-wash);
  color: var(--danger);
  font-size: 12.5px;
}

.delete-zone {
  margin-top: 6px;
}

.danger-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 8px 0;
  border: 0;
  background: transparent;
  color: var(--danger);
  font-size: 12.5px;
  cursor: pointer;
}

.confirm {
  padding: 14px;
  border-radius: var(--r-md);
  background: var(--danger-wash);
  border: 1px solid rgba(191, 58, 43, 0.2);
  color: var(--danger);
  font-size: 12.5px;
  line-height: 1.55;
}

.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

</style>
