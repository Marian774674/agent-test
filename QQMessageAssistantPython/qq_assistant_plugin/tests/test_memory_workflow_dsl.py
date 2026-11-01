"""「记忆与整理」Workflow 的离线体检。

真正的验收要靠导入 Dify + 真机跑一轮；这里钉死三件一错就是静默失败的事：
接线错（跑不到 LLM）、Start 变量名和 Spring 发的不一致（Dify 直接 400）、
解析节点把没有证据的条目放过去（编造的证据会落进数据库）。
"""
import json
from pathlib import Path

import pytest
import yaml

DSL = Path(__file__).resolve().parents[3] / "design" / "QQAssistant-memory-workflow.yml"

pytestmark = pytest.mark.skipif(not DSL.exists(), reason="memory workflow DSL 不存在")


@pytest.fixture(scope="module")
def graph():
    doc = yaml.safe_load(DSL.read_text(encoding="utf-8"))
    g = doc["workflow"]["graph"]
    return doc, g, {n["id"]: n for n in g["nodes"]}


def _main(nodes, node_id):
    ns = {}
    exec(nodes[node_id]["data"]["code"], ns)
    return ns["main"]


def test_is_a_workflow_app(graph):
    doc, _, _ = graph
    assert doc["app"]["mode"] == "workflow"
    assert doc["app"]["name"] == "记忆与整理"


def test_branches_are_wired(graph):
    """每条支路一张表对死：多一条少一条都是接线错。"""
    _, g, _ = graph
    edges = {(e["source"], e["sourceHandle"]): e["target"] for e in g["edges"]}
    assert edges == {
        ("start", "source"): "is_digest",
        ("is_digest", "true"): "digest_llm",
        ("is_digest", "false"): "is_extract",
        ("digest_llm", "source"): "parse_digest",
        ("parse_digest", "source"): "end_digest",
        ("is_extract", "true"): "extract_llm",
        ("is_extract", "false"): "is_resolve",
        ("extract_llm", "source"): "parse_extract",
        ("parse_extract", "source"): "end_extract",
        ("is_resolve", "true"): "resolve_llm",
        ("is_resolve", "false"): "is_expand",
        ("resolve_llm", "source"): "parse_resolve",
        ("parse_resolve", "source"): "end_resolve",
        ("is_expand", "true"): "expand_llm",
        ("is_expand", "false"): "answer_llm",
        ("expand_llm", "source"): "parse_expand",
        ("parse_expand", "source"): "end_expand",
        ("answer_llm", "source"): "parse_ask_answer",
        ("parse_ask_answer", "source"): "end_answer",
    }


def test_start_declares_exactly_the_inputs_spring_sends(graph):
    """Dify 只认 Start 里声明过的变量，少一个就是那一段永远是空输入。"""
    _, _, nodes = graph
    declared = {v["variable"] for v in nodes["start"]["data"]["variables"]}
    assert declared == {
        "task", "stage", "current_time", "chat_name", "messages", "profile", "focus",
        "question", "known_contacts", "owner_aliases", "candidates", "time_hint",
    }


def test_is_resolve_branches_on_stage(graph):
    """ask 分两段（先认人、再作答），分岔只认 stage。"""
    _, _, nodes = graph
    cases = nodes["is_resolve"]["data"]["cases"]
    selectors = [c["conditions"][0]["variable_selector"] for c in cases]
    assert selectors == [["start", "stage"]]
    assert cases[0]["conditions"][0]["value"] == "resolve"


def test_prompt_pins_the_evidence_contract(graph):
    _, _, nodes = graph
    system = nodes["digest_llm"]["data"]["prompt_template"][0]["text"]
    assert "source_msg_ids" in system
    assert "[@我]" in system
    assert "不少于 4" in system or "不低于 4" in system
    assert "[]" in system


def test_extract_prompt_pins_the_predicate_vocabulary(graph):
    _, _, nodes = graph
    system = nodes["extract_llm"]["data"]["prompt_template"][0]["text"]
    for word in ("生日", "姓名", "专业", "班级", "学号", "住址", "电话", "时间安排", "偏好", "关系", "其他"):
        assert word in system
    assert "source_msg_ids" in system


def test_answer_prompt_forbids_uncited_conclusions(graph):
    _, _, nodes = graph
    system = nodes["answer_llm"]["data"]["prompt_template"][0]["text"]
    assert "used_ids" in system
    assert "不要编造" in system or "不许编造" in system or "不足以回答" in system


def test_parse_digest_drops_items_without_evidence(graph):
    _, _, nodes = graph
    raw = """
```json
[{"summary":"班主任提醒明天开班会","importance":4,"keywords":"班会","mentions_me":false,"source_msg_ids":[1]},
 {"summary":"没有证据的一条","importance":5,"keywords":"","mentions_me":false,"source_msg_ids":[]},
 {"summary":"","importance":3,"source_msg_ids":[2]}]
```
"""
    items = json.loads(_main(nodes, "parse_digest")(raw)["items"])
    assert [i["summary"] for i in items] == ["班主任提醒明天开班会"]


