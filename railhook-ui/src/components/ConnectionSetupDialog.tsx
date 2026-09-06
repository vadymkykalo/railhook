import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ConnectionSetupFlow } from '../pages/ConnectionSetupPage';
import { queryKeys } from '../api/queries';
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,
} from './ui/dialog';

/**
 * The connection wizard, as an action rather than a destination.
 *
 * <p>Two places now open the same flow — the Connections tab and the dashboard's
 * getting-started card — so the dialog chrome lives here instead of being typed
 * out twice and drifting.
 *
 * <p>The `open &&` guard around the flow is load-bearing, not tidiness to be
 * refactored away: `ConnectionSetupFlow` keeps `stepIndex`, `endpointId` and the
 * generated secret in its own state, so unmounting it on close is what makes the
 * next opening start at step one instead of resuming someone else's half-built
 * connection.
 *
 * <p>Invalidating the onboarding query belongs here rather than inside the flow.
 * The flow is also a route (`/connection-setup`), which navigates away and has
 * no card to refresh; both dialog callers do. Without this the card would keep
 * claiming the step is undone for up to the query's 30-second staleTime, which
 * is exactly how the informational wizard used to feel.
 */
export default function ConnectionSetupDialog({
  projectId, open, onOpenChange, onCreated,
}: {
  projectId: string | undefined;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated?: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();

  const handleDone = () => {
    if (projectId) {
      queryClient.invalidateQueries({ queryKey: queryKeys.dashboard.onboarding(projectId) });
    }
    onCreated?.();
    onOpenChange(false);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>{t('connections.newConnection')}</DialogTitle>
          <DialogDescription>{t('connectionSetup.pageDesc')}</DialogDescription>
        </DialogHeader>
        {projectId && open && (
          <ConnectionSetupFlow
            projectId={projectId}
            onDone={handleDone}
            onCancel={() => onOpenChange(false)}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}
