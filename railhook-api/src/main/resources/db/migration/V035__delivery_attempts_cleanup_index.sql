-- Composite index for DataRetentionService.cleanupOldSuccessfulAttempts()
-- Covers: WHERE created_at < :cutoffTime AND http_status_code >= 200 AND http_status_code < 300
CREATE INDEX IF NOT EXISTS idx_delivery_attempts_cleanup
    ON delivery_attempts(created_at, http_status_code);
