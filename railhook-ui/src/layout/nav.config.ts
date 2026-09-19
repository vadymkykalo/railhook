import {
  LayoutDashboard, Network, Radio, Send, GitBranch, BarChart3, Wrench,
  Webhook, Bell, ArrowDownToLine, Repeat2, FileJson2, Shield, Activity,
  AlertTriangle, History, GitCompare, Play, TestTube, Cable, Users, Key,
  FileText, Building2, CreditCard, Settings, ShieldCheck,
} from 'lucide-react';
import type { Role } from '../auth/ProtectedRoute';

/**
 * Two levels, and only two.
 *
 * The rail names the seven things a person comes here to do. Everything else
 * is a tab inside one of them, because it is a facet of that thing rather than
 * a separate destination: a Schema is a property of the connection it validates,
 * the DLQ is a status a delivery is in, Replay is something you do to deliveries
 * you have selected.
 *
 * The previous sidebar listed all of it flat — 32 entries in 10 groups, needing
 * 1472px of column in a 731px viewport — and answered the overflow with a
 * "show advanced features" toggle. Two levels is the answer; a toggle is not.
 */

export interface NavEntry {
  nameKey: string;
  /** Built from the project id, or absolute when the destination is org-level. */
  path: (projectId?: string) => string;
  icon: React.ElementType;
  requiredRole?: Role;
  /** Route segments this entry owns, for active-state matching. */
  owns: string[];
}

export interface NavSection extends NavEntry {
  /** Rendered as a tab strip under the header. One entry means no strip. */
  tabs: NavEntry[];
}

/**
 * With no project the destination is the section's own setup screen, not `/admin/projects`: every
 * entry used to resolve to that one page, so a brand-new organization saw a rail of links that all
 * led back to where it already was.
 */
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
    // Everything that decides where an event goes and what it looks like on arrival.
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
      tab('nav.outgoingEvents', 'events', Radio),
      tab('nav.incomingEvents', 'incoming-events', ArrowDownToLine),
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
    // The workbench: things you drive, not records you read.
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

/**
 * API keys belong to a project, but they are a setting, not something you do to events — so they
 * sit in the Settings strip, built from the current project like any project tab. They used to be
 * reachable only from the command palette: Settings owned the route, and no tab led there.
 */
export const API_KEYS_TAB: NavEntry = tab('nav.apiKeys', 'api-keys', Key);

/**
 * Settings, reached from the sidebar footer rather than the rail.
 *
 * Only some of it is org-level. `/admin/settings` is the person's own profile —
 * their display name, their timezone, the form that changes their password — and
 * every member has to be able to open it. The three that follow act on the
 * organization, and those stay OWNER-only.
 */
export const SETTINGS_SECTION: NavSection = {
  nameKey: 'nav.settings',
  path: () => '/admin/settings',
  icon: Settings,
  owns: ['settings', 'org-settings', 'members', 'audit-log', 'billing', 'api-keys'],
  tabs: [
    orgTab('nav.profile', '/admin/settings', Settings),
    orgTab('nav.orgSettings', '/admin/org-settings', Building2, 'OWNER'),
    orgTab('nav.members', '/admin/members', Users, 'OWNER'),
    API_KEYS_TAB,
    orgTab('nav.auditLog', '/admin/audit-log', FileText),
    orgTab('nav.billing', '/admin/billing', CreditCard, 'OWNER'),
  ],
};

/**
 * The platform admin panel, for the people who run the deployment — reached from the sidebar
 * footer, and only offered when `/auth/me` says `platformAdmin`.
 *
 * Deliberately no `requiredRole`: an organization role has nothing to do with it, and an OWNER
 * is not a platform admin. The pages check `platformAdmin` themselves, and the server checks it
 * again — with the sign-in's age — on every request.
 */
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

/**
 * The one-line "what is this" for an entry, shown when the pointer rests on it: `nav.consumers`
 * reads `navHints.consumers`. A label has to stay a word or two, and several of them — Consumers,
 * Time Machine, Failed Forwards — say nothing to someone who has not read the docs yet. An entry
 * with no hint in the locale files simply has none.
 */
export function hintKeyOf(entry: NavEntry): string {
  return entry.nameKey.replace(/^nav\./, 'navHints.');
}

/** The route segment currently in view, from either URL shape. */
export function segmentOf(pathname: string): string {
  const afterAdmin = pathname.replace(/^\/admin\/?/, '');
  const parts = afterAdmin.split('/').filter(Boolean);
  if (parts[0] === 'projects' && parts.length >= 3) return parts[2];
  if (parts[0] === 'start' && parts.length >= 2) return parts[1];
  // The panel's views are one level deeper, so each is named for its second segment: otherwise
  // Overview, Organizations and Users would all be "platform", and every tab would be current.
  if (parts[0] === 'platform') return parts[1] ? `platform-${parts[1]}` : 'platform';
  return parts[0] ?? '';
}

export function sectionFor(pathname: string): NavSection | undefined {
  const segment = segmentOf(pathname);
  if (SETTINGS_SECTION.owns.includes(segment)) return SETTINGS_SECTION;
  if (PLATFORM_SECTION.owns.includes(segment)) return PLATFORM_SECTION;
  return PROJECT_SECTIONS.find((s) => s.owns.includes(segment));
}

/**
 * The minimum role a destination demands — read by the navigation to decide what
 * to offer, and by the layout to decide what to admit.
 *
 * One table for both, because they were two. The sidebar showed the Settings
 * entry to every member while the router guarded `/admin/settings` at OWNER, so
 * an invited developer or viewer clicked their own profile and got Access
 * Denied — with no other way to change their password. Neither side declares a
 * role of its own any more: an entry states what it needs here, and a tab that
 * is shown is by construction a tab that opens.
 */
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
