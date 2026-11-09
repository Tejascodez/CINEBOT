package com.cinebot.agent.service;

import com.cinebot.agent.config.AgentChatConfig.AgentLlm;
import com.cinebot.agent.config.AgentProperties;
import com.cinebot.agent.config.AgentToolsConfig.AgentToolbox;
import com.cinebot.agent.dto.AgentResponse;
import com.cinebot.agent.dto.ConversationResponse;
import com.cinebot.agent.memory.ConversationStore;
import com.cinebot.agent.memory.ConversationStore.Conversation;
import com.cinebot.agent.memory.PersistentChatMemory;
import com.cinebot.agent.tools.AgentTurn;
import com.cinebot.agent.tools.ConfirmationGate;
import com.cinebot.agent.tools.ConversationState;
import com.cinebot.agent.tools.PendingAction;
import com.cinebot.agent.tools.ToolReply;
import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.cinebot.common.time.BusinessTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * The agent: one call = one user message, processed with USER-CONTROLLED tool execution.
 * <pre>
 *   [system + state] + memory + user message
 *        └─► model ──► text? ─────────────────────────────► answer (saved)
 *               └─► tool calls ─► ToolCallingManager ─► @Tool methods (as this user)
 *                        └─► results appended, saved ─► model again   (at most maxToolRounds times)
 * </pre>
 * Spring AI could run this loop internally; owning it gives a hard round limit, a per-call trace for the API,
 * persisted tool traffic, a fresh state block every round, and graceful degradation when the provider fails
 * after tools have already run (the user still gets the cards and real results).
 * <p>
 * Not @Transactional: model calls are slow network I/O, and every tool runs its own service transactions.
 */
@Service
public class MovieBookingAgentService {

