import { useState } from 'react';
import { Loader2, CreditCard, ShoppingCart, Github, Zap } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess } from '../lib/toast';
import { useCreateProject } from '../api/queries';
import { cn } from '../lib/utils';
import type { ProjectResponse } from '../types/api.types';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { Label } from './ui/label';
import { Textarea } from './ui/textarea';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from './ui/dialog';

type ProjectTemplate = 'custom' | 'stripe' | 'shopify' | 'github';

const TEMPLATES: { key: ProjectTemplate; icon: React.ElementType }[] = [
  { key: 'custom', icon: Zap },
  { key: 'stripe', icon: CreditCard },
  { key: 'shopify', icon: ShoppingCart },
  { key: 'github', icon: Github },
];

const TEMPLATE_DEFAULTS: Record<ProjectTemplate, { name: string; description: string }> = {
  custom: { name: '', description: '' },
  stripe: { name: 'Stripe Payments', description: 'Payment webhook integration — charge.succeeded, invoice.paid, refund.created' },
  shopify: { name: 'Shopify Store', description: 'E-commerce webhook integration — order.created, product.updated, checkout.completed' },
  github: { name: 'GitHub CI/CD', description: 'Repository webhook integration — push, pull_request.opened, workflow.completed' },
};

const capitalize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/**
 * Creating a project, wherever it is started from: the Projects page, a section's setup screen,
 * the dashboard's first step. One dialog, so the three cannot drift in what they ask; where to go
 * next is the caller's, because each of them was on its way somewhere different.
 */
export default function CreateProjectDialog({
  open, onOpenChange, onCreated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated: (project: ProjectResponse) => void;
}) {
  const { t } = useTranslation();
  const createProject = useCreateProject();
  const [selectedTemplate, setSelectedTemplate] = useState<ProjectTemplate>('custom');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const creating = createProject.isPending;

  const reset = () => { setSelectedTemplate('custom'); setName(''); setDescription(''); };

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault();
    createProject.mutate({ name, description }, {
      onSuccess: (project) => {
        onOpenChange(false);
        reset();
        showSuccess(t('projects.toast.created'));
        onCreated(project);
      },
      onError: (err: unknown) => showApiError(err, 'projects.toast.createFailed'),
    });
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        onOpenChange(next);
        if (!next) reset();
      }}
    >
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t('projects.createDialog.title')}</DialogTitle>
          <DialogDescription>{t('projects.createDialog.description')}</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleCreate}>
          <div className="space-y-5 py-4">
            <div className="space-y-2">
              <span className="text-sm font-medium leading-none">{t('projects.createDialog.templateLabel')}</span>
              <div role="radiogroup" aria-label={t('projects.createDialog.templateLabel')} className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                {TEMPLATES.map(({ key, icon: TIcon }) => (
                  <button
                    key={key}
                    type="button"
                    role="radio"
                    aria-checked={selectedTemplate === key}
                    onClick={() => {
                      setSelectedTemplate(key);
                      setName(TEMPLATE_DEFAULTS[key].name);
                      setDescription(TEMPLATE_DEFAULTS[key].description);
                    }}
                    className={cn(
                      'flex flex-col items-center gap-1.5 border p-3 text-center transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
                      selectedTemplate === key ? 'border-primary bg-secondary' : 'border-rail hover:border-primary/40'
                    )}
                  >
                    <TIcon className={cn('h-4 w-4', selectedTemplate === key ? 'text-primary' : 'text-muted-foreground')} aria-hidden />
                    <span className="text-[11px] font-medium leading-tight">
                      {t(`projects.createDialog.template${capitalize(key)}`)}
                    </span>
                  </button>
                ))}
              </div>
              <p className="text-xs text-muted-foreground">
                {t(`projects.createDialog.template${capitalize(selectedTemplate)}Desc`)}
              </p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="project-name">{t('projects.createDialog.name')}</Label>
              <Input
                id="project-name"
                placeholder={t('projects.createDialog.namePlaceholder')}
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
                disabled={creating}
                autoFocus
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="project-description">{t('projects.createDialog.descriptionLabel')}</Label>
              <Textarea
                id="project-description"
                placeholder={t('projects.createDialog.descriptionPlaceholder')}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                disabled={creating}
                rows={3}
              />
            </div>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={creating}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={creating}>
              {creating && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}
              {creating ? t('projects.createDialog.submitting') : t('projects.createDialog.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
