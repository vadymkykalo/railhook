import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';

vi.mock('../../api/piiRules.api', () => ({
  piiRulesApi: { preview: vi.fn() },
}));

// CodeMirror does not run in jsdom; the editor is a textarea here.
vi.mock('../JsonEditor', () => ({
  default: ({ value, onChange }: { value: string; onChange: (v: string) => void }) => (
    <textarea aria-label="payload" value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}));

import PiiPreview from '../PiiPreview';
import { piiRulesApi } from '../../api/piiRules.api';

function render() {
  return renderPage(<PiiPreview projectId={TEST_PROJECT_ID} />, {
    path: '/projects/:projectId/pii-rules',
    initialEntry: `/projects/${TEST_PROJECT_ID}/pii-rules`,
  });
}

beforeEach(() => vi.clearAllMocks());

describe('PiiPreview', () => {
  it('shows what the rules actually did, not an illustration of what they might do', async () => {
    // The page used to print a hand-written example — including a fabricated
    // 'sha256:a1b2c3d4e5f6' that no backend ever produced.
    vi.mocked(piiRulesApi.preview).mockResolvedValue('{"email":"jo***@example.com"}');
    const user = userEvent.setup();
    render();

    await user.click(screen.getByRole('button', { name: /Run preview/i }));

    expect(await screen.findByText(/jo\*\*\*@example\.com/)).toBeInTheDocument();
    expect(piiRulesApi.preview).toHaveBeenCalledWith(TEST_PROJECT_ID, expect.any(String));
  });

  it('renders a preview the client handed back already parsed', async () => {
    // The endpoint answers text/plain, but axios parses anything that looks
    // like JSON regardless of content type — so this client resolved an object
    // while its type said string. Rendering that threw React error #31 on the
    // whole page.
    vi.mocked(piiRulesApi.preview).mockResolvedValue(
      { customer: { email: 'jo***@example.com' }, amount: 4900 } as unknown as string
    );
    const user = userEvent.setup();
    render();

    await user.click(screen.getByRole('button', { name: /Run preview/i }));

    expect(await screen.findByText(/jo\*\*\*@example\.com/)).toBeInTheDocument();
  });

  it('will not send a payload that is not JSON', async () => {
    const user = userEvent.setup();
    render();

    const editor = screen.getByLabelText('payload');
    await user.clear(editor);
    await user.type(editor, '{{not json');

    expect(screen.getByRole('button', { name: /Run preview/i })).toBeDisabled();
    expect(piiRulesApi.preview).not.toHaveBeenCalled();
  });

  it('says so when the rules changed nothing, rather than looking like it failed', async () => {
    const payload = '{"amount":10}';
    vi.mocked(piiRulesApi.preview).mockResolvedValue(payload);
    const user = userEvent.setup();
    render();

    const editor = screen.getByLabelText('payload');
    await user.clear(editor);
    await user.type(editor, payload.replace('{', '{{'));
    await user.click(screen.getByRole('button', { name: /Run preview/i }));

    expect(await screen.findByText(/Nothing was masked/i)).toBeInTheDocument();
  });

  it('reports a failed preview as a failure', async () => {
    vi.mocked(piiRulesApi.preview).mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render();

    await user.click(screen.getByRole('button', { name: /Run preview/i }));

    expect(await screen.findByText(/Preview failed/i)).toBeInTheDocument();
  });
});
