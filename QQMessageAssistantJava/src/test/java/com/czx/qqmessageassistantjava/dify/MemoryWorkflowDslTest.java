package com.czx.qqmessageassistantjava.dify;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 静态校验 Dify 工作流 DSL：确保仓库里的 YAML 结构完整、引用不悬空。
 *
 * <p>真正的导入 / 发布只能在 Dify 控制台完成，这里替代不了那一步。
 */
class MemoryWorkflowDslTest {

    private static final Pattern SELECTOR =
            Pattern.compile("\\{\\{#([A-Za-z0-9_\\-]+)\\.([A-Za-z0-9_\\-]+)#\\}\\}");

    private static Map<String, Object> root;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        Path path = Path.of("../design/QQAssistant-memory-workflow.yml");
        assertThat(Files.exists(path)).as("工作流文件应存在：%s", path.toAbsolutePath()).isTrue();
        LoaderOptions options = new LoaderOptions();
        try (InputStream in = Files.newInputStream(path)) {
            root = new Yaml(new SafeConstructor(options)).load(in);
        }
        assertThat(root).isNotNull();
    }

    @Test
    void expand_llm_and_parse_expand_nodes_exist() {
        assertThat(nodesById()).containsKeys("expand_llm", "parse_expand");
    }

    @Test
    void is_expand_branch_reads_stage_from_start() {
        assertThat(conditionSelectors("is_expand"))
                .as("is_expand 的条件应引用 start.stage")
                .anySatisfy(selector -> assertThat(selector).containsExactly("start", "stage"));

        Map<String, Object> data = data("is_expand");
        List<Map<String, Object>> cases = listOfMaps(data.get("cases"));
        assertThat(cases).isNotEmpty();
        Map<String, Object> condition = listOfMaps(cases.get(0).get("conditions")).get(0);
        assertThat(condition.get("value")).isEqualTo("expand");
    }

    @Test
    void parse_resolve_exposes_queries_and_time_range() {
        assertThat(outputKeys("parse_resolve"))
                .contains("entities", "keywords", "queries", "time_hint", "time_from", "time_to");
    }

    @Test
    void parse_expand_exposes_queries_entities_and_time_range() {
        assertThat(outputKeys("parse_expand"))
                .contains("queries", "entities", "time_hint", "time_from", "time_to");
    }

    @Test
    void answer_prompt_tells_model_to_look_at_msg_mention_evidence_first() {
        assertThat(promptText("answer_llm")).contains("msg-mention");
    }

    @Test
    void node_ids_are_unique() {
        List<String> ids = nodes().stream().map(node -> String.valueOf(node.get("id"))).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void every_edge_endpoint_exists() {
        Set<String> ids = nodesById().keySet();
        for (Map<String, Object> edge : edges()) {
            assertThat(ids).as("edge source %s", edge.get("source")).contains((String) edge.get("source"));
            assertThat(ids).as("edge target %s", edge.get("target")).contains((String) edge.get("target"));
        }
    }

    @Test
    void every_template_selector_points_at_an_existing_node_and_key() {
        Map<String, Map<String, Object>> nodes = nodesById();
        for (Map<String, Object> node : nodes.values()) {
            String nodeId = String.valueOf(node.get("id"));
            for (String text : stringsOf(node)) {
                Matcher matcher = SELECTOR.matcher(text);
                while (matcher.find()) {
                    String sourceId = matcher.group(1);
                    String key = matcher.group(2);
                    assertThat(nodes)
                            .as("节点 %s 的模板引用了不存在的节点 %s", nodeId, sourceId)
                            .containsKey(sourceId);
                    assertThat(selectableKeys(nodes.get(sourceId)))
                            .as("节点 %s 的模板引用了 %s 上不存在的输出 %s", nodeId, sourceId, key)
                            .contains(key);
                }
            }
        }
    }

    @Test
    void old_is_resolve_false_to_answer_edge_is_gone() {
        assertThat(routes())
                .as("is_resolve 的 false 分支已被 is_expand 取代，不能再直连 answer_llm")
                .doesNotContain("is_resolve[false]→answer_llm");
    }

    @Test
    void expand_chain_runs_llm_parse_end() {
        assertThat(routes())
                .as("expand_llm → parse_expand → end_expand 必须完整")
                .contains("expand_llm[source]→parse_expand", "parse_expand[source]→end_expand");
    }

    @Test
    void end_node_output_names_are_globally_unique() {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> node : nodes()) {
            Map<String, Object> data = dataOf(node);
            if ("end".equals(data.get("type"))) {
                for (Map<String, Object> output : listOfMaps(data.get("outputs"))) {
                    names.add(String.valueOf(output.get("variable")));
                }
            }
        }
        assertThat(names).as("End 节点输出名不能重复：%s", names).doesNotHaveDuplicates();
        assertThat(names).contains(
                "expand_queries", "expand_entities", "expand_time_hint",
                "expand_time_from", "expand_time_to");
    }

    @Test
    void expand_branch_is_wired_between_resolve_and_answer() {
        assertThat(edges())
                .as("is_resolve 的 false 分支应进入 is_expand")
                .anySatisfy(edge -> {
                    assertThat(edge.get("source")).isEqualTo("is_resolve");
                    assertThat(edge.get("sourceHandle")).isEqualTo("false");
                    assertThat(edge.get("target")).isEqualTo("is_expand");
                });
        assertThat(edges())
                .as("is_expand 的 true 分支应进入 expand_llm")
                .anySatisfy(edge -> {
                    assertThat(edge.get("source")).isEqualTo("is_expand");
                    assertThat(edge.get("sourceHandle")).isEqualTo("true");
                    assertThat(edge.get("target")).isEqualTo("expand_llm");
                });
        assertThat(edges())
                .as("is_expand 的 false 分支应进入 answer_llm")
                .anySatisfy(edge -> {
                    assertThat(edge.get("source")).isEqualTo("is_expand");
                    assertThat(edge.get("sourceHandle")).isEqualTo("false");
                    assertThat(edge.get("target")).isEqualTo("answer_llm");
                });
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> graph() {
        Map<String, Object> workflow = (Map<String, Object>) root.get("workflow");
        return (Map<String, Object>) workflow.get("graph");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodes() {
        return (List<Map<String, Object>>) graph().get("nodes");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> edges() {
        return (List<Map<String, Object>>) graph().get("edges");
    }

    private static Map<String, Map<String, Object>> nodesById() {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes()) {
            byId.put(String.valueOf(node.get("id")), node);
        }
        return byId;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(String nodeId) {
        Map<String, Object> node = nodesById().get(nodeId);
        assertThat(node).as("节点 %s 应存在", nodeId).isNotNull();
        return (Map<String, Object>) node.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<String, Object> node) {
        return (Map<String, Object>) node.get("data");
    }

    /** 把每条边收成 {@code source[handle]→target}，便于做精确的接线断言。 */
    private static List<String> routes() {
        List<String> routes = new ArrayList<>();
        for (Map<String, Object> edge : edges()) {
            routes.add(edge.get("source") + "[" + edge.get("sourceHandle") + "]→" + edge.get("target"));
        }
        return routes;
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> conditionSelectors(String nodeId) {
        List<List<String>> selectors = new ArrayList<>();
        for (Map<String, Object> branch : listOfMaps(data(nodeId).get("cases"))) {
            for (Map<String, Object> condition : listOfMaps(branch.get("conditions"))) {
                selectors.add((List<String>) condition.get("variable_selector"));
            }
        }
        return selectors;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> outputKeys(String nodeId) {
        Object outputs = data(nodeId).get("outputs");
        if (!(outputs instanceof Map<?, ?> map)) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (Object key : map.keySet()) {
            keys.add(String.valueOf(key));
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static String promptText(String nodeId) {
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> template : listOfMaps(data(nodeId).get("prompt_template"))) {
            text.append(template.get("text")).append('\n');
        }
        return text.toString();
    }

    /**
     * 节点里可以被 {@code {{#id.key#}}} 引用的键。
     * start 用变量名，llm 固定输出 text，code 输出在 outputs 里。
     */
    @SuppressWarnings("unchecked")
    private static Set<String> selectableKeys(Map<String, Object> node) {
        Map<String, Object> data = (Map<String, Object>) node.get("data");
        String type = String.valueOf(data.get("type"));
        Set<String> keys = new LinkedHashSet<>();
        switch (type) {
            case "start" -> {
                for (Map<String, Object> variable : listOfMaps(data.get("variables"))) {
                    keys.add(String.valueOf(variable.get("variable")));
                }
            }
            case "llm" -> keys.add("text");
            case "code" -> {
                if (data.get("outputs") instanceof Map<?, ?> outputs) {
                    for (Object key : outputs.keySet()) {
                        keys.add(String.valueOf(key));
                    }
                }
            }
            default -> {
            }
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static List<String> stringsOf(Object value) {
        List<String> out = new ArrayList<>();
        collectStrings(value, out);
        return out;
    }

    private static void collectStrings(Object value, List<String> out) {
        if (value instanceof String text) {
            out.add(text);
        } else if (value instanceof Map<?, ?> map) {
            for (Object child : map.values()) {
                collectStrings(child, out);
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                collectStrings(child, out);
            }
        }
    }
}
