-- V8: the chat tables now back the Spring AI agent (Step 9).
--  * one ASSISTANT row can request SEVERAL tool calls, and one TOOL row answers all of them (Spring AI's
--    AssistantMessage / ToolResponseMessage), so per-row tool_name/tool_args/tool_result become JSON arrays;
--  * provider_metadata keeps provider data that must be replayed (e.g. Gemini thought signatures);
--  * busy_until is a short lease: one message per conversation is processed at a time.

-- Step 7 tool traffic used the old one-call-per-row shape and cannot be replayed to the new model: drop it.
-- The visible conversation (user text + assistant text) is kept.
DELETE FROM chatbot_messages WHERE role = 'TOOL' OR (role = 'ASSISTANT' AND content IS NULL);

ALTER TABLE chatbot_messages
    DROP CONSTRAINT ck_chatbot_messages_shape,
    DROP COLUMN tool_name,
    DROP COLUMN tool_args,
    DROP COLUMN tool_result,
    ADD COLUMN tool_calls        JSONB,   -- ASSISTANT: [{id, name, arguments}]
    ADD COLUMN tool_responses    JSONB,   -- TOOL:      [{id, name, response}]
    ADD COLUMN provider_metadata JSONB,
    ADD CONSTRAINT ck_chatbot_messages_shape CHECK (
        (role = 'USER' AND content IS NOT NULL AND tool_calls IS NULL AND tool_responses IS NULL)
        OR (role = 'ASSISTANT' AND (content IS NOT NULL OR tool_calls IS NOT NULL) AND tool_responses IS NULL)
        OR (role = 'TOOL' AND tool_responses IS NOT NULL AND content IS NULL AND tool_calls IS NULL));

ALTER TABLE chatbot_sessions ADD COLUMN busy_until TIMESTAMPTZ;
