# 唯一事件监听器：接管 PersonMessageReceived / GroupMessageReceived。
# D1 决策：prevent_default + prevent_postorder 掐断 LangBot 内置管线，插件自己编排。
# 主流程顺序即优先级，勿调整（原因见各步注释）。
from __future__ import annotations

import asyncio
import logging
import time
import traceback

from langbot_plugin.api.definition.components.common.event_listener import EventListener
from langbot_plugin.api.entities import context, events

from core import conversation_store, file_store, reply_gate, reply_rules
from core.dispatch import parse_answer
from core.event import build_inputs, event_id_of
from core.reply import reply_text
from core.rules import task_key
from core.text import extract_text, has_file, human_delay_seconds, is_at_bot, peer_names

log = logging.getLogger(__name__)

# Dify 的 Start 只认声明过的变量：插件先上、Chatflow 还没重导入时，多发的三个输入
# 会让 /chat-messages 直接 400，群里所有消息静默没人管。
# 撞上一次就退回旧口径并在本进程内记住，别每条消息都先撞一次。
_legacy_chatflow = False
_legacy_since = 0.0

# 但「记住」必须会过期：DSL 重导入之后，插件若还一直丢那三个输入，
# 自动回复的开关就永远不生效，用户只能重启 LangBot 自救。
LEGACY_PROBE_SECONDS = 600


def _drop_new_inputs(inputs: dict) -> dict:
    return {k: v for k, v in inputs.items() if k not in reply_rules.NEW_INPUTS}


def _is_bad_request(error: Exception) -> bool:
    """400 = 参数不被接受。Dify 对未声明输入、坏 query 都回 400，措辞不稳定，只判状态码。"""
    return "HTTP 400" in str(error)


def _file_task():
    """延迟导入 file_task 的唯一入口。

    file_task 会拉起 openpyxl/python-docx/pypdf：模块级导入一旦缺依赖，
    整个插件都起不来（一条消息都收不到），所以必须延迟导入。
    坑：不能在分支里写 `from core import file_task` 后又在别处引用——
    Python 会把 file_task 当成本函数的局部名，后面的 NEED_INPUT 分支
    在未赋值时就会炸：UnboundLocalError: cannot access local variable
    'file_task'（实机 23:31 就是这样被 LangBot 吞掉的）。
    """
    from core import file_task

    return file_task


