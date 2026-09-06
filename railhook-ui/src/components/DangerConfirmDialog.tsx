import { useState, useEffect, useId } from 'react';
import { AlertTriangle, Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { Label } from './ui/label';
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from './ui/alert-dialog';

interface DangerConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  /** What the person must type back before the action unlocks. */
  confirmName: string;
  /** What this will cost, stated before it happens. */
  impact?: string[];
  onConfirm: () => void | Promise<void>;
  loading?: boolean;
  confirmLabel?: string;
}

/**
 * The one ritual every irreversible action in the product goes through:
 * say what will be lost, make the person type the name back, and only then
 * unlock the button. Uniform on purpose — a delete that looks different from
 * the last delete is a delete somebody clicks through.
 */
export default function DangerConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmName,
  impact,
  onConfirm,
  loading = false,
  confirmLabel,
}: DangerConfirmDialogProps) {
  const { t } = useTranslation();
  const [inputValue, setInputValue] = useState('');
  const inputId = useId();

  useEffect(() => {
    if (!open) setInputValue('');
  }, [open]);

  const isMatch = inputValue === confirmName;

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle className="flex items-center gap-2 text-halt">
            <AlertTriangle className="h-4 w-4 flex-shrink-0" aria-hidden />
            {title}
          </AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>

        {impact && impact.length > 0 && (
          <div className="rounded-lg border border-halt/30 bg-halt-soft p-3">
            <p className="mono-label text-halt">{t('dangerConfirm.impact')}</p>
            <ul className="mt-1.5 space-y-1">
              {impact.map((line) => (
                <li key={line} className="flex gap-1.5 text-[13px] leading-snug text-muted-foreground">
                  <span aria-hidden className="text-halt">—</span>
                  <span>{line}</span>
                </li>
              ))}
            </ul>
          </div>
        )}

        <div className="space-y-2">
          <Label htmlFor={inputId} className="text-sm font-normal text-muted-foreground">
            {t('dangerConfirm.typeToConfirm', { name: confirmName })}
          </Label>
          <Input
            id={inputId}
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            placeholder={confirmName}
            className="font-mono text-sm"
            autoComplete="off"
            autoFocus
          />
        </div>

        <AlertDialogFooter>
          <AlertDialogCancel disabled={loading}>{t('common.cancel')}</AlertDialogCancel>
          <Button variant="destructive" onClick={onConfirm} disabled={!isMatch || loading}>
            {loading && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}
            {confirmLabel || t('common.delete')}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
