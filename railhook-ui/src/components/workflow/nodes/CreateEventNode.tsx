import { memo } from 'react';
import { type NodeProps } from '@xyflow/react';
import { useTranslation } from 'react-i18next';
import BaseNode from './BaseNode';

function CreateEventNode({ data, selected }: NodeProps) {
  const { t } = useTranslation();
  const d = data as Record<string, unknown>;
  const eventType = d.eventType ? String(d.eventType) : '';
  return (
    <BaseNode
      role="action"
      icon="📤"
      label={String(d.label || t('workflows.nodeTypes.createEvent.label'))}
      subtitle={eventType || t('workflows.nodeStatus.notConfigured')}
      selected={selected}
    />
  );
}

export default memo(CreateEventNode);
