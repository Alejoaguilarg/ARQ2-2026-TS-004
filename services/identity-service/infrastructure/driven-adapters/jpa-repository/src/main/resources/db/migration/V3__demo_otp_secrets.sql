-- Demo TOTP secrets (RFC 6238, base32), encrypted with AES-256-GCM under the development key
-- (finova.security.otp-key). The plain secrets are in the README so they can be added to an
-- authenticator app. Enrolment is out of scope (spec.md §1.6).
UPDATE users SET otp_secret = 'un8kCcEv6l5is6yZoRwvBNWmjUyLRVMjWbbQA70hHVRSlvRY1/m3cPvyCqebNzKGqR4OGmgvtErkWfoT' WHERE user_id = '7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c';
UPDATE users SET otp_secret = 'ck26Ce/ev/k5s928nmWK4yWgpc8b1ND2yY/kb9eBs7nRGs0q7zaTQn7R/AHNGN3Gf7gh/4jYc4T6itCN' WHERE user_id = '2b9d4e6f-1a3c-4e5b-9d7f-0a1b2c3d4e5f';
UPDATE users SET otp_secret = '/FygmgN6qIO4rBv/w1rKhF7kKHd0UUiRt7VURZ9I1vhWDDntIItKSMP+wuSOGZa+fq8eAgXx4K1V/C/n' WHERE user_id = '5e8a1c3d-7b9f-4d2e-8a6c-1f3b5d7e9a0c';
