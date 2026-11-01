"""事件监听器回归：所有消息都进 Chatflow，插件只负责传上下文和执行。

钉两件事：
1. 文本补充必须把待续文件的 structure/task_ref 一起送过去，否则 Dify 无法判断
   这句是“补充字段”还是普通聊天。
2. 文件消息必须先下载/抽取，再把 event_type=file + structure 送 Chatflow。
"""
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest
from langbot_plugin.api.entities.builtin.platform import message as pm

from components.event_listener.default import DefaultEventListener
from core import conversation_store, file_store


class FakeCtx:
    def __init__(self, chain, sender_id="1001", launcher_id=None):
        self.event = SimpleNamespace(sender_id=sender_id, launcher_id=launcher_id,
                                     message_chain=chain)
        self.prevented = []

    def prevent_default(self):
        self.prevented.append("default")

    def prevent_postorder(self):
        self.prevented.append("postorder")


@pytest.fixture
def deps(tmp_path, monkeypatch):
    # 面包屑写进 tmp_path，别污染真实 data/files
    monkeypatch.setattr(file_store, "BASE", tmp_path / "files")
    conversation_store.clear_cache()
    d = SimpleNamespace(
        settings=SimpleNamespace(group_prefix="!", bot_qq=12345, intercept_scope="off",
                                 bot_uuid="bot-1", fact_auto_threshold=0.85),
        spring=SimpleNamespace(
            profile=AsyncMock(return_value={"facts": {}, "stylePrompt": ""}),
            list_open_memos=AsyncMock(return_value=[]),
            reply_context=AsyncMock(return_value={
                "enabled": True, "replyPrompt": "", "rateLimit": 20,
                "outLastHour": 0, "recentMessages": []}),
            create_draft=AsyncMock(return_value={"id": 1}),
            sync_file_task=AsyncMock()),
        dify=SimpleNamespace(chat=AsyncMock(return_value={
            "answer": '{"action":"SILENT"}', "conversation_id": "conv-1"})),
        dispatcher=SimpleNamespace(handle=AsyncMock(return_value=None)),
    )
    d.log_queue = SimpleNamespace(enqueue=lambda payload: None)
    return d


@pytest.mark.asyncio
async def test_text_without_active_task_falls_through(deps):
    """没有待续文件任务时，普通文本正常进 Chatflow。"""
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="你好")]))

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    inputs = deps.dify.chat.await_args.kwargs["inputs"]
    assert inputs["event_type"] == "text"
    assert inputs["file_task_ref"] == ""
    assert inputs["structure"] == ""
    assert deps.dify.chat.await_args.args[0] == "你好"


@pytest.mark.asyncio
async def test_text_with_active_file_task_passes_structure(deps):
    """“姓名张三”这类补充必须带 structure，Dify 才能直接填。"""
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="姓名张三")]))
    task = {"taskRef": "ft_abc", "senderId": 1001, "chatId": None,
            "structure": {"kind": "xlsx", "sheets": []},
            "originalName": "名单.xlsx"}

    with patch("core.file_store.load_active_input", AsyncMock(return_value="ft_abc")), \
            patch("core.file_store.load_task", AsyncMock(return_value=task)):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    inputs = deps.dify.chat.await_args.kwargs["inputs"]
    assert inputs["event_type"] == "text"
    assert inputs["file_task_ref"] == "ft_abc"
    assert json.loads(inputs["structure"])["kind"] == "xlsx"
    assert inputs["file_name"] == "名单.xlsx"


@pytest.mark.asyncio
async def test_text_does_not_leak_task_from_another_chat(deps):
    """群聊 A 的文件任务不能被群聊 B 的文本带走。"""
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="!姓名张三")]), launcher_id="222")
    task = {"taskRef": "ft_abc", "senderId": 1001, "chatId": 111,
            "structure": {"kind": "xlsx"}, "originalName": "a.xlsx"}

    with patch("core.file_store.load_active_input", AsyncMock(return_value="ft_abc")), \
            patch("core.file_store.load_task", AsyncMock(return_value=task)):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=True)

    inputs = deps.dify.chat.await_args.kwargs["inputs"]
    assert inputs["file_task_ref"] == ""
    assert inputs["structure"] == ""


@pytest.mark.asyncio
async def test_file_message_ingests_then_sends_file_inputs(deps):
    """文件消息：先 ingest，再把 event_type=file + structure 交给 Chatflow。"""
    chain = pm.MessageChain([
        pm.Plain(text="把姓名填上"),
        pm.File(type="File", id="f1", name="名单.xlsx", size=1, url=""),
    ])
    ctx = FakeCtx(chain)
    task = {"taskRef": "ft_1", "senderId": 1001, "chatId": None,
            "structure": {"kind": "xlsx", "sheets": []},
            "originalName": "名单.xlsx"}

    with patch("core.file_task.ingest", AsyncMock(return_value=task)) as ingest:
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    ingest.assert_awaited_once()
    inputs = deps.dify.chat.await_args.kwargs["inputs"]
    assert inputs["event_type"] == "file"
    assert inputs["file_task_ref"] == "ft_1"
    assert inputs["file_kind"] == "xlsx"
    assert deps.dify.chat.await_args.args[0] == "把姓名填上"


