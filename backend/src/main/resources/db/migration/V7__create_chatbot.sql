-- V7: chatbot conversations. The assistant is an interface to the backend: it stores conversation state and
-- ids (show / lock / booking) in context, never business decisions, card numbers or contact details.

CREATE TABLE chatbot_sessions (
    id             UUID        PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users (id),
    context        JSONB       NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL,
    last_active_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_chatbot_sessions_user ON chatbot_sessions (user_id, last_active_at DESC);

-- role USER: what the person typed · ASSISTANT: model text OR a model tool call (tool_name + tool_args)
-- TOOL: the backend's result of that call (what the model sees next). cards = UI payloads for the frontend.
CREATE TABLE chatbot_messages (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id  UUID        NOT NULL REFERENCES chatbot_sessions (id),
    role        VARCHAR(10) NOT NULL,
    content     TEXT,
    tool_name   VARCHAR(40),
    tool_args   JSONB,
    tool_result JSONB,
    cards       JSONB,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_chatbot_messages_role CHECK (role IN ('USER', 'ASSISTANT', 'TOOL')),
    CONSTRAINT ck_chatbot_messages_shape CHECK (content IS NOT NULL OR tool_name IS NOT NULL)
);
CREATE INDEX idx_chatbot_messages_session ON chatbot_messages (session_id, id);
