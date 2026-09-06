import { useState, useCallback, useRef, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  ReactFlow,
  Controls,
  MiniMap,
  Background,
  BackgroundVariant,
  addEdge,
  useNodesState,
  useEdgesState,
  type Connection,
  type Edge,
  type Node,
  type OnConnect,
  ReactFlowProvider,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { ArrowLeft, Save, ToggleLeft, ToggleRight, Loader2, Play, History, CheckCircle2, XCircle, Clock, ChevronDown, ChevronUp, BarChart3, Activity } from 'lucide-react';
import type { WorkflowExecutionResponse } from '../api/workflows.api';
import { workflowsApi } from '../api/workflows.api';
import { Button } from '../components/ui/button';
import PageSkeleton from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import { showApiError, showSuccess } from '../lib/toast';
import { formatDateTime } from '../lib/date';
import { nodeTypes, nodeTemplates, type NodeTemplate } from '../components/workflow/nodes/nodeTypes';
import NodeConfigPanel from '../components/workflow/NodeConfigPanel';
import StatusBadge, { type StatusKind } from '../components/StatusBadge';
import JsonBlock from '../components/JsonBlock';

let nodeIdCounter = 0;
function getNextNodeId() {
  return `node_${Date.now()}_${nodeIdCounter++}`;
}

function WorkflowBuilderInner() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const qc = useQueryClient();
  const reactFlowWrapper = useRef<HTMLDivElement>(null);

  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [selectedNode, setSelectedNode] = useState<Node | null>(null);
  const [hasUnsaved, setHasUnsaved] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [triggerPayload, setTriggerPayload] = useState('{"type":"test.event","data":{}}');
  const [showTriggerDialog, setShowTriggerDialog] = useState(false);

  const {
    data: workflow, isLoading, isError, error, refetch, isRefetching,
  } = useQuery({
    queryKey: ['workflow', projectId, workflowId],
    queryFn: () => workflowsApi.get(projectId!, workflowId!),
    enabled: !!projectId && !!workflowId,
  });

  // Load definition into canvas
  useEffect(() => {
    if (workflow?.definition) {
      const def = workflow.definition;
      if (def.nodes && Array.isArray(def.nodes)) {
        setNodes(def.nodes as Node[]);
      }
      if (def.edges && Array.isArray(def.edges)) {
        setEdges(def.edges as Edge[]);
      }
      setHasUnsaved(false);
    }
  }, [workflow, setNodes, setEdges]);

  const onConnect: OnConnect = useCallback(
    (params: Connection) => {
      setEdges((eds) => addEdge(params, eds));
      setHasUnsaved(true);
    },
    [setEdges],
  );

  const onNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    setSelectedNode(node);
  }, []);

  const onPaneClick = useCallback(() => {
    setSelectedNode(null);
  }, []);

  const handleNodesChange: typeof onNodesChange = useCallback(
    (changes) => {
      onNodesChange(changes);
      setHasUnsaved(true);
    },
    [onNodesChange],
  );

  const handleEdgesChange: typeof onEdgesChange = useCallback(
    (changes) => {
      onEdgesChange(changes);
      setHasUnsaved(true);
    },
    [onEdgesChange],
  );

  // Update node data from config panel
  const handleNodeDataUpdate = useCallback(
    (nodeId: string, newData: Record<string, unknown>) => {
      setNodes((nds) =>
        nds.map((n): Node => (n.id === nodeId ? { ...n, data: newData } : n)),
      );
      setSelectedNode((prev) => (prev && prev.id === nodeId ? { ...prev, data: newData } as Node : prev));
      setHasUnsaved(true);
    },
    [setNodes],
  );

  // Drag & drop from sidebar
  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();
      const templateJson = event.dataTransfer.getData('application/workflow-node');
      if (!templateJson) return;

      const template: NodeTemplate = JSON.parse(templateJson);
      const wrapperBounds = reactFlowWrapper.current?.getBoundingClientRect();
      if (!wrapperBounds) return;

      const position = {
        x: event.clientX - wrapperBounds.left - 90,
        y: event.clientY - wrapperBounds.top - 20,
      };

      const newNode: Node = {
        id: getNextNodeId(),
        type: template.type,
        position,
        data: { ...template.defaultData },
      };

      setNodes((nds: Node[]) => [...nds, newNode]);
      setHasUnsaved(true);
    },
    [setNodes],
  );

  // Delete selected node
  const deleteSelectedNode = useCallback(() => {
    if (!selectedNode) return;
    setNodes((nds: Node[]) => nds.filter((n) => n.id !== selectedNode.id));
    setEdges((eds: Edge[]) => eds.filter((e) => e.source !== selectedNode.id && e.target !== selectedNode.id));
    setSelectedNode(null);
    setHasUnsaved(true);
  }, [selectedNode, setNodes, setEdges]);

  // Save
  const saveMutation = useMutation({
    mutationFn: () =>
      workflowsApi.update(projectId!, workflowId!, {
        name: workflow!.name,
        description: workflow!.description || undefined,
        definition: { nodes: nodes as unknown as import('../api/workflows.api').WorkflowNode[], edges: edges as unknown as import('../api/workflows.api').WorkflowEdge[] },
        triggerType: workflow!.triggerType,
        triggerConfig: extractTriggerConfig(),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflow', projectId, workflowId] });
      qc.invalidateQueries({ queryKey: ['workflows', projectId] });
      setHasUnsaved(false);
      showSuccess(t('workflows.toast.saved'));
    },
    onError: (err) => showApiError(err, t('workflows.toast.saveFailed')),
  });

  const toggleMutation = useMutation({
    mutationFn: (enabled: boolean) => workflowsApi.toggle(projectId!, workflowId!, enabled),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflow', projectId, workflowId] });
      qc.invalidateQueries({ queryKey: ['workflows', projectId] });
    },
    onError: (err) => showApiError(err, t('workflows.toast.toggleFailed2')),
  });

  const triggerMutation = useMutation({
    mutationFn: (payload: Record<string, unknown>) => workflowsApi.trigger(projectId!, workflowId!, payload),
    onSuccess: () => {
      showSuccess(t('workflows.toast.triggered'));
      setShowTriggerDialog(false);
      qc.invalidateQueries({ queryKey: ['workflow-executions', projectId, workflowId] });
      setShowHistory(true);
    },
    onError: (err) => showApiError(err, t('workflows.toast.triggerFailed')),
  });

  const { data: executions } = useQuery({
    queryKey: ['workflow-executions', projectId, workflowId],
    queryFn: () => workflowsApi.listExecutions(projectId!, workflowId!, 0, 10),
    enabled: !!projectId && !!workflowId && showHistory,
    refetchInterval: showHistory ? 5000 : false,
  });

  // Extract trigger config from the trigger node
  const extractTriggerConfig = useCallback((): Record<string, unknown> => {
    const triggerNode = nodes.find((n) => n.type === 'webhookTrigger');
    if (triggerNode?.data) {
      const d = triggerNode.data as Record<string, unknown>;
      return { eventTypePattern: d.eventTypePattern || '*' };
    }
    return {};
  }, [nodes]);

  // Keyboard shortcuts
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 's') {
        e.preventDefault();
        if (hasUnsaved && workflow) saveMutation.mutate();
      }
      if (e.key === 'Delete' || e.key === 'Backspace') {
        if (selectedNode && document.activeElement === document.body) {
          deleteSelectedNode();
        }
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [hasUnsaved, workflow, saveMutation, selectedNode, deleteSelectedNode]);

  if (isLoading) {
    return (
      <PageSkeleton maxWidth="max-w-none">
        <div className="h-[70vh] animate-pulse rounded-lg border border-rail bg-muted" />
      </PageSkeleton>
    );
  }

  // A fetch that failed is not a workflow that was deleted, and the canvas
  // below writes back to whatever this returned.
  if (isError) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState error={error} onRetry={() => refetch()} retrying={isRefetching} />
      </div>
    );
  }

  if (!workflow) {
    return (
      <div className="p-4 lg:p-6">
        <EmptyState icon={Activity} title={t('workflows.builder.notFound')} />
      </div>
    );
  }

  return (
    <div className="flex flex-col h-[calc(100vh-64px)]">
      {/* Toolbar */}
      <div className="flex items-center justify-between gap-3 border-b border-rail bg-card px-4 py-2">
        <div className="flex items-center gap-3 min-w-0">
          <Button variant="ghost" size="icon-sm" onClick={() => navigate(`/admin/projects/${projectId}/workflows`)} title={t('workflows.builder.back')} aria-label={t('workflows.builder.back')}>
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div className="min-w-0">
            <h2 className="truncate text-sm font-medium">{workflow.name}</h2>
            <p className="font-mono text-[10px] text-muted-foreground">
              {[
                t('workflows.version', { version: workflow.version }),
                t('workflows.builder.nodesCount', { count: nodes.length }),
                t('workflows.builder.edgesCount', { count: edges.length }),
              ].join(' · ')}
              {hasUnsaved && <span className="ml-1 text-retry">{`● ${t('workflows.builder.unsaved')}`}</span>}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="ghost"
            size="sm"
            className="gap-1.5 text-xs"
            onClick={() => setShowHistory(!showHistory)}
          >
            <History className="h-4 w-4" />
            {t('workflows.builder.history')}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="gap-1.5 text-xs"
            onClick={() => setShowTriggerDialog(true)}
          >
            <Play className="h-4 w-4" />
            {t('workflows.builder.testRun')}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="gap-1.5 text-xs"
            onClick={() => toggleMutation.mutate(!workflow.enabled)}
          >
            {workflow.enabled ? <ToggleRight className="h-4 w-4 text-ok" aria-hidden /> : <ToggleLeft className="h-4 w-4" aria-hidden />}
            {workflow.enabled ? t('workflows.builder.enabled') : t('workflows.builder.disabled')}
          </Button>
          <Button
            size="sm"
            className="gap-1.5"
            onClick={() => saveMutation.mutate()}
            disabled={!hasUnsaved || saveMutation.isPending}
          >
            {saveMutation.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Save className="h-3.5 w-3.5" />}
            {t('workflows.builder.save')}
          </Button>
        </div>
      </div>

      {/* Main area */}
      <div className="flex flex-1 overflow-hidden">
        {/* Node palette sidebar */}
        <div className="w-48 flex-shrink-0 space-y-2 overflow-y-auto border-r border-rail bg-card p-3">
          <p className="mono-label px-1">
            {t('workflows.builder.dragToAdd')}
          </p>
          {nodeTemplates.map((template) => (
            <div
              key={template.type}
              draggable
              onDragStart={(e) => {
                e.dataTransfer.setData('application/workflow-node', JSON.stringify(template));
                e.dataTransfer.effectAllowed = 'move';
              }}
              className="flex cursor-grab items-center gap-2 rounded-md border border-rail bg-card px-2.5 py-2 transition-colors hover:border-primary/40 hover:bg-secondary/50 active:cursor-grabbing"
            >
              <span className="text-sm">{template.icon}</span>
              <div className="min-w-0">
                <div className="text-xs font-medium truncate">{t(`workflows.nodeTypes.${template.type}.label`)}</div>
                <div className="truncate text-[11px] text-muted-foreground">{t(`workflows.nodeTypes.${template.type}.description`)}</div>
              </div>
            </div>
          ))}
        </div>

        {/* Canvas */}
        <div className="flex-1" ref={reactFlowWrapper}>
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={handleNodesChange}
            onEdgesChange={handleEdgesChange}
            onConnect={onConnect}
            onNodeClick={onNodeClick}
            onPaneClick={onPaneClick}
            onDragOver={onDragOver}
            onDrop={onDrop}
            nodeTypes={nodeTypes}
            fitView
            fitViewOptions={{ padding: 0.3 }}
            deleteKeyCode={null}
            defaultEdgeOptions={{ animated: true, style: { stroke: 'hsl(var(--rail))', strokeWidth: 2 } }}
            proOptions={{ hideAttribution: true }}
          >
            <Controls position="bottom-left" />
            <MiniMap
              position="bottom-right"
              className="!rounded-lg !border !border-rail !bg-card"
              maskColor="hsl(var(--muted) / 0.6)"
              nodeColor="hsl(var(--muted-foreground))"
              nodeStrokeColor="hsl(var(--rail))"
              nodeStrokeWidth={3}
            />
            <Background
              variant={BackgroundVariant.Dots}
              gap={20}
              size={1}
              color="hsl(var(--rail))"
              className="!bg-background"
            />
          </ReactFlow>
        </div>

        {/* Config panel */}
        {selectedNode && (
          <NodeConfigPanel
            node={selectedNode}
            onUpdate={handleNodeDataUpdate}
            onClose={() => setSelectedNode(null)}
          />
        )}
      </div>

      {/* Execution history drawer */}
      {showHistory && (
        <div className="max-h-80 overflow-y-auto border-t border-rail bg-card">
          <div className="sticky top-0 z-10 flex items-center justify-between border-b border-rail bg-card px-4 py-2">
            <h3 className="mono-label">{t('workflows.builder.executionHistory')}</h3>
            <Button variant="ghost" size="icon-sm" onClick={() => setShowHistory(false)} title={t('workflows.builder.closeHistory')} aria-label={t('workflows.builder.closeHistory')}>
              <ChevronDown className="h-4 w-4" />
            </Button>
          </div>

          {/* Stats summary */}
          {(() => {
            const total = workflow.totalExecutions ?? 0;
            const success = workflow.successfulExecutions ?? 0;
            const failed = workflow.failedExecutions ?? 0;
            const rate = total > 0 ? Math.round((success / total) * 100) : 0;
            const execs = executions?.content ?? [];
            const avgMs = execs.length > 0 ? Math.round(execs.reduce((s, e) => s + (e.durationMs ?? 0), 0) / execs.length) : 0;
            return (
              <div className="grid grid-cols-5 gap-3 border-b border-rail bg-secondary/40 px-4 py-2.5">
                <div className="flex items-center gap-1.5">
                  <Activity className="h-3 w-3 text-muted-foreground" aria-hidden />
                  <div>
                    <div className="mono-label">{t('workflows.builder.statsTotal')}</div>
                    <div className="font-mono text-xs font-medium">{total}</div>
                  </div>
                </div>
                <div className="flex items-center gap-1.5">
                  <CheckCircle2 className="h-3 w-3 text-ok" aria-hidden />
                  <div>
                    <div className="mono-label">{t('workflows.builder.statsSuccess')}</div>
                    <div className="font-mono text-xs font-medium text-ok">{success}</div>
                  </div>
                </div>
                <div className="flex items-center gap-1.5">
                  <XCircle className="h-3 w-3 text-halt" aria-hidden />
                  <div>
                    <div className="mono-label">{t('workflows.builder.statsFailed')}</div>
                    <div className="font-mono text-xs font-medium text-halt">{failed}</div>
                  </div>
                </div>
                <div className="flex items-center gap-1.5">
                  <BarChart3 className="h-3 w-3 text-primary" aria-hidden />
                  <div>
                    <div className="mono-label">{t('workflows.builder.statsRate')}</div>
                    <div className="font-mono text-xs font-medium">{rate}%</div>
                  </div>
                </div>
                <div className="flex items-center gap-1.5">
                  <Clock className="h-3 w-3 text-muted-foreground" aria-hidden />
                  <div>
                    <div className="mono-label">{t('workflows.builder.statsAvg')}</div>
                    <div className="font-mono text-xs font-medium">{avgMs}ms</div>
                  </div>
                </div>
              </div>
            );
          })()}

          {!executions?.content?.length ? (
            <EmptyState
              icon={History}
              title={t('workflows.builder.noExecutions')}
              className="flex flex-col items-center justify-center p-6"
            />
          ) : (
            <div className="divide-y divide-rail">
              {executions.content.map((exec: WorkflowExecutionResponse) => (
                <ExecutionRow key={exec.id} exec={exec} />
              ))}
            </div>
          )}
        </div>
      )}

      {/* Manual trigger dialog */}
      {showTriggerDialog && (
        <div className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center" onClick={() => setShowTriggerDialog(false)}>
          <div className="w-[480px] max-w-[90vw] space-y-4 rounded-lg border border-rail bg-card p-5 shadow-elevated" onClick={(e) => e.stopPropagation()}>
            <h3 className="text-[15px] font-medium">{t('workflows.builder.testRun')}</h3>
            <p className="text-xs text-muted-foreground">{t('workflows.builder.testRunHint')}</p>
            <textarea
              value={triggerPayload}
              onChange={(e) => setTriggerPayload(e.target.value)}
              rows={8}
              className="w-full resize-y font-mono text-xs"
            />
            <div className="flex gap-2 justify-end">
              <Button variant="ghost" size="sm" onClick={() => setShowTriggerDialog(false)}>
                {t('workflows.cancel')}
              </Button>
              <Button
                size="sm"
                className="gap-1.5"
                disabled={triggerMutation.isPending}
                onClick={() => {
                  try {
                    const payload = JSON.parse(triggerPayload);
                    triggerMutation.mutate(payload);
                  } catch {
                    showApiError(new Error(t('workflows.builder.invalidJson')), t('workflows.toast.triggerFailed'));
                  }
                }}
              >
                {triggerMutation.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Play className="h-3.5 w-3.5" />}
                {t('workflows.builder.runNow')}
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function ExecutionRow({ exec }: { exec: WorkflowExecutionResponse }) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const [expandedStep, setExpandedStep] = useState<string | null>(null);

  // An execution and its steps are domain statuses, so they resolve to the four
  // reserved hues like every other status in the product: done, still owed,
  // abandoned, nothing tried.
  const statusIcon = exec.status === 'COMPLETED' ? <CheckCircle2 className="h-3.5 w-3.5 text-ok" aria-hidden />
    : exec.status === 'FAILED' ? <XCircle className="h-3.5 w-3.5 text-halt" aria-hidden />
    : exec.status === 'RUNNING' ? <Loader2 className="h-3.5 w-3.5 animate-spin text-retry" aria-hidden />
    : <Clock className="h-3.5 w-3.5 text-muted-foreground" aria-hidden />;

  const stepStatusCls: Record<string, string> = {
    SUCCESS: 'bg-ok',
    FAILED: 'bg-halt',
    SKIPPED: 'bg-idle',
    RUNNING: 'bg-retry animate-pulse',
    PENDING: 'bg-idle',
  };

  const steps = exec.steps;

  return (
    <div>
      <div
        className="flex cursor-pointer items-center gap-3 px-4 py-2 text-xs transition-colors hover:bg-secondary/50"
        onClick={() => setExpanded(!expanded)}
      >
        {statusIcon}
        <span className="font-medium">{t(`workflows.execStatus.${exec.status}`)}</span>
        <span className="font-mono text-muted-foreground">{exec.startedAt ? formatDateTime(exec.startedAt) : ''}</span>
        {exec.durationMs != null && <span className="font-mono text-muted-foreground">{exec.durationMs}ms</span>}
        {exec.errorMessage && <span className="flex-1 truncate text-halt">{exec.errorMessage}</span>}
        {expanded ? <ChevronUp className="h-3 w-3 ml-auto" /> : <ChevronDown className="h-3 w-3 ml-auto" />}
      </div>
      {expanded && (
        <div className="px-4 pb-3 space-y-1">
          {steps && steps.length > 0 ? (
            <div className="space-y-0.5">
              {steps.map((step, i) => (
                <div key={step.id} className="rounded-md border border-rail bg-secondary/30">
                  <div
                    className="flex cursor-pointer items-center gap-2 px-2.5 py-1.5 text-[11px] transition-colors hover:bg-secondary/60"
                    onClick={(e) => { e.stopPropagation(); setExpandedStep(expandedStep === step.id ? null : step.id); }}
                  >
                    <span className="text-muted-foreground w-4 text-center font-mono">{i + 1}</span>
                    <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${stepStatusCls[step.status] || 'bg-idle'}`} />
                    <span className="font-mono font-medium">{step.nodeType}</span>
                    <StatusBadge
                      kind={kindOfStepStatus(step.status)}
                      label={t(`workflows.stepStatus.${step.status}`)}
                      icon={false}
                    />
                    {step.durationMs != null && <span className="font-mono text-[11px] text-muted-foreground">{step.durationMs}ms</span>}
                    {step.errorMessage && <span className="flex-1 truncate text-halt">{step.errorMessage}</span>}
                    <ChevronDown className={`h-2.5 w-2.5 ml-auto text-muted-foreground transition-transform ${expandedStep === step.id ? 'rotate-180' : ''}`} />
                  </div>
                  {expandedStep === step.id && (
                    <div className="space-y-1.5 border-t border-rail px-2.5 pb-2">
                      {step.outputData != null && (
                        <div className="pt-1.5">
                          <JsonBlock label={t('workflows.builder.stepOutput')} value={asText(step.outputData)} maxHeight="max-h-32" />
                        </div>
                      )}
                      {step.inputData != null && (
                        <JsonBlock label={t('workflows.builder.stepInput')} value={asText(step.inputData)} maxHeight="max-h-32" />
                      )}
                      {step.errorMessage && (
                        <div>
                          <span className="mono-label !text-halt">{t('workflows.builder.stepError')}</span>
                          <p className="mt-0.5 break-all rounded border border-halt/25 bg-halt-soft p-2 font-mono text-[11px] text-halt">
                            {step.errorMessage}
                          </p>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              ))}
            </div>
          ) : (
            <p className="px-2 py-1 text-[10px] text-muted-foreground">{t('workflows.builder.noStepData')}</p>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * A workflow step's status is a domain status, so it wears the four reserved
 * meanings like every other one: a finished step is `ok`, one still going is
 * `retry` (an attempt still owed), a failure is `halt`, and a step that never
 * ran is `idle`. This page used to keep a private colour map that reached for
 * the same tokens by hand — which is exactly how a status stops matching the
 * rest of the product one rename later.
 */
function kindOfStepStatus(status: string): StatusKind {
  switch (status) {
    case 'SUCCESS': return 'ok';
    case 'RUNNING': return 'retry';
    case 'FAILED': return 'halt';
    default: return 'idle';
  }
}

/** Step payloads arrive as either a JSON string or an already-parsed object. */
function asText(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}

export default function WorkflowBuilderPage() {
  return (
    <ReactFlowProvider>
      <WorkflowBuilderInner />
    </ReactFlowProvider>
  );
}
