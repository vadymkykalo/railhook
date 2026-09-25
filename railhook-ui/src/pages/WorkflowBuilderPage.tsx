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
  useNodesInitialized,
  useReactFlow,
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

/** Zoom floor under React Flow's default 0.5: at 0.5 a six-node workflow does not fit a phone. */
const FIT_VIEW_OPTIONS = { padding: 0.2 };
const MIN_ZOOM = 0.2;

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
  const { screenToFlowPosition, fitView } = useReactFlow();
  const nodesInitialized = useNodesInitialized();
  const fitted = useRef(false);

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

  // A refetch must not discard unsaved edits; only the canvas waits for a save.
  const hasUnsavedRef = useRef(hasUnsaved);
  hasUnsavedRef.current = hasUnsaved;

  useEffect(() => {
    if (hasUnsavedRef.current) return;
    if (workflow?.definition) {
      const def = workflow.definition;
      // API-created workflows have no positions or edge ids and the canvas throws without them, so lay them out.
      if (def.nodes && Array.isArray(def.nodes)) {
        setNodes((def.nodes as Partial<Node>[]).map((node, index) => ({
          ...node,
          position: node.position ?? { x: 80 + index * 260, y: 160 },
        }) as Node));
      }
      if (def.edges && Array.isArray(def.edges)) {
        setEdges((def.edges as Partial<Edge>[]).map((edge, index) => ({
          ...edge,
          id: edge.id ?? `${edge.source}-${edge.target}-${index}`,
        }) as Edge));
      }
      setHasUnsaved(false);
    }
  }, [workflow, setNodes, setEdges]);

  // Fit once the loaded nodes are measured: React Flow's own fitView ran on an empty canvas.
  useEffect(() => {
    if (fitted.current || !nodesInitialized || nodes.length === 0) return;
    fitted.current = true;
    fitView(FIT_VIEW_OPTIONS);
  }, [nodesInitialized, nodes.length, fitView]);

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

  // Measurement and selection changes are not edits; counting them armed Save on open.
  const isEdit = (type: string) => type !== 'dimensions' && type !== 'select';

  const handleNodesChange: typeof onNodesChange = useCallback(
    (changes) => {
      onNodesChange(changes);
      if (changes.some((change) => isEdit(change.type))) setHasUnsaved(true);
    },
    [onNodesChange],
  );

  const handleEdgesChange: typeof onEdgesChange = useCallback(
    (changes) => {
      onEdgesChange(changes);
      if (changes.some((change) => isEdit(change.type))) setHasUnsaved(true);
    },
    [onEdgesChange],
  );

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

  // The canvas converts the screen point, so the node lands under the pointer at any pan or zoom.
  const addNode = useCallback(
    (template: NodeTemplate, screenPoint: { x: number; y: number }) => {
      const dropped = screenToFlowPosition(screenPoint);
      const newNode: Node = {
        id: getNextNodeId(),
        type: template.type,
        // Half a node up and left, so the node sits around the point rather than hanging off it.
        position: { x: dropped.x - 90, y: dropped.y - 20 },
        data: { ...template.defaultData },
      };

      setNodes((nds: Node[]) => [...nds, newNode]);
      setHasUnsaved(true);
    },
    [screenToFlowPosition, setNodes],
  );

  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();
      const templateJson = event.dataTransfer.getData('application/workflow-node');
      if (!templateJson) return;

      addNode(JSON.parse(templateJson) as NodeTemplate, { x: event.clientX, y: event.clientY });
    },
    [addNode],
  );

  // Touch screens fire no dragstart, so a tap drops the node in the middle of the view.
  const addNodeToView = useCallback(
    (template: NodeTemplate) => {
      const bounds = reactFlowWrapper.current?.getBoundingClientRect();
      if (!bounds) return;
      addNode(template, { x: bounds.left + bounds.width / 2, y: bounds.top + bounds.height / 2 });
    },
    [addNode],
  );

  const deleteSelectedNode = useCallback(() => {
    if (!selectedNode) return;
    setNodes((nds: Node[]) => nds.filter((n) => n.id !== selectedNode.id));
    setEdges((eds: Edge[]) => eds.filter((e) => e.source !== selectedNode.id && e.target !== selectedNode.id));
    setSelectedNode(null);
    setHasUnsaved(true);
  }, [selectedNode, setNodes, setEdges]);

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

  const extractTriggerConfig = useCallback((): Record<string, unknown> => {
    const triggerNode = nodes.find((n) => n.type === 'webhookTrigger');
    if (triggerNode?.data) {
      const d = triggerNode.data as Record<string, unknown>;
      return { eventTypePattern: d.eventTypePattern || '*' };
    }
    return {};
  }, [nodes]);

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
        <div className="h-[70vh] animate-pulse border border-rail bg-muted" />
      </PageSkeleton>
    );
  }

  // A failed fetch is not a deleted workflow, and the canvas writes back to what this returns.
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
      <div className="border-b border-rail bg-card px-3 py-2 sm:px-4">
        <div className="flex min-w-0 items-center gap-1.5 sm:gap-3">
          <Button variant="ghost" size="icon-sm" className="flex-shrink-0" onClick={() => navigate(`/admin/projects/${projectId}/workflows`)} title={t('workflows.builder.back')} aria-label={t('workflows.builder.back')}>
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <h2 className="min-w-0 flex-1 truncate text-sm font-medium">{workflow.name}</h2>
          <div className="flex flex-shrink-0 items-center gap-0.5 sm:gap-2">
            <Button
              variant="ghost"
              size="sm"
              className="gap-1.5 px-2 text-xs sm:px-3"
              onClick={() => setShowHistory(!showHistory)}
              title={t('workflows.builder.history')}
              aria-label={t('workflows.builder.history')}
            >
              <History className="h-4 w-4" />
              <span className="hidden sm:inline">{t('workflows.builder.history')}</span>
            </Button>
            <Button
              variant="ghost"
              size="sm"
              className="gap-1.5 px-2 text-xs sm:px-3"
              onClick={() => setShowTriggerDialog(true)}
              title={t('workflows.builder.testRun')}
              aria-label={t('workflows.builder.testRun')}
            >
              <Play className="h-4 w-4" />
              <span className="hidden sm:inline">{t('workflows.builder.testRun')}</span>
            </Button>
            <Button
              variant="ghost"
              size="sm"
              className="gap-1.5 px-2 text-xs sm:px-3"
              onClick={() => toggleMutation.mutate(!workflow.enabled)}
              title={workflow.enabled ? t('workflows.builder.enabled') : t('workflows.builder.disabled')}
              aria-label={workflow.enabled ? t('workflows.builder.enabled') : t('workflows.builder.disabled')}
            >
              {workflow.enabled ? <ToggleRight className="h-4 w-4 text-ok" aria-hidden /> : <ToggleLeft className="h-4 w-4" aria-hidden />}
              <span className="hidden sm:inline">{workflow.enabled ? t('workflows.builder.enabled') : t('workflows.builder.disabled')}</span>
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
        <p className="mt-1 truncate font-mono text-[10px] text-muted-foreground">
          {[
            t('workflows.version', { version: workflow.version }),
            t('workflows.builder.nodesCount', { count: nodes.length }),
            t('workflows.builder.edgesCount', { count: edges.length }),
          ].join(' · ')}
          {hasUnsaved && <span className="ml-1 text-retry">{`● ${t('workflows.builder.unsaved')}`}</span>}
        </p>
      </div>

      <div className="flex flex-1 flex-col overflow-hidden lg:flex-row">
        <div className="flex-shrink-0 border-b border-rail bg-card lg:flex lg:w-48 lg:flex-col lg:overflow-y-auto lg:border-b-0 lg:border-r">
          <p className="mono-label px-3 pt-2 lg:px-4 lg:pt-3">
            <span className="lg:hidden">{t('workflows.builder.tapToAdd')}</span>
            <span className="hidden lg:inline">{t('workflows.builder.dragToAdd')}</span>
          </p>
          <div className="flex gap-2 overflow-x-auto px-3 pb-2 pt-1.5 lg:flex-col lg:overflow-x-visible lg:pb-3 lg:pt-2">
            {nodeTemplates.map((template) => (
              <button
                type="button"
                key={template.type}
                draggable
                onClick={() => addNodeToView(template)}
                onDragStart={(e) => {
                  e.dataTransfer.setData('application/workflow-node', JSON.stringify(template));
                  e.dataTransfer.effectAllowed = 'move';
                }}
                className="flex flex-shrink-0 cursor-grab items-center gap-2 border border-rail bg-card px-2.5 py-2 text-left transition-colors hover:border-primary/40 hover:bg-secondary/50 active:cursor-grabbing lg:w-full"
              >
                <span className="text-sm">{template.icon}</span>
                <div className="min-w-0">
                  <div className="text-xs font-medium truncate">{t(`workflows.nodeTypes.${template.type}.label`)}</div>
                  <div className="hidden truncate text-[11px] text-muted-foreground lg:block">{t(`workflows.nodeTypes.${template.type}.description`)}</div>
                </div>
              </button>
            ))}
          </div>
        </div>

        <div className="min-h-0 flex-1" ref={reactFlowWrapper}>
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
            fitViewOptions={FIT_VIEW_OPTIONS}
            minZoom={MIN_ZOOM}
            deleteKeyCode={null}
            defaultEdgeOptions={{ animated: true, style: { stroke: 'hsl(var(--rail))', strokeWidth: 2 } }}
            proOptions={{ hideAttribution: true }}
          >
            <Controls position="bottom-left" />
            <MiniMap
              position="bottom-right"
              /* Hidden on a phone, where the minimap covers a quarter of the canvas. */
              className="!hidden !border !border-rail !bg-card sm:!block"
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

        {selectedNode && (
          <NodeConfigPanel
            node={selectedNode}
            onUpdate={handleNodeDataUpdate}
            onClose={() => setSelectedNode(null)}
          />
        )}
      </div>

      {showHistory && (
        <div className="max-h-80 overflow-y-auto border-t border-rail bg-card">
          <div className="sticky top-0 z-10 flex items-center justify-between border-b border-rail bg-card px-4 py-2">
            <h3 className="mono-label">{t('workflows.builder.executionHistory')}</h3>
            <Button variant="ghost" size="icon-sm" onClick={() => setShowHistory(false)} title={t('workflows.builder.closeHistory')} aria-label={t('workflows.builder.closeHistory')}>
              <ChevronDown className="h-4 w-4" />
            </Button>
          </div>

          {(() => {
            const total = workflow.totalExecutions ?? 0;
            const success = workflow.successfulExecutions ?? 0;
            const failed = workflow.failedExecutions ?? 0;
            const rate = total > 0 ? Math.round((success / total) * 100) : 0;
            const execs = executions?.content ?? [];
            const avgMs = execs.length > 0 ? Math.round(execs.reduce((s, e) => s + (e.durationMs ?? 0), 0) / execs.length) : 0;
            return (
              <div className="grid grid-cols-2 gap-3 border-b border-rail bg-secondary/40 px-4 py-2.5 sm:grid-cols-3 lg:grid-cols-5">
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

      {showTriggerDialog && (
        <div className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center" onClick={() => setShowTriggerDialog(false)}>
          <div className="w-[480px] max-w-[90vw] space-y-4 border border-rail bg-card p-5 shadow-elevated" onClick={(e) => e.stopPropagation()}>
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
        className="flex cursor-pointer flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2 text-xs transition-colors hover:bg-secondary/50"
        onClick={() => setExpanded(!expanded)}
      >
        {statusIcon}
        <span className="font-medium">{t(`workflows.execStatus.${exec.status}`)}</span>
        <span className="font-mono text-muted-foreground">{exec.startedAt ? formatDateTime(exec.startedAt) : ''}</span>
        {exec.durationMs != null && <span className="font-mono text-muted-foreground">{exec.durationMs}ms</span>}
        {exec.errorMessage && <span className="min-w-0 flex-1 truncate text-halt">{exec.errorMessage}</span>}
        {expanded ? <ChevronUp className="h-3 w-3 ml-auto" /> : <ChevronDown className="h-3 w-3 ml-auto" />}
      </div>
      {expanded && (
        <div className="px-4 pb-3 space-y-1">
          {steps && steps.length > 0 ? (
            <div className="space-y-0.5">
              {steps.map((step, i) => (
                <div key={step.id} className="border border-rail bg-secondary/30">
                  <div
                    className="flex cursor-pointer flex-wrap items-center gap-x-2 gap-y-1 px-2.5 py-1.5 text-[11px] transition-colors hover:bg-secondary/60"
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
                    {step.errorMessage && <span className="min-w-0 flex-1 truncate text-halt">{step.errorMessage}</span>}
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
