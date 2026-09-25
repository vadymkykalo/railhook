import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type {
  TransformationResponse,
  TransformationVersionResponse,
  TransformationVersionDiffResponse,
} from '../../types/api.types';

vi.mock('../../api/transformations.api', () => ({
  transformationsApi: {
    get: vi.fn(),
    listVersions: vi.fn(),
    getVersion: vi.fn(),
    diffVersions: vi.fn(),
    restoreVersion: vi.fn(),
  },
}));

import TransformationHistoryPage from '../TransformationHistoryPage';
import { transformationsApi } from '../../api/transformations.api';

const TRANSFORMATION_ID = 'tf-1';
const ROUTE = '/projects/:projectId/transformations/:transformationId/history';
const AT = `/projects/${TEST_PROJECT_ID}/transformations/${TRANSFORMATION_ID}/history`;

const TRANSFORMATION: TransformationResponse = {
  id: TRANSFORMATION_ID,
  projectId: TEST_PROJECT_ID,
  name: 'Stripe → CRM',
  kind: 'TEMPLATE',
  template: '{"a":"${$.three}"}',
  version: 3,
  enabled: true,
  subscriptionCount: 1,
  destinationCount: 0,
  createdAt: '2026-08-01T10:00:00Z',
  updatedAt: '2026-08-03T10:00:00Z',
};

const VERSIONS: TransformationVersionResponse[] = [
  {
    id: 'v-3', transformationId: TRANSFORMATION_ID, version: 3, current: true,
    createdBy: 'user-1', createdByEmail: 'owner@example.com', createdAt: '2026-08-03T10:00:00Z',
  },
  {
    id: 'v-2', transformationId: TRANSFORMATION_ID, version: 2, current: false,
    createdAt: '2026-08-02T10:00:00Z',
  },
  {
    id: 'v-1', transformationId: TRANSFORMATION_ID, version: 1, current: false,
    createdBy: 'user-1', createdByEmail: 'owner@example.com', createdAt: '2026-08-01T10:00:00Z',
  },
];

const DIFF: TransformationVersionDiffResponse = {
  transformationId: TRANSFORMATION_ID,
  leftVersion: 1,
  rightVersion: 3,
  leftCreatedAt: '2026-08-01T10:00:00Z',
  rightCreatedAt: '2026-08-03T10:00:00Z',
  leftTemplate: '{"a":"${$.one}"}',
  rightTemplate: '{"a":"${$.three}"}',
  diffs: [{ path: '$.a', type: 'CHANGED', leftValue: '${$.one}', rightValue: '${$.three}' }],
};

describe('TransformationHistoryPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(transformationsApi.get).mockResolvedValue(TRANSFORMATION);
    vi.mocked(transformationsApi.listVersions).mockResolvedValue(VERSIONS);
    vi.mocked(transformationsApi.diffVersions).mockResolvedValue(DIFF);
    vi.mocked(transformationsApi.getVersion).mockResolvedValue({
      ...VERSIONS[2], template: '{"a":"${$.one}"}',
    });
    vi.mocked(transformationsApi.restoreVersion).mockResolvedValue({ ...TRANSFORMATION, version: 4 });
  });

  it('lists every version with who published it, and marks the one in use', async () => {
    renderPage(<TransformationHistoryPage />, { path: ROUTE, initialEntry: AT });

    await waitFor(() => expect(screen.getByText('v3')).toBeInTheDocument());
    expect(screen.getByText('v2')).toBeInTheDocument();
    expect(screen.getByText('v1')).toBeInTheDocument();
    expect(screen.getAllByText('owner@example.com')).toHaveLength(2);
    expect(screen.getByText('API key')).toBeInTheDocument();
    expect(screen.getByText('Current')).toBeInTheDocument();
  });

  it('compares the two versions that are ticked', async () => {
    const user = userEvent.setup();
    renderPage(<TransformationHistoryPage />, { path: ROUTE, initialEntry: AT });

    await waitFor(() => expect(screen.getByText('v3')).toBeInTheDocument());
    await user.click(screen.getByLabelText('Compare version 1'));
    await user.click(screen.getByLabelText('Compare version 3'));

    await waitFor(() =>
      expect(transformationsApi.diffVersions).toHaveBeenCalledWith(TEST_PROJECT_ID, TRANSFORMATION_ID, 1, 3));
    expect(await screen.findByText('$.a')).toBeInTheDocument();
  });

  it('says a restore adds a version rather than removing the ones after it, and only then restores', async () => {
    const user = userEvent.setup();
    renderPage(<TransformationHistoryPage />, { path: ROUTE, initialEntry: AT });

    await waitFor(() => expect(screen.getByText('v1')).toBeInTheDocument());
    // Rows run newest first and v3 is current, so the restore buttons are [v2, v1].
    await user.click(screen.getAllByRole('button', { name: /Restore/i })[1]);

    const dialog = await screen.findByRole('alertdialog').catch(() => screen.getByRole('dialog'));
    expect(within(dialog).getByText(/published again as v4/i)).toBeInTheDocument();
    expect(within(dialog).getByText(/stays in the history/i)).toBeInTheDocument();
    expect(transformationsApi.restoreVersion).not.toHaveBeenCalled();

    await user.click(within(dialog).getByRole('button', { name: 'Restore' }));

    await waitFor(() =>
      expect(transformationsApi.restoreVersion).toHaveBeenCalledWith(TEST_PROJECT_ID, TRANSFORMATION_ID, 1));
  });

  it('offers no restore for the version already in use', async () => {
    renderPage(<TransformationHistoryPage />, { path: ROUTE, initialEntry: AT });

    await waitFor(() => expect(screen.getByText('v3')).toBeInTheDocument());
    expect(screen.getAllByRole('button', { name: /Restore/i })).toHaveLength(2);
  });
});
