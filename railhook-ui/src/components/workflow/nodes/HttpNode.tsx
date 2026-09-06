import { memo } from 'react';
import { type NodeProps } from '@xyflow/react';
import { useTranslation } from 'react-i18next';
import BaseNode from './BaseNode';

function HttpNode({ data, selected }: NodeProps) {
  const { t } = useTranslation();
  const d = data as Record<string, unknown>;
  const method = String(d.method || 'POST');
  const url = d.url ? String(d.url) : '';
  const shortUrl = url.length > 30 ? url.substring(0, 30) + '…' : url;
  return (
    <BaseNode
      role="action"
      icon="🌐"
      label={String(d.label || t('workflows.nodeTypes.http.label'))}
      subtitle={url ? `${method} ${shortUrl}` : t('workflows.nodeStatus.notConfigured')}
      selected={selected}
    />
  );
}

export default memo(HttpNode);
