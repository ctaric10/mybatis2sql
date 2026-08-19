-- Generated from: UserMapper.xml
-- foreach variants: one item, two items; empty output is omitted.

-- ============================================================
-- SELECT findUsers (variants: 18)
-- ============================================================

-- Variant 1
-- Branches: if(name != null)=false | choose when(status == 1) | if(ids != null and ids.size() > 0)=false
SELECT id, u.name, u.status FROM users u WHERE u.status = 1 ORDER BY '?';

-- Variant 2
-- Branches: if(name != null)=false | choose when(status == 1) | foreach(ids)=one | if(ids != null and ids.size() > 0)=true
SELECT id, u.name, u.status FROM users u WHERE u.status = 1 AND u.id IN ( '?' ) ORDER BY '?';

-- Variant 3
-- Branches: if(name != null)=false | choose when(status == 1) | foreach(ids)=two | if(ids != null and ids.size() > 0)=true
SELECT id, u.name, u.status FROM users u WHERE u.status = 1 AND u.id IN ( '?', '?' ) ORDER BY '?';

-- Variant 4
-- Branches: if(name != null)=false | choose when(status == 2) | if(ids != null and ids.size() > 0)=false
SELECT id, u.name, u.status FROM users u WHERE u.status = 2 ORDER BY '?';

-- Variant 5
-- Branches: if(name != null)=false | choose when(status == 2) | foreach(ids)=one | if(ids != null and ids.size() > 0)=true
SELECT id, u.name, u.status FROM users u WHERE u.status = 2 AND u.id IN ( '?' ) ORDER BY '?';

-- Variant 6
-- Branches: if(name != null)=false | choose when(status == 2) | foreach(ids)=two | if(ids != null and ids.size() > 0)=true
SELECT id, u.name, u.status FROM users u WHERE u.status = 2 AND u.id IN ( '?', '?' ) ORDER BY '?';

-- Variant 7
-- Branches: if(name != null)=false | choose otherwise | if(ids != null and ids.size() > 0)=false
SELECT id, u.name, u.status FROM users u WHERE u.status IS NOT NULL ORDER BY '?';

-- Variant 8
-- Branches: if(name != null)=false | choose otherwise | foreach(ids)=one | if(ids != null and ids.size() > 0)=true
SELECT id, u.name, u.status FROM users u WHERE u.status IS NOT NULL AND u.id IN ( '?' ) ORDER BY '?';

-- Variant 9
-- Branches: if(name != null)=false | choose otherwise | foreach(ids)=two | if(ids != null and ids.size() > 0)=true
SELECT id, u.name, u.status FROM users u WHERE u.status IS NOT NULL AND u.id IN ( '?', '?' ) ORDER BY '?';

-- Variant 10
-- Branches: if(name != null)=true | choose when(status == 1) | if(ids != null and ids.size() > 0)=false
SELECT id, u.name, u.status FROM users u WHERE u.name = '?' AND u.status = 1 ORDER BY '?';

-- Variant 11
-- Branches: if(name != null)=true | choose when(status == 1) | foreach(ids)=one | if(ids != null and ids.size() > 0)=true
SELECT id, u.name, u.status FROM users u WHERE u.name = '?' AND u.status = 1 AND u.id IN ( '?' ) ORDER BY '?';

-- Variant 12
-- Branches: if(name != null)=true | choose when(status == 1) | foreach(ids)=two | if(ids != null and ids.size() > 0)=true
SELECT id, u.name, u.status FROM users u WHERE u.name = '?' AND u.status = 1 AND u.id IN ( '?', '?' ) ORDER BY '?';

-- Variant 13
-- Branches: if(name != null)=true | choose when(status == 2) | if(ids != null and ids.size() > 0)=false
SELECT id, u.name, u.status FROM users u WHERE u.name = '?' AND u.status = 2 ORDER BY '?';

-- Variant 14
-- Branches: if(name != null)=true | choose when(status == 2) | foreach(ids)=one | if(ids != null and ids.size() > 0)=true
SELECT id, u.name, u.status FROM users u WHERE u.name = '?' AND u.status = 2 AND u.id IN ( '?' ) ORDER BY '?';

-- Variant 15
-- Branches: if(name != null)=true | choose when(status == 2) | foreach(ids)=two | if(ids != null and ids.size() > 0)=true
SELECT id, u.name, u.status FROM users u WHERE u.name = '?' AND u.status = 2 AND u.id IN ( '?', '?' ) ORDER BY '?';

-- Variant 16
-- Branches: if(name != null)=true | choose otherwise | if(ids != null and ids.size() > 0)=false
SELECT id, u.name, u.status FROM users u WHERE u.name = '?' AND u.status IS NOT NULL ORDER BY '?';

-- Variant 17
-- Branches: if(name != null)=true | choose otherwise | foreach(ids)=one | if(ids != null and ids.size() > 0)=true
SELECT id, u.name, u.status FROM users u WHERE u.name = '?' AND u.status IS NOT NULL AND u.id IN ( '?' ) ORDER BY '?';

-- Variant 18
-- Branches: if(name != null)=true | choose otherwise | foreach(ids)=two | if(ids != null and ids.size() > 0)=true
SELECT id, u.name, u.status FROM users u WHERE u.name = '?' AND u.status IS NOT NULL AND u.id IN ( '?', '?' ) ORDER BY '?';

-- ============================================================
-- UPDATE updateUser (variants: 4)
-- ============================================================

-- Variant 1
-- Branches: if(name != null)=false | if(status != null)=false
UPDATE users WHERE id = '?';

-- Variant 2
-- Branches: if(name != null)=false | if(status != null)=true
UPDATE users SET status = '?' WHERE id = '?';

-- Variant 3
-- Branches: if(name != null)=true | if(status != null)=false
UPDATE users SET name = '?' WHERE id = '?';

-- Variant 4
-- Branches: if(name != null)=true | if(status != null)=true
UPDATE users SET name = '?', status = '?' WHERE id = '?';

-- ============================================================
-- DELETE deleteUser (variants: 1)
-- ============================================================

-- Variant 1
DELETE FROM users WHERE id = '?';

