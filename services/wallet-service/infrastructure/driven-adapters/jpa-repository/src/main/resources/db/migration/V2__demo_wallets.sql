-- Demo wallets: wallet creation is out of scope, so they are provisioned (spec.md §1.6).
-- user_ids match identity-service V2__demo_users.sql. Each initial balance is an OPENING entry.
INSERT INTO wallets (wallet_id, user_id, balance, currency, version, updated_at) VALUES
    ('a1c4e7f0-3b6d-4a9c-8e2f-5b8d1a4c7e01', '7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c', 500000.00, 'COP', 0, now()), -- ana
    ('b2d5f8a1-4c7e-4bad-9f3a-6c9e2b5d8f02', '2b9d4e6f-1a3c-4e5b-9d7f-0a1b2c3d4e5f', 100000.00, 'COP', 0, now()), -- beto
    ('c3e6a9b2-5d8f-4cbe-a04b-7daf3c6e9a03', '5e8a1c3d-7b9f-4d2e-8a6c-1f3b5d7e9a0c', 0.00, 'COP', 0, now());     -- auditor

-- The auditor starts at 0.00 and gets no OPENING entry: ledger amounts must be > 0.
INSERT INTO ledger_entries (entry_id, wallet_id, transfer_id, type, amount, balance_after, created_at) VALUES
    ('d4f7b0c3-6e9a-4dcf-b15c-8eb04d7fab01', 'a1c4e7f0-3b6d-4a9c-8e2f-5b8d1a4c7e01', NULL, 'OPENING', 500000.00, 500000.00, now()),
    ('e5a8c1d4-7fab-4ed0-826d-9fc15e8abc02', 'b2d5f8a1-4c7e-4bad-9f3a-6c9e2b5d8f02', NULL, 'OPENING', 100000.00, 100000.00, now());
