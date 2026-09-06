import { memo } from 'react';
import { type NodeProps } from '@xyflow/react';
import { useTranslation } from 'react-i18next';
import BaseNode from './BaseNode';

function TriggerNode({ data, selected }: NodeProps) {
  const { t } = useTranslation();
  const d = data as Record<string, unknown>;
  return (
    <BaseNode
      role="trigger"
      icon="⚡"
      label={String(d.label || t('workflows.nodeTypes.webhookTrigger.label'))}
      subtitle={d.eventTypePattern ? String(d.eventTypePattern) : t('workflows.nodeStatus.allEvents')}
      selected={selected}
      hasInput={false}
      hasOutput={true}
    />
  );
}

export default memo(TriggerNode);
