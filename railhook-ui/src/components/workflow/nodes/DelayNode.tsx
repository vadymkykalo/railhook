import { memo } from 'react';
import { type NodeProps } from '@xyflow/react';
import { useTranslation } from 'react-i18next';
import BaseNode from './BaseNode';

function DelayNode({ data, selected }: NodeProps) {
  const { t } = useTranslation();
  const d = data as Record<string, unknown>;
  const seconds = d.delaySeconds ? Number(d.delaySeconds) : 5;
  return (
    <BaseNode
      role="logic"
      icon="⏱️"
      label={String(d.label || t('workflows.nodeTypes.delay.label'))}
      subtitle={t('workflows.nodeStatus.delaySeconds', { count: seconds })}
      selected={selected}
    />
  );
}

export default memo(DelayNode);
