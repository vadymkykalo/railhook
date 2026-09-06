-- Restores the foreign key V052 dropped by accident.
--
-- V001 created delivery_attempts with
--     delivery_id UUID NOT NULL REFERENCES deliveries(id) ON DELETE CASCADE
-- V052 replaced that table with a partitioned parent, re-creating every column and every
-- index -- and no foreign key. Its own comment enumerates what it changed and does not
-- mention this, so the loss was not deliberate.
--
-- The old constraint survives only on delivery_attempts_legacy, the attached pre-cutover
-- partition. So rows written before the cutover still cascade and rows written after it do
-- not: DlqService.purgeAllDlq -> deleteDlqByProjectId deletes deliveries and leaves their
-- attempts behind, holding request and response bodies that nothing references any more.
-- Dropping a monthly partition eventually reclaims them, which is why nobody noticed, but
-- referential integrity was gone in the meantime and a purge did not mean what it said.
--
-- Two things this migration cannot do the gentle way, both verified against PostgreSQL 16:
--
--   * NOT VALID is rejected outright on a partitioned table
--     ("cannot add NOT VALID foreign key on partitioned table ... not yet supported"),
--     so the constraint is added validating: brief ACCESS EXCLUSIVE on the parent plus a
--     scan of every partition. On a large installation, do this in a maintenance window --
--     as V052 itself advises.
--   * ADD CONSTRAINT fails outright if any orphan exists, so they are deleted first. Every
--     row this removes is already unreachable: its delivery is gone, and delivery_id is the
--     only way in.

DELETE FROM delivery_attempts a
 WHERE NOT EXISTS (SELECT 1 FROM deliveries d WHERE d.id = a.delivery_id);

-- Attached to the parent, so every existing partition gets it and -- confirmed on PG 16 --
-- so does every partition PartitionMaintenanceService creates later. No change needed there.
ALTER TABLE delivery_attempts
    ADD CONSTRAINT fk_delivery_attempts_delivery
    FOREIGN KEY (delivery_id) REFERENCES deliveries(id) ON DELETE CASCADE;

COMMENT ON CONSTRAINT fk_delivery_attempts_delivery ON delivery_attempts IS
    'Restores the ON DELETE CASCADE that V001 declared and V052 dropped when it rebuilt this '
    'table as a partitioned parent. Without it, deleting a delivery leaves its attempt rows '
    'orphaned with their request and response bodies still stored.';
