-- Self-service only starts OAuth. Accounts and assignments still require HR approval.
ALTER TABLE wecom_person_onboarding
    DROP CONSTRAINT wecom_person_onboarding_invitation_source_check;
ALTER TABLE wecom_person_onboarding
    ADD CONSTRAINT wecom_person_onboarding_invitation_source_check
    CHECK (invitation_source IN ('DIRECTORY_EVENT', 'MANUAL_LINK', 'SELF_SERVICE'));
