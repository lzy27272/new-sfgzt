-- A revoked recipient/current node is intentionally suppressed, not falsely marked sent or retried forever.
ALTER TABLE notification_delivery DROP CONSTRAINT notification_delivery_status_check;
ALTER TABLE notification_delivery ADD CONSTRAINT notification_delivery_status_check
 CHECK(status IN ('PENDING','SENT','DELIVERED','READ','FAILED','SUPPRESSED'));
ALTER TABLE notification_delivery ADD COLUMN suppressed_at TIMESTAMPTZ;
ALTER TABLE notification_delivery ADD CONSTRAINT notification_delivery_suppression_reason
 CHECK(status<>'SUPPRESSED' OR (suppressed_at IS NOT NULL AND last_error IS NOT NULL AND sent_at IS NULL));
