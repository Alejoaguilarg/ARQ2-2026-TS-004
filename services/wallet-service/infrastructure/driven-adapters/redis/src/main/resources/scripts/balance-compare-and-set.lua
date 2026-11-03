-- Compare-and-set of the balance read model (spec §2.1): writes balance:{userId} only when the key
-- has no version or the incoming version is greater than the stored one, so a late write never
-- overwrites a newer balance. Redis runs the script atomically. No TTL.
--
-- KEYS[1] = balance:{userId}
-- ARGV[1] = walletId, ARGV[2] = available, ARGV[3] = currency, ARGV[4] = version, ARGV[5] = asOf
-- Returns 1 when the hash was written and 0 when the stored version is newer or equal.

local stored = redis.call('HGET', KEYS[1], 'version')
if stored and tonumber(stored) >= tonumber(ARGV[4]) then
  return 0
end
redis.call('HSET', KEYS[1],
  'walletId', ARGV[1],
  'available', ARGV[2],
  'currency', ARGV[3],
  'version', ARGV[4],
  'asOf', ARGV[5])
return 1
