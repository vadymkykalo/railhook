import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ShieldCheck } from 'lucide-react';
import { piiRulesApi } from '../api/piiRules.api';
import { isValidJson, formatJson } from '../lib/json';
import { resolveErrorMessage } from '../lib/toast';
import JsonEditor from './JsonEditor';
import JsonBlock from './JsonBlock';
import {
  ResultFrame, ResultPlaceholder, RunControl, Workbench, WorkbenchPanel,
} from './Workbench';

/**
 * What the rules actually do to a payload.
 *
 * <p>The rules list used to answer this with `maskExample()` — a hand-written
 * illustration that returned `'sha256:a1b2c3d4e5f6'` for every HASH rule and
 * `'jo***@example.com'` for every email one. It was a drawing of masking, not
 * masking: it could not know which rules were enabled, it never saw the
 * operator's payload, and its hash was invented. Meanwhile
 * `POST /pii-rules/preview` had shipped, with a client in `piiRules.api.ts`,
 * and nothing had ever called it.
 *
 * <p>So rules stop being authored blind. The shape is the one the test console
 * and the transform studio already use: input left, one run control under it,
 * the verdict right.
 */

const SAMPLE = JSON.stringify(
  {
    customer: { email: 'jordan@example.com', phone: '+1 555 0134' },
    card: { number: '4242424242424242' },
    amount: 4900,
  },
  null,
  2
);

export default function PiiPreview({ projectId }: { projectId: string }) {
  const { t } = useTranslation();
  const [payload, setPayload] = useState(SAMPLE);
  const [running, setRunning] = useState(false);
  const [output, setOutput] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const valid = isValidJson(payload);

  const run = async () => {
    setRunning(true);
    setFailure(null);
    try {
      setOutput(await piiRulesApi.preview(projectId, payload));
    } catch (err) {
      setOutput(null);
      // The badge already says "Preview failed"; the title has to say
      // something else or the frame prints the same sentence twice.
      setFailure(resolveErrorMessage(err, 'piiRules.preview.failedFallback'));
    } finally {
      setRunning(false);
    }
  };

  // Comparing the parsed forms, not the strings: the backend returns its own
  // formatting, so a whitespace difference is not a masked field.
  const unchanged =
    output !== null && isValidJson(output) && formatJson(output) === formatJson(payload);

  return (
    <WorkbenchPanel
      eyebrow={t('piiRules.preview.title')}
      title={t('piiRules.preview.description')}
      bodyClassName="p-4"
    >
      <Workbench
        input={
          <div className="space-y-1.5">
            <div className="mono-label">{t('piiRules.preview.inputLabel')}</div>
            <JsonEditor value={payload} onChange={setPayload} minHeight="180px" maxHeight="320px" />
          </div>
        }
        run={
          <RunControl
            label={t('piiRules.preview.run')}
            runningLabel={t('piiRules.preview.running')}
            running={running}
            disabled={!valid}
            onClick={run}
            hint={!valid ? t('piiRules.preview.invalidJson') : undefined}
          />
        }
        result={
          failure ? (
            <ResultFrame kind="halt" statusLabel={t('piiRules.preview.failed')} title={failure} />
          ) : output === null ? (
            <ResultPlaceholder
              icon={ShieldCheck}
              title={t('piiRules.preview.placeholder')}
              hint={t('piiRules.preview.placeholderHint')}
            />
          ) : (
            <ResultFrame
              kind={unchanged ? 'idle' : 'ok'}
              statusLabel={t(unchanged ? 'piiRules.preview.unchanged' : 'piiRules.preview.masked')}
              title={unchanged ? t('piiRules.preview.unchangedHint') : t('piiRules.preview.outputLabel')}
            >
              <JsonBlock label={t('piiRules.preview.outputLabel')} value={output} maxHeight="max-h-80" />
            </ResultFrame>
          )
        }
      />
    </WorkbenchPanel>
  );
}
