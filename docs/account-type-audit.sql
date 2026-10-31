-- Read-only preflight. Never execute console_2.sql as a migration.
SELECT u.id, array_agg(r.role ORDER BY r.role) FILTER (WHERE r.role IS NOT NULL) AS roles,
       EXISTS (SELECT 1 FROM businesses b WHERE b.owner_user_id = u.id) AS owns_business,
       EXISTS (SELECT 1 FROM bookings bk WHERE bk.customer_id = u.id) AS has_customer_history
FROM users u LEFT JOIN user_roles r ON r.user_id = u.id
GROUP BY u.id
HAVING count(r.role) <> 1
 OR (EXISTS (SELECT 1 FROM businesses b WHERE b.owner_user_id = u.id)
     AND NOT bool_or(r.role = 'BUSINESS'))
 OR (bool_or(r.role = 'BUSINESS') AND EXISTS (SELECT 1 FROM bookings bk WHERE bk.customer_id = u.id));