@pytest.mark.asyncio
async def test_pending_hint_is_forwarded(deps):
    """Dify 会话重建后，本地备份的填写要求仍要带过去。"""
    chain = pm.MessageChain([pm.File(type="File", id="f1", name="a.xlsx", size=1, url="")])
    ctx = FakeCtx(chain)
    task = {"taskRef": "ft_1", "senderId": 1001, "chatId": None,
            "structure": {"kind": "xlsx"}, "originalName": "a.xlsx"}

    with patch("core.file_task.ingest", AsyncMock(return_value=task)), \
            patch("core.file_store.load_pending",
                  AsyncMock(return_value={"request": "把姓名填上", "taskRef": "ft_0"})):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    inputs = deps.dify.chat.await_args.kwargs["inputs"]
    assert inputs["pending_request_hint"] == "把姓名填上"
    assert inputs["last_task_ref"] == "ft_0"


@pytest.mark.asyncio
async def test_file_task_mirror_gets_conversation_and_pending_request(deps):
    """文件任务镜像要带上 conversation_id 和待填要求，Vue 才能按会话看任务。"""
    chain = pm.MessageChain([pm.File(type="File", id="f1", name="a.xlsx", size=1, url="")])
    ctx = FakeCtx(chain)
    task = {"taskRef": "ft_1", "senderId": 1001, "chatId": None,
            "structure": {"kind": "xlsx"}, "originalName": "a.xlsx"}

    with patch("core.file_task.ingest", AsyncMock(return_value=task)), \
            patch("core.file_store.load_pending",
                  AsyncMock(return_value={"request": "把姓名填上", "taskRef": "ft_0"})):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    body = deps.spring.sync_file_task.await_args.args[0]
    assert body["taskRef"] == "ft_1"
    assert body["conversationId"] == "conv-1"
    assert body["pendingRequest"] == "把姓名填上"


@pytest.mark.asyncio
async def test_conversation_id_is_passed_to_dify(deps):
    """同一会话的第二条消息必须带上上一条返回的 conversation_id。"""
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="你好")]))
    deps.dify.chat.return_value = {
        "answer": '{"action":"SILENT"}', "conversation_id": "conv-old"}

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)), \
            patch("core.conversation_store.resolve",
                  AsyncMock(return_value="conv-old")) as resolve, \
            patch("core.conversation_store.bind", AsyncMock()) as bind:
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    resolve.assert_awaited_once_with(deps, "bot-1", "private", None, 1001)
    assert deps.dify.chat.await_args.kwargs["conversation_id"] == "conv-old"
    assert deps.dify.chat.await_args.kwargs["user"] == "private:1001"
    bind.assert_not_awaited()


@pytest.mark.asyncio
async def test_new_conversation_is_bound(deps):
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="你好")]))

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)), \
            patch("core.conversation_store.resolve", AsyncMock(return_value=None)), \
            patch("core.conversation_store.bind", AsyncMock()) as bind:
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    assert deps.dify.chat.await_args.kwargs["conversation_id"] is None
    bind.assert_awaited_once_with(deps, "bot-1", "private", None, 1001, "conv-1")


@pytest.mark.asyncio
async def test_missing_conversation_resets_and_retries(deps):
    """Dify 侧会话不存在时，不能把 400 直接吞成“无回复”。"""
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="你好")]))
    deps.dify.chat = AsyncMock(side_effect=[
        RuntimeError('Dify /chat-messages HTTP 400: '
                     '{"message":"Conversation Not Exists."}'),
        {"answer": '{"action":"SILENT"}', "conversation_id": "conv-new"},
    ])

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)), \
            patch("core.conversation_store.resolve", AsyncMock(return_value="conv-old")), \
            patch("core.conversation_store.reset", AsyncMock()) as reset, \
            patch("core.conversation_store.bind", AsyncMock()) as bind:
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    reset.assert_awaited_once_with(deps, "bot-1", "private", None, 1001)
    assert deps.dify.chat.await_count == 2
    assert "conversation_id" not in deps.dify.chat.await_args_list[1].kwargs
    bind.assert_awaited_once_with(deps, "bot-1", "private", None, 1001, "conv-new")


