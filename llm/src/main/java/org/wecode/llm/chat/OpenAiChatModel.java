package org.wecode.llm.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.wecode.llm.model.FinishReason;
import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.LlmUsage;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.Role;
import org.wecode.llm.model.ToolCall;
import org.wecode.llm.model.ToolSpec;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * OpenAI 兼容 Chat Completions 实现（DeepSeek / 中转站等通用）。
 * <p>
 * 当前为非流式请求；对外返回完整 {@link LlmResponse}。后续可改为 SSE 聚合，不改接口。
 * <p>
 * URL 约定：{@code baseUrl} 由调用方决定是否包含 {@code /v1}，
 * 本类只做「去尾斜杠 + {@code /chat/completions}」。
 */
public final class OpenAiChatModel implements ChatModel {

    /**
     * 推理/思考相关可选参数（来自入口配置拆解，可为 null）。
     *
     * @param enabled            是否解析响应中的思考内容并填入 {@link LlmResponse#thinking()}
     * @param sendThinkingToggle 是否向 Provider 请求体发送思考模式开关
     * @param reasoningEffort    如 low/medium/high；写入请求 {@code reasoning_effort}
     * @param thinkingFieldName  响应里思考字段名，默认 {@code reasoning_content}
     */
    public record ThinkingOptions(
            Boolean enabled,
            Boolean sendThinkingToggle,
            String reasoningEffort,
            String thinkingFieldName
    ) {
        public static ThinkingOptions none() {
            return new ThinkingOptions(null, null, null, null);
        }

        /** 解析用字段名；未配置时用常见默认值。 */
        public String resolvedFieldName() {
            // 自定义优先，否则用常见默认
            if (thinkingFieldName != null && !thinkingFieldName.isBlank()) {
                return thinkingFieldName;
            }
            return "reasoning_content";
        }

        /** 是否把思考文本写入 {@link LlmResponse}。 */
        public boolean shouldCaptureThinking() {
            return Boolean.TRUE.equals(enabled);
        }

        /** 是否需要向服务端显式发送思考模式开关。 */
        public boolean shouldSendThinkingToggle() {
            return Boolean.TRUE.equals(sendThinkingToggle);
        }
    }

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Double temperature;
    private final ThinkingOptions thinkingOptions;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * @param baseUrl     API 根，到 host 或到 /v1 均可，勿带 /chat/completions
     * @param apiKey      Bearer Token
     * @param model       模型名
     * @param temperature 采样温度；可为 null（请求体省略）
     */
    public OpenAiChatModel(String baseUrl, String apiKey, String model, Double temperature) {
        this(baseUrl, apiKey, model, temperature, ThinkingOptions.none());
    }

    /**
     * @param baseUrl          API 根
     * @param apiKey           Bearer Token
     * @param model            模型名
     * @param temperature      采样温度；可为 null
     * @param thinkingOptions  推理相关选项；可为 null
     */
    public OpenAiChatModel(
            String baseUrl,
            String apiKey,
            String model,
            Double temperature,
            ThinkingOptions thinkingOptions
    ) {
        this(
                baseUrl,
                apiKey,
                model,
                temperature,
                thinkingOptions,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
                new ObjectMapper()
        );
    }

