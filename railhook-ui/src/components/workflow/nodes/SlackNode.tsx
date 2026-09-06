import { memo } from 'react';
import { type NodeProps } from '@xyflow/react';
import { useTranslation } from 'react-i18next';
import BaseNode from './BaseNode';

function SlackNode({ data, selected }: NodeProps) {
  const { t } = useTranslation();
  const d = data as Record<string, unknown>;
  const channel = d.channel ? String(d.channel) : '';
  const hasUrl = d.webhookUrl && String(d.webhookUrl).length > 0;
  return (
    <BaseNode
      role="action"
      icon="💬"
      label={String(d.label || t('workflows.nodeTypes.slack.label'))}
      subtitle={channel || (hasUrl ? t('workflows.nodeStatus.webhookConfigured') : t('workflows.nodeStatus.notConfigured'))}
      selected={selected}
    />
  );
}

export default memo(SlackNode);
