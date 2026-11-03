-- Demo users: registration and enrolment are out of scope (spec.md §1.6).
-- Password of all three: Finova2026!  PINs: ana 4821, beto 1357, auditor 2468.
-- Wallet must use these same user_ids for their wallets.
INSERT INTO users (user_id, email, phone, status, roles, password_hash, pin_hash) VALUES
    ('7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c', 'ana@example.com', '+573001112233', 'ACTIVE', '{USER}',
     '$argon2id$v=19$m=16384,t=2,p=1$T3+K0Upf5KAOmYUHKQh/sw$EVZI61agdvqnE8uGvuYFYdDRbJok+wiQVeBaHpD7baE',
     '$argon2id$v=19$m=16384,t=2,p=1$TZ8c3WGk1QtP3ulo3KuJag$vmY1ZLtnzJtOsNe8qrfdDGUcGXH+JJGJ5bsymfefosU'),
    ('2b9d4e6f-1a3c-4e5b-9d7f-0a1b2c3d4e5f', 'beto@example.com', '+573004445566', 'ACTIVE', '{USER}',
     '$argon2id$v=19$m=16384,t=2,p=1$hIgNufx8xAQLzAB9nkJuzw$MxkDbwJZ0+3Q9qMdgEqDBAYysQKm3TZRvHaWWLfuRwc',
     '$argon2id$v=19$m=16384,t=2,p=1$Jx3JWIvUht1hPhR0OfmXWg$rrFpRlgWtbgW4pnZJlHf22KlEHII2xxBFbn5y3Nku7Q'),
    ('5e8a1c3d-7b9f-4d2e-8a6c-1f3b5d7e9a0c', 'auditor@example.com', NULL, 'ACTIVE', '{USER,AUDITOR}',
     '$argon2id$v=19$m=16384,t=2,p=1$6nZfX6RMYvQhrfvSAcu31Q$Lcv6uGHdYgrsCbcn0njDDlR2CxEKAI3IEpwPvKEucjs',
     '$argon2id$v=19$m=16384,t=2,p=1$1Z8mVEtLBLnskqnc5WgpTg$r1k/skKpYZPY7RmbXbnRcx1rxdz5sWAXVoSoDFYqY7w');
