import {
  LayoutDashboard, Network, Radio, Send, GitBranch, BarChart3, Wrench,
  Webhook, Bell, ArrowDownToLine, Repeat2, FileJson2, Shield, Activity,
  AlertTriangle, History, GitCompare, Play, TestTube, Cable, Users, Key,
  FileText, Building2, CreditCard, Settings, ShieldCheck,
} from 'lucide-react';
import type { Role } from '../auth/ProtectedRoute';

export interface NavEntry {
  nameKey: string;
  path: (projectId?: string) => string;
  icon: React.ElementType;
  requiredRole?: Role;
  owns: string[];
}

export interface NavSection extends NavEntry {
  tabs: NavEntry[];
}

/** With no project each entry leads to its own setup screen, not back to /admin/projects. */
const p = (projectId: string | undefined, segment: string) =>
  projectId ? `/admin/projects/${projectId}/${segment}` : `/admin/start/${segment}`;

const tab = (nameKey: string, segment: string, icon: React.ElementType, requiredRole?: Role): NavEntry => ({
  nameKey,
  path: (projectId) => p(projectId, segment),
  icon,
  owns: [segment],
  requiredRole,
});

const orgTab = (nameKey: string, path: string, icon: React.ElementType, requiredRole?: Role): NavEntry => ({
  nameKey,
  path: () => path,
  icon,
  owns: [path.replace('/admin/', '')],
  requiredRole,
});

export const PROJECT_SECTIONS: NavSection[] = [
  {
    nameKey: 'nav.overview',
    path: () => '/admin/dashboard',
    icon: LayoutDashboard,
    owns: ['dashboard'],
    tabs: [],
  },
  {
    nameKey: 'nav.connections',
    path: (projectId) => p(projectId, 'connections'),
    icon: Network,
    owns: ['connections', 'connection-setup', 'endpoints', 'consumers', 'subscriptions', 'incoming-sources', 'transformations', 'rules', 'schemas', 'pii-rules'],
    tabs: [
      tab('nav.connections', 'connections', Network),
      tab('nav.endpoints', 'endpoints', Webhook),
      tab('nav.consumers', 'consumers', Users),
      tab('nav.subscriptions', 'subscriptions', Bell),
      tab('nav.incomingSources', 'incoming-sources', ArrowDownToLine),
      tab('nav.transformations', 'transformations', Repeat2),
      tab('nav.rules', 'rules', GitBranch),
      tab('nav.schemas', 'schemas', FileJson2),
      tab('nav.piiRules', 'pii-rules', Shield),
    ],
  },
  {
    nameKey: 'nav.events',
    path: (projectId) => p(projectId, 'events'),
    icon: Radio,
    owns: ['events', 'incoming-events'],
    tabs: [
      tab('nav.outgoing', 'events', Radio),
      tab('nav.incoming', 'incoming-events', ArrowDownToLine),
    ],
  },
  {
    nameKey: 'nav.deliveries',
    path: (projectId) => p(projectId, 'deliveries'),
    icon: Send,
    owns: ['deliveries', 'dlq', 'incoming-dlq', 'replay'],
    tabs: [
      tab('nav.allDeliveries', 'deliveries', Send),
      tab('nav.dlq', 'dlq', AlertTriangle),
      tab('nav.incomingDlq', 'incoming-dlq', ArrowDownToLine),
      tab('nav.replay', 'replay', History),
    ],
  },
  {
    nameKey: 'nav.workflows',
    path: (projectId) => p(projectId, 'workflows'),
    icon: GitBranch,
    owns: ['workflows'],
    tabs: [],
  },
  {
    nameKey: 'nav.analytics',
    path: (projectId) => p(projectId, 'analytics'),
    icon: BarChart3,
    owns: ['analytics', 'alerts', 'incidents', 'usage'],
    tabs: [
      tab('nav.metrics', 'analytics', BarChart3),
      tab('nav.alerts', 'alerts', Bell),
      tab('nav.incidents', 'incidents', AlertTriangle),
      tab('nav.usage', 'usage', Activity),
    ],
  },
  {
    nameKey: 'nav.develop',
    path: (projectId) => p(projectId, 'test-console'),
    icon: Wrench,
    owns: ['test-console', 'transform-studio', 'event-diff', 'test-endpoints', 'tunnels'],
    tabs: [
      tab('nav.testConsole', 'test-console', Play),
      tab('nav.transformStudio', 'transform-studio', GitCompare),
      tab('nav.eventDiff', 'event-diff', GitCompare),
      tab('nav.testEndpoints', 'test-endpoints', TestTube),
      orgTab('nav.tunnels', '/admin/tunnels', Cable),
    ],
  },
];