def test_parse_digest_returns_empty_on_garbage(graph):
    _, _, nodes = graph
    main = _main(nodes, "parse_digest")
    assert main("模型开始胡说八道了")["items"] == "[]"
    assert main("")["items"] == "[]"


def test_parse_extract_drops_notes_without_evidence(graph):
    _, _, nodes = graph
    raw = json.dumps({
        "contacts": [{"sender_id": 7, "alias": "阿黄", "relation": "室友"}],
        "notes": [
            {"subject_sender_id": 7, "predicate": "生日", "value": "11月11日", "source_msg_ids": [3]},
            {"subject_sender_id": 7, "predicate": "生日", "value": "编的", "source_msg_ids": []},
        ],
    }, ensure_ascii=False)
    out = _main(nodes, "parse_extract")(raw)
    assert json.loads(out["contacts"])[0]["alias"] == "阿黄"
    assert [n["value"] for n in json.loads(out["notes"])] == ["11月11日"]


def test_parse_extract_coerces_numeric_string_ids(graph):
    """模型十次里有几次会把 QQ 号写成字符串；照原样用会被当成「不在本批内」全丢掉。"""
    _, _, nodes = graph
    raw = json.dumps({
        "contacts": [{"sender_id": "7", "alias": "阿黄", "relation": "室友"}],
        "notes": [{"subject_sender_id": "7", "predicate": "生日", "value": "11月11日",
                   "source_msg_ids": ["3"]}],
    }, ensure_ascii=False)
    out = _main(nodes, "parse_extract")(raw)
    assert json.loads(out["contacts"])[0]["sender_id"] == 7
    assert json.loads(out["notes"])[0]["subject_sender_id"] == 7


def test_parse_extract_drops_a_note_whose_subject_cannot_be_read(graph):
    """subject 读不成数字时必须整条丢掉：null 的含义是「这是我自己的事」，猜错就成了别人的事实。"""
    _, _, nodes = graph
    raw = json.dumps({
        "contacts": [],
        "notes": [
            {"subject_sender_id": "阿黄", "predicate": "生日", "value": "张冠李戴", "source_msg_ids": [3]},
            {"subject_sender_id": None, "predicate": "专业", "value": "计算机", "source_msg_ids": [4]},
        ],
    }, ensure_ascii=False)
    notes = json.loads(_main(nodes, "parse_extract")(raw)["notes"])
    assert [n["value"] for n in notes] == ["计算机"]
    assert notes[0]["subject_sender_id"] is None


def test_parse_extract_returns_empty_shapes_on_garbage(graph):
    _, _, nodes = graph
    out = _main(nodes, "parse_extract")("模型又胡说八道了")
    assert out["contacts"] == "[]"
    assert out["notes"] == "[]"


def test_parse_resolve_returns_empty_shapes_on_garbage(graph):
    _, _, nodes = graph
    out = _main(nodes, "parse_resolve")("嗯？")
    assert out["entities"] == "[]"
    assert out["keywords"] == "[]"
    assert out["time_hint"] == ""


def test_parse_ask_answer_drops_non_list_used_ids(graph):
    _, _, nodes = graph
    main = _main(nodes, "parse_ask_answer")
    assert json.loads(main('{"answer":"阿黄下周考试","used_ids":"msg:12"}')["used_ids"]) == []
    assert main("胡说八道")["answer"] == ""
    ok = main('{"answer":"阿黄下周考试","used_ids":["msg:12"]}')
    assert ok["answer"] == "阿黄下周考试"
    assert json.loads(ok["used_ids"]) == ["msg:12"]


def test_parse_ask_answer_blanks_an_answer_without_citations(graph):
    """有结论却没引用，等于让模型对着候选集答它自己的记忆——直接作废。"""
    _, _, nodes = graph
    assert _main(nodes, "parse_ask_answer")('{"answer":"阿黄下周考试","used_ids":[]}')["answer"] == ""


def test_end_nodes_expose_unique_output_names(graph):
    """Dify 要求各结束节点的输出变量名全局唯一，否则「发布」直接被清单挡住。

    五个结束节点各带自己的输出名，Spring 按名字取值——重名就是静默串台。
    expand 的结束节点用 expand_* 前缀，避免和 resolve 的 queries/time_* 撞名。
    """
    _, _, nodes = graph
    names = [
        out["variable"]
        for node in nodes.values()
        if node["data"].get("type") == "end"
        for out in node["data"].get("outputs", [])
    ]
    assert len(names) == len(set(names)), f"结束节点输出变量重名：{names}"
    assert set(names) == {
        "items", "contacts", "notes",
        "entities", "keywords", "queries", "time_hint", "time_from", "time_to",
        "answer", "used_ids",
        "expand_queries", "expand_entities", "expand_time_hint",
        "expand_time_from", "expand_time_to",
    }