    private static final Logger log = LoggerFactory.getLogger(MovieBookingAgentService.class);
    static final String GAVE_UP = "Sorry, I couldn't finish that. Could you try a smaller step, or rephrase?";
    private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH);

    private final AgentLlm llm;
    private final AgentToolbox toolbox;
    private final ToolCallingManager toolCallingManager;
    private final ConfirmationGate confirmationGate;
    private final PersistentChatMemory memory;
    private final ConversationStore conversations;
    private final AgentProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final String systemPrompt;
    private final Set<String> toolNames;

    public MovieBookingAgentService(AgentLlm llm, AgentToolbox toolbox, ObjectProvider<ToolCallingManager> toolCalling,
                                    ConfirmationGate confirmationGate, PersistentChatMemory memory,
                                    ConversationStore conversations, AgentProperties properties, ObjectMapper json,
                                    Clock clock, @Value("classpath:agent/system-prompt.md") Resource systemPrompt)
            throws IOException {
        this.llm = llm;
        this.toolbox = toolbox;
        this.toolCallingManager = toolCalling.getIfAvailable(() -> ToolCallingManager.builder().build());
        this.confirmationGate = confirmationGate;
        this.memory = memory;
        this.conversations = conversations;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.systemPrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
        this.toolNames = Set.copyOf(toolbox.names());
    }

    /** @param declineConfirmationId the pending action the user declined with the card's "No" button, if any */
    public AgentResponse chat(AuthenticatedUser user, UUID conversationId, String text, String declineConfirmationId) {
        if (!llm.configured()) {
            throw new ApiException(ErrorCode.AI_NOT_CONFIGURED,
                    "Set AI_CHAT_PROVIDER=google-genai and GEMINI_API_KEY to enable the assistant");
        }
        Conversation conversation = conversationId == null
                ? conversations.create(user) : conversations.requireOwned(user, conversationId);
        UUID id = conversation.id();
        conversations.lease(user, id);
        try {
            return runTurn(user, conversation, text.trim(), declineConfirmationId);
        } finally {
            conversations.release(id);
        }
    }

    public ConversationResponse history(AuthenticatedUser user, UUID conversationId) {
        Conversation conversation = conversations.requireOwned(user, conversationId);
        return new ConversationResponse(conversation.id(), conversation.createdAt(),
                memory.visible(conversation.id()), pending(conversation.state()));
    }

    private AgentResponse runTurn(AuthenticatedUser user, Conversation conversation, String text,
                                  String declineConfirmationId) {
        String id = conversation.id().toString();
        ConversationState state = conversation.state();
        state.setTurn(state.getTurn() + 1);
        // Before the model reads the message: a "no" withdraws the pending hold/cancellation, so the model's
        // reading of the reply can never turn it into a confirmation.
        if (confirmationGate.withdrawIfDeclined(state, text, declineConfirmationId)) {
            log.info("Conversation {}: user declined the pending confirmation; withdrawn", id);
        }
        AgentTurn turn = new AgentTurn(user, state, clock.instant());

        UserMessage userMessage = new UserMessage(text);
        List<Message> messages = new ArrayList<>();
        messages.add(system(state));
        messages.addAll(memory.get(id));
        messages.add(userMessage);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(toolbox.callbacks())
                .internalToolExecutionEnabled(false)    // we run the loop (see class comment)
                .toolContext(turn.asToolContext())      // who the user is: set by us, never by the model
                .build();

        String answer = null;
        boolean userMessageSaved = false;
        for (int round = 0; round < properties.maxToolRounds() && answer == null; round++) {
            messages.set(0, system(state)); // tools change the state: the model always sees the current one
            Prompt prompt = new Prompt(messages, options);
            ChatResponse response;
            try {
                response = llm.client().prompt(prompt).call().chatResponse();
            } catch (RuntimeException e) {
                log.warn("LLM call failed for conversation {} (round {})", id, round, e);
                if (turn.trace().isEmpty()) {
                    // Nothing happened yet: fail cleanly, and keep no half-turn in memory.
                    throw new ApiException(ErrorCode.AI_UNAVAILABLE, "The assistant is temporarily unavailable. "
                            + "Please try again in a moment.");
                }
                answer = degraded(turn); // tools already ran (maybe a hold): report the real results anyway
                break;
            }
            if (!userMessageSaved) {
                memory.add(id, userMessage);
                userMessageSaved = true;
            }
            AssistantMessage reply = response == null || response.getResult() == null ? null
                    : response.getResult().getOutput();
            if (reply == null || !reply.hasToolCalls()) {
                answer = reply == null || reply.getText() == null || reply.getText().isBlank() ? GAVE_UP : reply.getText();
                break;
            }
            ToolResponseMessage results = execute(prompt, response, reply, turn);
            memory.add(id, List.of(reply, results));
            messages.add(reply);
            messages.add(results);
            log.debug("Conversation {} round {}: {}", id, round,
                    reply.getToolCalls().stream().map(AssistantMessage.ToolCall::name).collect(Collectors.joining(",")));
        }
        if (answer == null) {
            log.warn("Conversation {} hit the tool-round limit ({})", id, properties.maxToolRounds());
            answer = GAVE_UP;
        }
        if (!userMessageSaved) {
            memory.add(id, userMessage);
        }
        memory.add(id, AssistantMessage.builder().content(answer)
                .properties(turn.cards().isEmpty() ? Map.of() : Map.of(PersistentChatMemory.CARDS, turn.cards()))
                .build());
        conversations.saveState(conversation.id(), state);
        return new AgentResponse(conversation.id(), answer, toolCalls(turn), turn.cards(), pending(state));
    }

    /**
     * Runs the requested tools through Spring AI's ToolCallingManager. A model can hallucinate a tool name;
     * that is answered with an error result for the model (it then picks a real tool) instead of an exception.
     */
    private ToolResponseMessage execute(Prompt prompt, ChatResponse response, AssistantMessage reply, AgentTurn turn) {
        List<String> unknown = reply.getToolCalls().stream().map(AssistantMessage.ToolCall::name)
                .filter(n -> !toolNames.contains(n)).toList();
        if (unknown.isEmpty()) {
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            Message last = result.conversationHistory().getLast();
            if (last instanceof ToolResponseMessage toolResponses) {
                return toolResponses;
            }
            throw new IllegalStateException("Tool execution returned no tool responses");
        }
        log.warn("Model requested unknown tool(s) {}", unknown);
        return ToolResponseMessage.builder().responses(reply.getToolCalls().stream()
                .map(c -> new ToolResponseMessage.ToolResponse(c.id(), c.name(), write(toolNames.contains(c.name())
                        ? ToolReply.error("NOT_EXECUTED", "Not executed because another requested tool does not "
                                + "exist. Call it again on its own.")
                        : ToolReply.error("UNKNOWN_TOOL", "There is no tool called '" + c.name() + "'. Use only "
                                + "the tools provided."))))
                .toList()).build();
    }

    private SystemMessage system(ConversationState state) {
        var now = clock.instant().atZone(BusinessTime.ZONE);
        return new SystemMessage(systemPrompt
                .replace("{{NOW}}", now.format(NOW))
                .replace("{{TODAY}}", now.toLocalDate() + " (" + now.getDayOfWeek().getDisplayName(
                        java.time.format.TextStyle.FULL, Locale.ENGLISH) + ")")
                .replace("{{STATE}}", state.describeForModel()));
    }

    /** The model failed after tools ran: answer from the tool results themselves, never a guess. */
    private static String degraded(AgentTurn turn) {
        return "I'm having trouble reaching the AI service, but here is what I did: "
                + turn.trace().stream().map(t -> t.name() + " (" + t.status() + ")").collect(Collectors.joining(", "))
                + ". The details are shown below. Please try your next message again in a moment.";
    }

    private List<AgentResponse.ToolCallView> toolCalls(AgentTurn turn) {
        return turn.trace().stream().map(t -> new AgentResponse.ToolCallView(t.name(), parse(t.arguments()),
                t.status(), t.errorCode())).toList();
    }

    private static AgentResponse.PendingConfirmation pending(ConversationState state) {
        PendingAction p = state.getPending();
        if (p == null) {
            return null;
        }
        return new AgentResponse.PendingConfirmation(p.id(), p.type().name(), switch (p.type()) {
            case HOLD_SEATS -> "Hold seats " + String.join(", ", p.seatLabels());
            case CANCEL_BOOKING -> "Cancel booking " + p.bookingReference();
        });
    }

    private JsonNode parse(String arguments) {
        try {
            return arguments == null || arguments.isBlank() ? json.createObjectNode() : json.readTree(arguments);
        } catch (IOException e) {
            return json.getNodeFactory().textNode(arguments); // malformed model output: show it as-is
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
