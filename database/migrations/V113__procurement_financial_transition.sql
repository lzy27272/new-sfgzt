-- Explicit physical-procurement transition, independent of reimbursement enablement.
-- Existing stores/orders stay compatible; no financial classification is invented.
ALTER TABLE procurement_config ADD COLUMN financial_dimensions_required boolean NOT NULL DEFAULT false;
ALTER TABLE procurement_order ADD COLUMN financial_dimensions_required boolean NOT NULL DEFAULT false;

CREATE FUNCTION procurement_preserve_financial_requirement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.financial_dimensions_required AND NOT NEW.financial_dimensions_required
    OR OLD.status NOT IN ('DRAFT','RETURNED') AND OLD.financial_dimensions_required IS DISTINCT FROM NEW.financial_dimensions_required THEN
  RAISE EXCEPTION 'Submitted procurement financial requirement is frozen; do not weaken it through configuration';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER procurement_financial_requirement_frozen BEFORE UPDATE ON procurement_order
 FOR EACH ROW EXECUTE FUNCTION procurement_preserve_financial_requirement();
