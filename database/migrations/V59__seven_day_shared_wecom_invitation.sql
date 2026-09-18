-- Extend only the shared QR entry. Existing expiry timestamps and the
-- 120-minute employee-specific credential constraint must stay unchanged.
DO $$
DECLARE
    ttl_constraint_name text;
BEGIN
    SELECT constraint_item.conname
      INTO STRICT ttl_constraint_name
      FROM pg_constraint constraint_item
     WHERE constraint_item.conrelid = 'wecom_open_onboarding_invitation'::regclass
       AND constraint_item.contype = 'c'
       AND pg_get_constraintdef(constraint_item.oid) LIKE '%expires_at <=%'
       AND pg_get_constraintdef(constraint_item.oid) LIKE '%issued_at%';

    EXECUTE format(
        'ALTER TABLE wecom_open_onboarding_invitation DROP CONSTRAINT %I',
        ttl_constraint_name
    );
END $$;

ALTER TABLE wecom_open_onboarding_invitation
    ADD CONSTRAINT ck_wecom_open_invitation_max_ttl
    CHECK (expires_at <= issued_at + interval '7 days 5 seconds');

COMMENT ON CONSTRAINT ck_wecom_open_invitation_max_ttl
    ON wecom_open_onboarding_invitation IS
    'Shared QR invitations last at most seven days; existing expired tokens are not renewed.';