    /**
     * 可注入 HTTP / JSON 依赖（自定义超时、测试替身等）。
     *
     * @param baseUrl          API 根
     * @param apiKey           Bearer Token
     * @param model            模型名
     * @param temperature      采样温度；可为 null
     * @param thinkingOptions  推理相关选项；可为 null
     * @param httpClient       HTTP 客户端
     * @param objectMapper     JSON 工具
     */
    public OpenAiChatModel(
            String baseUrl,
            String apiKey,
            String model,
            Double temperature,
            ThinkingOptions thinkingOptions,
            HttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.baseUrl = requireNonBlank(baseUrl, "baseUrl");
        this.apiKey = requireNonBlank(apiKey, "apiKey");
        this.model = requireNonBlank(model, "model");
        this.temperature = temperature;
        this.thinkingOptions = thinkingOptions == null ? ThinkingOptions.none() : thinkingOptions;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public LlmResponse chat(List<Message> messages, List<ToolSpec> tools) {
        return chat(messages, tools, ChatRequestOptions.defaults());
    }

    @Override
    public LlmResponse chat(List<Message> messages, List<ToolSpec> tools, ChatRequestOptions options) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(options, "options");
        // tools 允许 null，按空列表处理
        List<ToolSpec> toolSpecs = tools == null ? List.of() : tools;
        try {
            String body = buildRequestBody(messages, toolSpecs, options);
            HttpResponse<String> response = postChatCompletion(body);
            // 非 2xx：带上状态码与响应片段，方便排查中转站
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Chat Completions 失败: HTTP " + response.statusCode()
                                + " body=" + truncate(response.body(), 800)
                );
            }
            return parseResponse(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Chat Completions 请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Chat Completions 被中断", e);
        }
    }

    /** 拼出 Chat Completions 完整 URL（同包测试可直接断言）。 */
    String chatCompletionsUrl() {
        String root = trimTrailingSlashes(baseUrl);
        return root + "/chat/completions";
    }

    /** 组装请求 JSON（同包测试可直接断言；非流式）。 */
    String buildRequestBody(List<Message> messages, List<ToolSpec> tools) throws IOException {
        return buildRequestBody(messages, tools, ChatRequestOptions.defaults());
    }

    /**
     * 组装带单次请求选项的 JSON 请求体。
     *
     * @param messages 当前请求消息
     * @param tools    当前请求工具定义
     * @param options  单次请求选项
     * @return OpenAI Chat Completions 兼容 JSON
     * @throws IOException 工具参数 JSON 非法时抛出
     */
    String buildRequestBody(
            List<Message> messages,
            List<ToolSpec> tools,
            ChatRequestOptions options
    ) throws IOException {
        Objects.requireNonNull(options, "options");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("stream", false);
        // 摘要等内部调用可覆盖输出上限；未设置时不改变既有 Provider 请求体。
        if (options.maxOutputTokens() != null) {
            root.put("max_tokens", options.maxOutputTokens());
        }
        // 有温度才写入，避免部分模型拒收 null
        if (temperature != null && !thinkingOptions.shouldCaptureThinking()) {
            root.put("temperature", temperature);
        }
        // Provider 显式配置思考开关时，按 DeepSeek 等兼容接口的扩展格式发送。
        if (thinkingOptions.shouldSendThinkingToggle()) {
            root.putObject("thinking").put(
                    "type",
                    thinkingOptions.shouldCaptureThinking() ? "enabled" : "disabled"
            );
        }
        // OpenAI 兼容推理强度；网关不支持时可能忽略或报错
        if (thinkingOptions.reasoningEffort() != null && !thinkingOptions.reasoningEffort().isBlank()) {
            root.put("reasoning_effort", thinkingOptions.reasoningEffort());
        }

        ArrayNode messagesNode = root.putArray("messages");
        for (Message message : messages) {
            messagesNode.add(toMessageNode(message));
        }

        // 有工具时才带 tools，避免空数组踩兼容问题
        if (!tools.isEmpty()) {
            ArrayNode toolsNode = root.putArray("tools");
            for (ToolSpec tool : tools) {
                toolsNode.add(toToolNode(tool));
            }
        }

        return objectMapper.writeValueAsString(root);
    }

    private ObjectNode toMessageNode(Message message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", message.role().name().toLowerCase());

        // tool 角色必须带 tool_call_id
        if (message.role() == Role.TOOL) {
            node.put("tool_call_id", message.toolCallId());
            node.put("content", message.content());
            return node;
        }

        // assistant 可能只有 tool_calls、content 为 null
        if (message.content() != null) {
            node.put("content", message.content());
        } else if (message.toolCalls().isEmpty()) {
            node.putNull("content");
        }

        // 思考模式 + 工具调用时，DeepSeek 要求后续请求完整回传 assistant 的推理内容。
        if (message.role() == Role.ASSISTANT
                && thinkingOptions.shouldCaptureThinking()
                && message.reasoningContent() != null
                && !message.reasoningContent().isBlank()) {
            node.put(thinkingOptions.resolvedFieldName(), message.reasoningContent());
        }

        if (!message.toolCalls().isEmpty()) {
            ArrayNode toolCallsNode = node.putArray("tool_calls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode callNode = toolCallsNode.addObject();
                callNode.put("id", call.id());
                callNode.put("type", "function");
                ObjectNode functionNode = callNode.putObject("function");
                functionNode.put("name", call.name());
                // OpenAI：arguments 是 JSON 对象的字符串
                functionNode.put("arguments", call.argumentsJson());
            }
        }
        return node;
    }

    private ObjectNode toToolNode(ToolSpec tool) throws IOException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "function");
        ObjectNode functionNode = node.putObject("function");
        functionNode.put("name", tool.name());
        functionNode.put("description", tool.description());
        // parameters 线上是对象，不是字符串
        functionNode.set("parameters", objectMapper.readTree(tool.parametersJson()));
        return node;
    }

    private HttpResponse<String> postChatCompletion(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(chatCompletionsUrl()))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * 解析 OpenAI Chat Completions 兼容响应。
     * <p>
     * 保持包可见仅供无网络单元测试直接验证响应映射。
     *
     * @param responseBody Provider 返回的完整 JSON 响应
     * @return 映射后的领域响应
     * @throws IOException 响应不是合法 JSON 时抛出
     */
    LlmResponse parseResponse(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.get("choices");
        // 没有 choices 时无法映射
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException(
                    "响应缺少 choices: " + truncate(responseBody, 800)
            );
        }

        JsonNode first = choices.get(0);
        JsonNode message = first.get("message");
        // 没有 message 时无法映射
        if (message == null || message.isNull()) {
            throw new IllegalStateException(
                    "响应缺少 choices[0].message: " + truncate(responseBody, 800)
            );
        }

        String content = textOrNull(message.get("content"));
        List<ToolCall> toolCalls = parseToolCalls(message.get("tool_calls"));
        FinishReason finishReason = mapFinishReason(textOrNull(first.get("finish_reason")), toolCalls);
        // 仅在配置要求返回思考时填入；否则保持 null
        String thinking = null;
        if (thinkingOptions.shouldCaptureThinking()) {
            thinking = extractThinking(message, thinkingOptions.resolvedFieldName());
        }
        // usage 缺失或核心字段不完整时返回 null，不能影响已成功解析的模型回答。
        LlmUsage usage = parseUsage(root.get("usage"));
        return new LlmResponse(content, toolCalls, finishReason, thinking, usage);
    }

    /**
     * 解析 OpenAI Chat Completions 兼容 usage，映射 DeepSeek 的缓存和推理明细。
     *
     * @param usageNode 响应根节点的 {@code usage}
     * @return 核心统计完整时的用量；否则为 {@code null}
     */
    private static LlmUsage parseUsage(JsonNode usageNode) {
        // Provider 未返回 usage，或返回值不是对象时，按不可用处理。
        if (usageNode == null || usageNode.isNull() || !usageNode.isObject()) {
            return null;
        }
        Long inputTokens = nonNegativeIntegralOrNull(usageNode.get("prompt_tokens"));
        Long outputTokens = nonNegativeIntegralOrNull(usageNode.get("completion_tokens"));
        Long totalTokens = nonNegativeIntegralOrNull(usageNode.get("total_tokens"));
        // 三项核心统计不完整时不能构造半真半假的 usage。
        if (inputTokens == null || outputTokens == null || totalTokens == null) {
            return null;
        }

        JsonNode completionDetails = usageNode.get("completion_tokens_details");
        Long reasoningTokens = completionDetails != null && completionDetails.isObject()
                ? nonNegativeIntegralOrNull(completionDetails.get("reasoning_tokens"))
                : null;
        return new LlmUsage(
                inputTokens,
                outputTokens,
                totalTokens,
                nonNegativeIntegralOrNull(usageNode.get("prompt_cache_hit_tokens")),
                nonNegativeIntegralOrNull(usageNode.get("prompt_cache_miss_tokens")),
                reasoningTokens
        );
    }

    /**
     * 将一个 JSON 整数安全转换为非负 long；缺失、非整数、溢出或负数均视为不可用。
     *
     * @param node 待转换的 JSON 节点
     * @return 合法的 token 数；否则为 {@code null}
     */
    private static Long nonNegativeIntegralOrNull(JsonNode node) {
        // token 统计只能是可放入 long 的非负整数。
        if (node == null || node.isNull() || !node.isIntegralNumber() || !node.canConvertToLong()) {
            return null;
        }
        long value = node.longValue();
        // 负数违反 token 用量语义，交由调用方按缺失处理。
        if (value < 0) {
            return null;
        }
        return value;
    }

    /**
     * 从 message 节点提取思考文本；先看配置字段名，再试常见别名。
     *
     * @param message   choices[0].message
     * @param fieldName 配置的字段名
     * @return 思考文本；没有则 null
     */
    private static String extractThinking(JsonNode message, String fieldName) {
        String primary = textOrNull(message.get(fieldName));
        // 配置字段有内容
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        // 常见别名兜底
        for (String alias : List.of("reasoning_content", "reasoning", "thinking", "reasoning_text")) {
            // 与主字段同名则跳过
            if (alias.equals(fieldName)) {
                continue;
            }
            String value = textOrNull(message.get(alias));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        // 无 tool_calls
        if (toolCallsNode == null || !toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return List.of();
        }
        List<ToolCall> result = new ArrayList<>();
        for (JsonNode callNode : toolCallsNode) {
            String id = textOrNull(callNode.get("id"));
            JsonNode function = callNode.get("function");
            String name = function == null ? null : textOrNull(function.get("name"));
            String arguments = function == null ? null : textOrNull(function.get("arguments"));
            // arguments 缺省时用空对象字符串，满足 ToolCall 非空约束
            if (arguments == null || arguments.isBlank()) {
                arguments = "{}";
            }
            result.add(new ToolCall(id, name, arguments));
        }
        return List.copyOf(result);
    }

    private static FinishReason mapFinishReason(String raw, List<ToolCall> toolCalls) {
        // 部分网关不返回 finish_reason，有 tool_calls 则视为 TOOL_CALLS
        if (raw == null || raw.isBlank()) {
            return toolCalls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS;
        }
        return switch (raw) {
            case "stop" -> FinishReason.STOP;
            case "tool_calls", "function_call" -> FinishReason.TOOL_CALLS;
            case "length" -> FinishReason.LENGTH;
            default -> toolCalls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS;
        };
    }

    private static String textOrNull(JsonNode node) {
        // 缺字段或 JSON null
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "null";
        }
        // 过长则截断，避免异常信息爆炸
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        // 空串视为无效配置
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String trimTrailingSlashes(String url) {
        int end = url.length();
        // 去掉末尾多余 /
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