export const SETTINGS_SECTION: NavSection = {
  nameKey: 'nav.settings',
  path: () => '/admin/settings',
  icon: Settings,
  owns: ['settings', 'org-settings', 'members', 'audit-log', 'billing', 'api-keys'],
  tabs: [
    orgTab('nav.profile', '/admin/settings', Settings),
    orgTab('nav.orgSettings', '/admin/org-settings', Building2, 'OWNER'),
    orgTab('nav.members', '/admin/members', Users, 'OWNER'),
    tab('nav.apiKeys', 'api-keys', Key),
    orgTab('nav.auditLog', '/admin/audit-log', FileText),
    orgTab('nav.billing', '/admin/billing', CreditCard, 'OWNER'),
  ],
};

/** No requiredRole: an OWNER is not a platform admin; pages and server check platformAdmin. */
export const PLATFORM_SECTION: NavSection = {
  nameKey: 'nav.platformAdmin',
  path: () => '/admin/platform',
  icon: ShieldCheck,
  owns: ['platform', 'platform-organizations', 'platform-users'],
  tabs: [
    { nameKey: 'nav.platformOverview', path: () => '/admin/platform', icon: LayoutDashboard, owns: ['platform'] },
    {
      nameKey: 'nav.platformOrganizations',
      path: () => '/admin/platform/organizations',
      icon: Building2,
      owns: ['platform-organizations'],
    },
    { nameKey: 'nav.platformUsers', path: () => '/admin/platform/users', icon: Users, owns: ['platform-users'] },
  ],
};

export function segmentOf(pathname: string): string {
  const afterAdmin = pathname.replace(/^\/admin\/?/, '');
  const parts = afterAdmin.split('/').filter(Boolean);
  if (parts[0] === 'projects' && parts.length >= 3) return parts[2];
  if (parts[0] === 'start' && parts.length >= 2) return parts[1];
  // Named by the second segment, or every platform tab would be current.
  if (parts[0] === 'platform') return parts[1] ? `platform-${parts[1]}` : 'platform';
  return parts[0] ?? '';
}

export function sectionFor(pathname: string): NavSection | undefined {
  const segment = segmentOf(pathname);
  if (SETTINGS_SECTION.owns.includes(segment)) return SETTINGS_SECTION;
  if (PLATFORM_SECTION.owns.includes(segment)) return PLATFORM_SECTION;
  return PROJECT_SECTIONS.find((s) => s.owns.includes(segment));
}

/** One table for nav and router: two tables once showed members a Settings link that denied them. */
const ROLE_BY_SEGMENT: ReadonlyMap<string, Role> = new Map(
  [
    ...PROJECT_SECTIONS.flatMap((section) => [section as NavEntry, ...section.tabs]),
    SETTINGS_SECTION as NavEntry,
    ...SETTINGS_SECTION.tabs,
  ]
    .filter((entry) => entry.requiredRole)
    .flatMap((entry) => entry.owns.map((segment) => [segment, entry.requiredRole!] as const))
);

export function requiredRoleFor(pathname: string): Role | undefined {
  return ROLE_BY_SEGMENT.get(segmentOf(pathname));
}