@pytest.mark.asyncio
async def test_open_memos_and_event_id_are_forwarded(deps):
    """未终结备忘要喂给 Dify：它只能从这里取 memoId 去改/取消。"""
    deps.spring.list_open_memos.return_value = [
        {"id": 12, "title": "班会", "content": "明天下午2点 S109",
         "dueTime": "2026-10-30T14:00", "status": "ACTIVE"}]
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="班会地点改成 S111")]))

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    inputs = deps.dify.chat.await_args.kwargs["inputs"]
    memos = json.loads(inputs["open_memos"])
    assert memos[0]["id"] == 12
    assert inputs["event_id"]


@pytest.mark.asyncio
async def test_spring_hiccup_does_not_block_the_reply(deps):
    """画像/备忘列表取不到时仍要进 Chatflow，否则用户一句回复都收不到。"""
    deps.spring.profile = AsyncMock(side_effect=RuntimeError("spring down"))
    deps.spring.list_open_memos = AsyncMock(side_effect=RuntimeError("spring down"))
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="明天下午两点开班会")]))

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    inputs = deps.dify.chat.await_args.kwargs["inputs"]
    assert inputs["open_memos"] == "[]"
    assert inputs["facts"] == "{}"
    deps.dispatcher.handle.assert_awaited_once()


@pytest.mark.asyncio
async def test_retry_reuses_the_same_event_id(deps):
    """会话丢失重试必须复用幂等键，否则同一条改期会被执行两遍。"""
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="班会取消了")]))
    deps.dify.chat = AsyncMock(side_effect=[
        RuntimeError('Dify /chat-messages HTTP 400: '
                     '{"message":"Conversation Not Exists."}'),
        {"answer": '{"action":"SILENT"}', "conversation_id": "conv-new"},
    ])

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)), \
            patch("core.conversation_store.resolve", AsyncMock(return_value="conv-old")), \
            patch("core.conversation_store.reset", AsyncMock()), \
            patch("core.conversation_store.bind", AsyncMock()):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    first, second = deps.dify.chat.await_args_list
    assert first.kwargs["inputs"]["event_id"] == second.kwargs["inputs"]["event_id"]


@pytest.mark.asyncio
async def test_reply_context_reaches_dify_and_degrades_when_spring_fails(deps):
    """人设与最近消息要送进 Chatflow；Spring 调不通时整轮照跑，只是自动回复保持关闭。"""
    deps.spring.reply_context = AsyncMock(return_value={
        "enabled": True, "replyPrompt": "随意点", "rateLimit": 20, "outLastHour": 0,
        "recentMessages": [{"senderName": "阿黄", "direction": "IN", "content": "在吗"}]})
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="你好")]))

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    inputs = deps.dify.chat.await_args.kwargs["inputs"]
    assert inputs["reply_prompt"] == "随意点"
    assert json.loads(inputs["recent_messages"])[0]["content"] == "在吗"
    assert "不透露" in inputs["reply_rules"]
    # 私聊没有群号：拿对方 QQ 当会话键，否则私聊的限流与开关永远查不到
    assert deps.spring.reply_context.await_args.args == (1001, "PRIVATE")


@pytest.mark.asyncio
async def test_reply_context_failure_does_not_break_the_turn(deps):
    deps.spring.reply_context = AsyncMock(side_effect=RuntimeError("spring down"))
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="你好")]))

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    assert deps.dify.chat.await_count == 1
    assert deps.spring.reply_context.await_count == 1
    assert deps.dify.chat.await_args.kwargs["inputs"]["recent_messages"] == "[]"


@pytest.mark.asyncio
async def test_chat_reply_is_silenced_when_the_switch_is_off(deps):
    """开关关着时，Dify 的闲聊应答不许真的发出去；但动作照跑，不报错。"""
    deps.spring.reply_context = AsyncMock(return_value={
        "enabled": False, "replyPrompt": "", "rateLimit": 20,
        "outLastHour": 0, "recentMessages": []})
    deps.dify.chat = AsyncMock(return_value={
        "answer": '{"action":"SEND","send_kind":"CHAT","content":"哈哈那挺好"}',
        "conversation_id": "conv-1"})
    deps.dispatcher.handle = AsyncMock(return_value="哈哈那挺好")
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="!你看这个")]), launcher_id="222")

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=True)

    assert deps.dispatcher.handle.await_args.args[0]["content"] == ""


