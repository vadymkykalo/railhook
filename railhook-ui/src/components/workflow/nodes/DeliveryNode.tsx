import { memo } from 'react';
import { type NodeProps } from '@xyflow/react';
import { useTranslation } from 'react-i18next';
import BaseNode from './BaseNode';

function DeliveryNode({ data, selected }: NodeProps) {
  const { t } = useTranslation();
  const d = data as Record<string, unknown>;
  const endpointId = d.endpointId ? String(d.endpointId) : '';
  const shortId = endpointId.length > 12 ? endpointId.substring(0, 12) + '…' : endpointId;
  return (
    <BaseNode
      role="action"
      icon="📦"
      label={String(d.label || t('workflows.nodeTypes.delivery.label'))}
      subtitle={endpointId ? shortId : t('workflows.nodeStatus.notConfigured')}
      selected={selected}
    />
  );
}

export default memo(DeliveryNode);
