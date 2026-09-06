import { memo } from 'react';
import { type NodeProps } from '@xyflow/react';
import { useTranslation } from 'react-i18next';
import BaseNode from './BaseNode';

function TransformNode({ data, selected }: NodeProps) {
  const { t } = useTranslation();
  const d = data as Record<string, unknown>;
  const hasTemplate = d.template && String(d.template).trim() !== '{}';
  return (
    <BaseNode
      role="logic"
      icon="🔄"
      label={String(d.label || t('workflows.nodeTypes.transform.label'))}
      subtitle={hasTemplate ? t('workflows.nodeStatus.templateConfigured') : t('workflows.nodeStatus.noTemplate')}
      selected={selected}
    />
  );
}

export default memo(TransformNode);
