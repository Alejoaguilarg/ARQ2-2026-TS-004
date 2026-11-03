-- Initial data set: contact data of the provisioned users (spec §1.6).
--
-- Ana, Beto and the auditor are the demo users of Identity (identity-service V2__demo_users.sql):
-- same user_id, email and phone, because this table is a copy of the contact data owned by Identity.
-- Wallet uses the same user_id values for their wallets.
-- The last three users only exist to demonstrate the SMS and PUSH channels and a provider failure
-- with the messages in samples/.
--
-- This is a Flyway *repeatable* migration (R__): when this file changes, Flyway runs it again on
-- the next start. ON CONFLICT updates existing rows, so it can be re-run safely.
-- The emails use reserved domains (example.com, .test): nothing is delivered to them. For a demo with
-- real email, update the rows in your local database instead of committing real addresses
-- (the repository is public).

INSERT INTO recipient_contacts (user_id, email, phone, push_token, preferred_channel) VALUES
    ('7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c', 'ana@example.com',          '+573001112233', NULL,                  'EMAIL'),
    ('2b9d4e6f-1a3c-4e5b-9d7f-0a1b2c3d4e5f', 'beto@example.com',         '+573004445566', NULL,                  'EMAIL'),
    ('5e8a1c3d-7b9f-4d2e-8a6c-1f3b5d7e9a0c', 'auditor@example.com',      NULL,            NULL,                  'EMAIL'),
    -- Demo only: SMS and PUSH channels
    ('6a1f3c2e-8b4d-4e5f-9a7b-1c2d3e4f5a60', 'usuario.sms@finova.test',  '+573000000003', NULL,                  'SMS'),
    ('9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a', 'usuario.push@finova.test', '+573000000004', 'push-token-usuario-d', 'PUSH'),
    -- Demo only: the provider mock answers 503 for this phone number (retries and DLQ)
    ('0f1e2d3c-4b5a-4968-8776-5a4b3c2d1e0f', 'falla@finova.test',        '+573009999999', NULL,                  'SMS')
ON CONFLICT (user_id) DO UPDATE SET
    email             = EXCLUDED.email,
    phone             = EXCLUDED.phone,
    push_token        = EXCLUDED.push_token,
    preferred_channel = EXCLUDED.preferred_channel;