async def _respond(deps, ctx, is_group, sender_id, chat_id, text,
                   event_type: str, file_ctx: dict | None = None):
    """统一出口：画像 → Chatflow → 机械分发。任何异常只记日志，不回复。"""
    # 本函数下面的降级分支要写这个模块级开关；global 必须在读它之前声明
    global _legacy_chatflow, _legacy_since

    # 到期的「旧 Chatflow」标记先撤掉：接下来这一轮用完整输入再探一次，
    # DSL 若已重新导入，自动回复就自己活过来了
    if _legacy_chatflow and time.monotonic() - _legacy_since > LEGACY_PROBE_SECONDS:
        _legacy_chatflow = False
        file_store.debug("legacy chatflow mark expired, probing with full inputs")

    settings = deps.settings
    chat_type = "group" if is_group else "private"
    query = text.removeprefix(settings.group_prefix) if is_group else text
    if not (query or "").strip():
        # /chat-messages 的 query 不能为空；纯文件事件用单空格占位，
        # Chatflow 的 CHECK_REQUEST 会 strip 掉，不会误当成填写要求
        query = " "
    key = conversation_store.conversation_key(
        settings.bot_uuid, chat_type, chat_id, sender_id)

    try:
        # 画像与未终结备忘都是「有则更好」：任一失败都不能中断整轮，
        # 否则 Dify 的 fail 分支根本没机会跑，用户连一句「没办成」都收不到。
        profile = {}
        try:
            profile = await deps.spring.profile(sender_id) or {}
        except Exception as e:
            file_store.debug(f"profile failed, continue without it: {e!r}")
        open_memos = []
        try:
            open_memos = await deps.spring.list_open_memos(sender_id) or []
        except Exception as e:
            file_store.debug(f"open memos failed, continue with []: {e!r}")
        # 自动回复的开关 / 限流 / 人设 / 最近消息，一次取齐。
        # 私聊没有群号，拿对方 QQ 当会话键——和 chat_setting 的存法保持一致。
        reply_ctx = reply_rules.normalize(None)
        try:
            reply_ctx = reply_rules.normalize(await deps.spring.reply_context(
                chat_id if chat_id is not None else sender_id,
                "GROUP" if is_group else "PRIVATE"))
        except Exception as e:
            # 取不到就当没开：宁可闭嘴，也不能拿不到限流快照还照说
            file_store.debug(f"reply context failed, auto reply stays off: {e!r}")

        pending = await file_store.load_pending(deps, key) or {}
        # 幂等键在此生成一次：下面「会话丢失重试」必须复用同一个值，
        # 否则后端会把同一条改期/取消执行两遍
        event_id = event_id_of(getattr(ctx, "event", None))
        inputs = build_inputs(
            profile, event_type, chat_type, chat_id, sender_id,
            file_task=file_ctx,
            pending_request_hint=pending.get("request", ""),
            last_task_ref=pending.get("taskRef", ""),
            open_memos=open_memos,
            event_id=event_id,
            reply_prompt=reply_ctx["replyPrompt"],
            recent_messages=reply_ctx["recentMessages"],
            reply_rules=reply_rules.RULES)
        file_store.debug(
            f"profile ok facts={len(profile.get('facts') or {})} "
            f"open_memos={len(open_memos)} event_id={event_id[:12]} "
            f"event={event_type} file={inputs['file_task_ref'] or '-'}")

        # 带上 conversation_id，Dify 才能看到这段对话的前文；
        # 否则“先说要求、再发文件”这类跨轮语义永远不会成立。
        conversation_id = await conversation_store.resolve(
            deps, settings.bot_uuid, chat_type, chat_id, sender_id)
        user = conversation_store.dify_user(chat_type, chat_id, sender_id)
        if _legacy_chatflow:
            inputs = _drop_new_inputs(inputs)
        try:
            result = await deps.dify.chat(
                query, user=user, conversation_id=conversation_id, inputs=inputs)
        except Exception as e:
            # Dify 应用重建/会话过期后，旧 ID 会返回 400 Conversation Not Exists；
            # 清掉映射后用新会话重试一次，避免用户从此永久收不到回复。
            if conversation_id and conversation_store.is_missing_conversation(e):
                file_store.debug(
                    f"conversation {conversation_id} missing, retry with new session")
                await conversation_store.reset(
                    deps, settings.bot_uuid, chat_type, chat_id, sender_id)
                result = await deps.dify.chat(query, user=user, inputs=inputs)
            elif not _legacy_chatflow and _is_bad_request(e):
                # 旧 Chatflow：Start 没声明自动回复那三个变量 → 400。
                # 撤掉它们再试一次；成功就记住，这一版插件对着旧 Chatflow 也能跑。
                _legacy_chatflow = True
                _legacy_since = time.monotonic()
                log.warning("Dify 400 with the auto-reply inputs, retry without them "
                            "(Chatflow 尚未按最新 DSL 重新导入)")
                file_store.debug(f"dify 400, retry without new inputs: {e!r}")
                result = await deps.dify.chat(
                    query, user=user, conversation_id=conversation_id,
                    inputs=_drop_new_inputs(inputs))
            else:
                raise

        new_conversation_id = result.get("conversation_id")
        if new_conversation_id and new_conversation_id != conversation_id:
            await conversation_store.bind(
                deps, settings.bot_uuid, chat_type, chat_id, sender_id,
                new_conversation_id)

        # 文件任务镜像补上会话/待填要求：Vue 才能按会话筛任务、看到“要填什么”
        if file_ctx and file_ctx.get("taskRef"):
            file_ctx["conversationId"] = new_conversation_id or conversation_id or ""
            file_ctx["pendingRequest"] = (
                pending.get("request", "") or file_ctx.get("pendingRequest") or "")
            try:
                await _file_task().sync(deps, file_ctx)
            except Exception as e:
                # 镜像失败不能挡住回复：真身始终在插件本地
                file_store.debug(f"sync file ctx failed: {e!r}")

        data = parse_answer(result.get("answer", ""))
        file_store.debug(
            f"dify ok conversation={new_conversation_id or conversation_id} "
            f"answer={result.get('answer', '')[:40]!r} action={data.get('action')}")

        # 出口门控：说得成不成看开关与限流；要不要改成人来确认是一条 CHAT 的语义判断
        gate = reply_gate.decide(data, reply_ctx)
        if gate.suppressed:
            file_store.debug(f"reply suppressed reason={gate.suppressed} "
                             f"kind={gate.send_kind} chat={chat_id}")
        if gate.draft:
            try:
                # 标题留空、正文写要发的话、sourceContent 写对方说了什么。
                # 以前拿正文前 20 字当标题，草稿卡会把同一句显示两遍，还看不出在回谁。
                await deps.spring.create_draft(
                    "REPLY", sender_id, chat_id, None,
                    {"content": gate.draft["content"],
                     "sourceContent": (query or "").strip()[:200]})
                file_store.debug(f"reply draft created for chat={chat_id}")
            except Exception as e:
                # 草稿没建成 = 这条回复彻底没了。宁可让用户等一句「没办成」，也别假装发过
                log.warning("create reply draft failed: %s", e)
                file_store.debug(f"create reply draft failed chat={chat_id}: {e!r}")
                apology = "这句我拿不准，先不替你说了。"
                await reply_text(ctx, apology)
                # 道歉语也走统一日志：不进 message_log，网页上就只剩「什么都没发生」
                deps.log_queue.enqueue({
                    "senderId": sender_id, "chatId": chat_id, "direction": "OUT",
                    "msgType": "text", "content": apology, "replyKind": "TASK",
                    "conversationId": new_conversation_id or conversation_id or "",
                })

        sent = await deps.dispatcher.handle(
            gate.data, ctx, sender_id, chat_id, file_ctx=file_ctx,
            conversation_key=key, deps=deps)
        file_store.debug(f"dispatch sent={bool(sent)} text={(sent or '')[:40]!r}")
        if sent:
            deps.log_queue.enqueue({
                "senderId": sender_id, "chatId": chat_id, "direction": "OUT",
                "msgType": "text", "content": sent, "replyKind": gate.send_kind,
                "conversationId": new_conversation_id or conversation_id or "",
            })
    except Exception as e:
        log.exception("handle message failed: %s", e)
        file_store.debug(f"EXC handle: {e!r}\n{traceback.format_exc(limit=4)}")


