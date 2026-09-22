-- Additive only: historical quantities, unit snapshots and journals are unchanged.
ALTER TABLE store_resource_item
    ADD COLUMN employee_usage_enabled boolean NOT NULL DEFAULT true;
COMMENT ON COLUMN store_resource_item.employee_usage_enabled IS
    'Whether this hotel item is selectable in housekeeping issuance and actual-use forms. Financial access is unchanged.';
