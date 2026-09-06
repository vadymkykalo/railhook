-- Composite index for fast EXISTS checks on deliveries via event.project_id JOIN
-- Used by onboarding status: deliveryRepository.existsByProjectId()
CREATE INDEX IF NOT EXISTS idx_events_project_id_id ON events(project_id, id);

-- Covering index for subscription EXISTS check by project_id
-- Used by onboarding status: subscriptionRepository.existsByProjectId()
CREATE INDEX IF NOT EXISTS idx_subscriptions_project_exists ON subscriptions(project_id) WHERE project_id IS NOT NULL;

-- Covering index for api_keys EXISTS check (non-revoked only)
-- Used by onboarding status: apiKeyRepository.existsByProjectIdAndRevokedAtIsNull()
CREATE INDEX IF NOT EXISTS idx_api_keys_project_active ON api_keys(project_id) WHERE revoked_at IS NULL;

-- Composite index for incoming_destinations EXISTS via incoming_source JOIN
-- Used by onboarding status: incomingDestinationRepository.existsByProjectId()
CREATE INDEX IF NOT EXISTS idx_incoming_destinations_source_id ON incoming_destinations(incoming_source_id);