@pytest.mark.asyncio
async def test_needs_confirm_creates_a_reply_draft_instead_of_sending(deps):
    deps.spring.reply_context = AsyncMock(return_value={
        "enabled": True, "replyPrompt": "", "rateLimit": 20,
        "outLastHour": 0, "recentMessages": []})
    deps.dify.chat = AsyncMock(return_value={
        "answer": '{"action":"SEND","send_kind":"CHAT","needs_confirm":true,'
                  '"content":"行，明天下午三点我过去"}',
        "conversation_id": "conv-1"})
    deps.dispatcher.handle = AsyncMock(return_value=None)
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="!明天三点你能来吗")]), launcher_id="222")

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=True)

    draft = deps.spring.create_draft.await_args
    assert draft.args[0] == "REPLY"
    # create_draft(type, senderId, chatId, title, payload)
    assert draft.args[4]["content"] == "行，明天下午三点我过去"
    # 标题不是「要发的话」：标题一写正文，草稿卡上同一句就会显示两遍
    assert draft.args[3] is None
    # 对方问了什么必须存下来，否则确认时看不到自己在回谁
    assert draft.args[4]["sourceContent"] == "明天三点你能来吗"
    assert deps.dispatcher.handle.await_args.args[0]["content"] == ""


@pytest.mark.asyncio
async def test_old_chatflow_without_the_new_inputs_still_answers(deps, monkeypatch):
    """Dify 的 Start 只认声明过的变量：插件先上、DSL 还没重导入时会 400。

    撞上就退回旧口径重试一次——否则升级插件的那一刻，群里所有消息都静默没人管。
    """
    from components.event_listener import default as listener

    monkeypatch.setattr(listener, "_legacy_chatflow", False)
    deps.dify.chat = AsyncMock(side_effect=[
        RuntimeError('Dify /chat-messages HTTP 400: '
                     '{"code":"invalid_param","message":"inputs invalid"}'),
        {"answer": '{"action":"SILENT"}', "conversation_id": "conv-1"},
    ])
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="你好")]))

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    first, second = deps.dify.chat.await_args_list
    assert "reply_rules" in first.kwargs["inputs"]
    assert "reply_rules" not in second.kwargs["inputs"]
    assert "recent_messages" not in second.kwargs["inputs"]
    assert second.kwargs["inputs"]["event_id"]          # 其余输入一个都不能少
    deps.dispatcher.handle.assert_awaited_once()


@pytest.mark.asyncio
async def test_legacy_mode_is_remembered_for_later_turns(deps, monkeypatch):
    """既然知道自己是旧 Chatflow，就别每条消息都先撞一次 400。"""
    from components.event_listener import default as listener

    monkeypatch.setattr(listener, "_legacy_chatflow", True)
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="你好")]))

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    inputs = deps.dify.chat.await_args.kwargs["inputs"]
    assert "reply_prompt" not in inputs
    assert "reply_rules" not in inputs
    assert deps.dify.chat.await_count == 1


@pytest.mark.asyncio
async def test_legacy_mode_expires_so_a_reimported_dsl_heals_without_restart(deps, monkeypatch):
    """记住「旧 Chatflow」只能是暂时的。

    DSL 重导入后，如果插件还在丢那三个输入，自动回复的开关就永远不生效，
    用户只能靠重启 LangBot 自救——所以过一段时间必须再探一次。
    """
    from components.event_listener import default as listener

    monkeypatch.setattr(listener, "_legacy_chatflow", True)
    monkeypatch.setattr(listener, "LEGACY_PROBE_SECONDS", 0)  # 立即到期
    deps.dify.chat = AsyncMock(return_value={
        "answer": '{"action":"SILENT"}', "conversation_id": "conv-1"})
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="你好")]))

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    inputs = deps.dify.chat.await_args.kwargs["inputs"]
    assert "reply_rules" in inputs          # 到期后用完整输入再探一次
    assert listener._legacy_chatflow is False


@pytest.mark.asyncio
async def test_draft_failure_leaves_a_trace_and_logs_the_apology(deps):
    """草稿没建成 = 这条回复彻底没了。至少要在面包屑和消息日志里留下痕迹。"""
    deps.spring.reply_context = AsyncMock(return_value={
        "enabled": True, "replyPrompt": "", "rateLimit": 20,
        "outLastHour": 0, "recentMessages": []})
    deps.dify.chat = AsyncMock(return_value={
        "answer": '{"action":"SEND","send_kind":"CHAT","needs_confirm":true,'
                  '"content":"行，明天下午三点我过去"}',
        "conversation_id": "conv-1"})
    deps.spring.create_draft = AsyncMock(side_effect=RuntimeError("spring down"))
    sent = []
    deps.log_queue = SimpleNamespace(enqueue=lambda payload: sent.append(payload))
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="!明天三点你能来吗")]), launcher_id="222")

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)), \
            patch("components.event_listener.default.reply_text", AsyncMock()) as reply_text:
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=True)

    # 叮嘱没发出去（走的是道歉语），道歉语必须进 OUT 日志，否则网页上什么都看不到
    assert deps.dispatcher.handle.await_args.args[0]["content"] == ""
    reply_text.assert_awaited_once()
    assert sent[-1]["direction"] == "OUT"
    assert sent[-1]["content"] == reply_text.await_args.args[1]
    assert sent[-1]["replyKind"] == "TASK"