class DefaultEventListener(EventListener):

    def __init__(self):
        super().__init__()
        # 注意：handler 装饰器是实例方法，只能在 __init__ 里注册，不能写在类体
        self.handler(events.PersonMessageReceived)(self._on_person)
        self.handler(events.GroupMessageReceived)(self._on_group)

    async def _on_person(self, ctx: context.EventContext):
        await self._handle(ctx, is_group=False)

    async def _on_group(self, ctx: context.EventContext):
        await self._handle(ctx, is_group=True)

    async def _handle(self, ctx: context.EventContext, is_group: bool):
        deps = self.plugin          # 单例容器，见 main.py
        settings = deps.settings

        event = ctx.event
        # 只接管 QQ 平台（sender/launcher 为数字 ID）；
        # WebChat 等非 QQ 来源（如 websocket_xxx）不 prevent_default，放行给内置管线
        try:
            sender_id = int(event.sender_id)
            chat_id = int(event.launcher_id) if is_group else None
        except (TypeError, ValueError):
            log.info("non-QQ sender %r, leave to default pipeline", event.sender_id)
            return

        # 0. 掐断内置管线（两连击，缺一不可）
        ctx.prevent_default()
        ctx.prevent_postorder()

        chain = event.message_chain
        text = extract_text(chain)

        # 1. IN 日志：同步入队，永不阻塞、永不抛错（排查依据，最先落）
        #    昵称/群名只给人看：整理结果要写「谁在哪个群说了什么」，光有 id 读不懂
        sender_name, chat_name = peer_names(event)
        deps.log_queue.enqueue({
            "senderId": sender_id, "chatId": chat_id, "direction": "IN",
            "senderName": sender_name, "chatName": chat_name,
            "msgType": "file" if has_file(chain) else "text", "content": text,
        })
        # 插件 worker 的 stdout 不会进 LangBot 日志文件，落盘面包屑才是唯一稳定痕迹
        file_store.debug(f"IN sender={sender_id} chat={chat_id} group={is_group} text={text[:50]!r}")

        # 2. 文件消息 → 下载 + 抽结构，然后交给 Chatflow 决策。
        #    必须 await：LangBot 的 API 绑定在 invocation 生命周期上，
        #    create_task 放后台后 handler 一返回，ctx.* 全部报 "Plugin invocation has ended"。
        if has_file(chain):
            log.warning("file message from %s, chain=%s", sender_id,
                        [type(c).__name__ for c in chain])
            task = await _file_task().ingest(ctx, event, is_group, deps)
            if task:
                await _respond(deps, ctx, is_group, sender_id, chat_id,
                               text, event_type="file", file_ctx=task)
            return

        if not text:
            # 诊断：既非 File 组件也无文本 → 把组件形态留下，防静默丢弃
            log.warning("empty text, chain=%s", [type(c).__name__ for c in chain])
            return

        # 3. 触发判定：群聊只响应 @机器人 或配置前缀
        if is_group:
            triggered = is_at_bot(chain, settings.bot_qq) \
                or text.startswith(settings.group_prefix)
        else:
            triggered = True

        # 4. 前置拦截。注意在 triggered 退出【之前】执行——
        #    群里发收集通知通常不会 @机器人，不 @ 也要能拦
        rule = None
        if settings.intercept_scope == "all" \
                or (settings.intercept_scope == "group" and is_group):
            rule = deps.rule_cache.match(text)
        if rule:
            await self._intercept(ctx, deps, rule, sender_id, chat_id)
            return

        if not triggered:
            return

        # 5. 文本消息：若有待续文件任务，把它的 structure/task_ref 一起送过去，
        #    Dify 才能判断“这句是补充字段还是普通聊天”。插件不做语义判断。
        active_ref = await file_store.load_active_input(deps, sender_id)
        file_ctx = await file_store.load_task(deps, active_ref) if active_ref else None
        # 防串会话：任务不属于当前会话就不带（私聊 chatId=None，群聊为群号）
        if file_ctx and file_ctx.get("chatId") != chat_id:
            file_ctx = None
        await _respond(deps, ctx, is_group, sender_id, chat_id, text,
                       event_type="text", file_ctx=file_ctx)

    async def _intercept(self, ctx, deps, rule, sender_id, chat_id):
        """命中拦截规则：防重检查 → 拟人延迟回复 → mark → OUT 日志。

        发送动作绝不重试；check/mark 是幂等接口，失败只记日志。
        """
        template = (rule.get("replyTemplate") or "").strip()
        if not template:
            log.warning("rule %s has empty replyTemplate, skip", rule.get("name"))
            return
        try:
            # 私聊场景 chat_id 为 None，防重键退化为 sender 维度
            scope_id = chat_id if chat_id is not None else sender_id
            key = task_key(scope_id, rule["name"])
            if await deps.spring.check_replied(scope_id, rule["name"], key):
                return
            await asyncio.sleep(human_delay_seconds(template, deps.settings))
            await reply_text(ctx, template)
            await deps.spring.mark_replied(scope_id, rule["name"], key)
            deps.log_queue.enqueue({
                "senderId": sender_id, "chatId": chat_id, "direction": "OUT",
                "msgType": "text", "content": template,
                # 拦截回复是「干活」，不该占用自动回复的每小时额度
                "replyKind": "TASK",
            })
        except Exception as e:
            log.warning("intercept reply failed (rule=%s): %s", rule.get("name"), e)
