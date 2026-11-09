package com.cinebot.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A deterministic stand-in for Gemini: each model call takes the next scripted step (a tool call or a text
 * answer) and records the prompt it received. Lets integration tests drive whole conversations through the real
 * agent loop, memory, tools and database without an API key, and assert what the model was shown.
 */
public class ScriptedChatModel implements ChatModel {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Deque<Function<Prompt, AssistantMessage>> script = new ArrayDeque<>();
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

    public synchronized void reset() {
        script.clear();
        prompts.clear();
    }

    /** Next model step: request one tool call with these JSON arguments. */
    public ScriptedChatModel thenCall(String tool, String arguments) {
        return then(p -> toolCalls(new String[][] {{tool, arguments}}));
    }

    /** Next model step: a tool call whose arguments are computed from what the model was shown. */
    public ScriptedChatModel thenCall(String tool, Function<Prompt, String> arguments) {
        return then(p -> toolCalls(new String[][] {{tool, arguments.apply(p)}}));
    }

    /** Next model step: several tool calls in one response (parallel function calling). */
    public ScriptedChatModel thenCalls(String[]... calls) {
        return then(p -> toolCalls(calls));
    }

    public ScriptedChatModel thenReply(String text) {
        return then(p -> new AssistantMessage(text));
    }

    public ScriptedChatModel thenReply(Function<Prompt, String> text) {
        return then(p -> new AssistantMessage(text.apply(p)));
    }

    /** Next model step: the provider fails (outage, quota, timeout). */
    public ScriptedChatModel thenFail() {
        return then(p -> {
            throw new IllegalStateException("simulated provider outage");
        });
    }

    public synchronized ScriptedChatModel then(Function<Prompt, AssistantMessage> step) {
        script.add(step);
        return this;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Function<Prompt, AssistantMessage> step;
        synchronized (this) {
            prompts.add(prompt);
            step = script.poll();
        }
        if (step == null) {
            throw new AssertionError("Unexpected model call; script exhausted. Last message: "
                    + prompt.getInstructions().getLast());
        }
        return new ChatResponse(List.of(new Generation(step.apply(prompt))));
    }

    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    public boolean exhausted() {
        return script.isEmpty();
    }

    // --- helpers for computing arguments from what the model saw ---

    /** The first response of the most recent tool result in the prompt. */
    public static JsonNode lastToolResult(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolResponseMessage tool) {
                try {
                    return JSON.readTree(tool.getResponses().getFirst().responseData());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new AssertionError("No tool result in prompt");
    }

    public static String systemText(Prompt prompt) {
        return prompt.getInstructions().stream().filter(m -> m instanceof SystemMessage).map(Message::getText)
                .findFirst().orElseThrow();
    }

    /** A value the conversation-state block shows the model, e.g. "confirmationId" or "showId". */
    public static String fromState(Prompt prompt, String key) {
        Matcher m = Pattern.compile(key + " ([A-Z0-9]+)").matcher(systemText(prompt));
        if (!m.find()) {
            throw new AssertionError(key + " not in conversation state:\n" + systemText(prompt));
        }
        return m.group(1);
    }

    private static AssistantMessage toolCalls(String[][] calls) {
        List<AssistantMessage.ToolCall> list = new java.util.ArrayList<>();
        for (String[] call : calls) {
            list.add(new AssistantMessage.ToolCall("call_" + UUID.randomUUID().toString().substring(0, 8),
                    "function", call[0], call[1]));
        }
        return AssistantMessage.builder().content("").toolCalls(list).build();
    }
}
