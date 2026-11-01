-- AutoX V17 — a throttle scope for natural-language search, which spends a shared language-model
-- quota per request and so has a per-address limit of its own, far below the public read limit.

ALTER TABLE throttle_counter DROP CONSTRAINT ck_throttle_scope;
ALTER TABLE throttle_counter ADD CONSTRAINT ck_throttle_scope
    CHECK (scope IN ('ACCOUNT', 'IP', 'PUBLIC_READ', 'PASSWORD_RESET', 'PASSWORD_RESET_IP',
                     'REGISTRATION_IP', 'MESSAGE_SEND', 'CONVERSATION_START', 'SEARCH_INTERPRET'));
