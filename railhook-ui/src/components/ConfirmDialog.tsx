import { type ReactNode } from 'react';
import { Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from './ui/alert-dialog';

interface ConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: ReactNode;
  onConfirm: () => void;
  loading?: boolean;
  /** Defaults to the shared "delete" label, which is what most callers want. */
  confirmLabel?: string;
  loadingLabel?: string;
  /** Red confirm button. Off for a reversible action — disabling, not deleting. */
  destructive?: boolean;
  children?: ReactNode;
}

/**
 * A yes-or-no on an action the person can still take back, or one small enough not to warrant
 * typing its name. For anything irreversible, DangerConfirmDialog asks for the name instead.
 */
export default function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  onConfirm,
  loading = false,
  confirmLabel,
  loadingLabel,
  destructive = true,
  children,
}: ConfirmDialogProps) {
  const { t } = useTranslation();

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        {children}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={loading}>{t('common.cancel')}</AlertDialogCancel>
          <AlertDialogAction
            onClick={onConfirm}
            disabled={loading}
            className={destructive ? 'bg-halt text-primary-foreground hover:bg-halt/90' : undefined}
          >
            {loading && <Loader2 className="h-4 w-4 animate-spin" />}
            {loading ? (loadingLabel ?? t('common.deleting')) : (confirmLabel ?? t('common.delete'))}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
