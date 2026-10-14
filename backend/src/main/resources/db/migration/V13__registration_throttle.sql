-- AutoX V13 — a throttle scope for account registration.
--
-- Registration was the one unauthenticated write with no cap. Each call hashes a password
-- (BCrypt, cost 12, about a quarter of a second of CPU), so an uncapped endpoint is both a
-- way to mass-produce accounts and a way to pin a core. It gets the same per-address
-- counter the other public endpoints use.
--
-- REGISTRATION_IP: registration attempts from one client address, successful or not.
-- Named for what it protects, like PASSWORD_RESET_IP, and kept apart from IP so that
-- failing to guess a password and creating accounts are not counted into the same block.

ALTER TABLE throttle_counter DROP CONSTRAINT ck_throttle_scope;
ALTER TABLE throttle_counter ADD CONSTRAINT ck_throttle_scope
    CHECK (scope IN ('ACCOUNT', 'IP', 'PUBLIC_READ', 'PASSWORD_RESET', 'PASSWORD_RESET_IP',
                     'REGISTRATION_IP'));
