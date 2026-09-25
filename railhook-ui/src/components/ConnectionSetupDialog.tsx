import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ConnectionSetupFlow } from '../pages/ConnectionSetupPage';
import { queryKeys } from '../api/queries';
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,
} from './ui/dialog';

/** The `open &&` guard unmounts the flow on close, so the next opening starts at step one. */
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
