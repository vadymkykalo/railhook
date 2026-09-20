import { describe, it, expect } from 'vitest';
import { classifyError } from '../errorClassifier';
import type { DeliveryAttemptResponse } from '../../types/api.types';

const attempt = (errorMessage: string | undefined, httpStatusCode?: number): DeliveryAttemptResponse =>
  ({ id: 'a', deliveryId: 'd', attemptNumber: 1, errorMessage, httpStatusCode,
    createdAt: new Date().toISOString() } as DeliveryAttemptResponse);

/**
 * The two cases that never reached the network.
 *
 * Both used to fall through every branch below them into UNKNOWN — "something went wrong,
 * check the endpoint" — which points the reader at a receiver that was never asked. They are
 * checked before the connection-level rules for that reason, so these cases pin the order as
 * much as the mapping.
 */
describe('classifyError, for an attempt that never left the worker', () => {
  it('names a failed transformation, not an unknown error', () => {
    const result = classifyError(attempt(
      'TRANSFORM_FAILED: Script transformation failed (RUNTIME at line 4): Error: boom'));

    expect(result.category).toBe('TRANSFORM_FAILED');
    expect(result.severity).toBe('error');
    expect(result.fixKey).toBe('errorClass.transformFailed.fix');
  });

  it('recognises a template failure too, which says neither "script" nor a line', () => {
    expect(classifyError(attempt('TRANSFORM_FAILED: Payload transformation failed: bad JSON')).category)
      .toBe('TRANSFORM_FAILED');
  });

  /** A cancellation is a filter doing its job. Calling it an error would be a false alarm. */
  it('names a cancellation as information, not as a failure', () => {
    const result = classifyError(attempt('CANCELLED_BY_TRANSFORMATION: test traffic'));

    expect(result.category).toBe('TRANSFORM_CANCELLED');
    expect(result.severity).toBe('info');
  });

  /**
   * A transformation failure carries no status code, which is exactly the shape the timeout and
   * connection rules match on. Ordering is the whole of this test.
   */
  it('is not mistaken for a timeout when the word appears in the script\'s own message', () => {
    expect(classifyError(attempt('TRANSFORM_FAILED: Script transformation failed (TIMEOUT): the script was still running')).category)
      .toBe('TRANSFORM_FAILED');
  });

  it('leaves everything else classified as it was', () => {
    expect(classifyError(attempt('connection refused')).category).toBe('CONNECTION_REFUSED');
    expect(classifyError(attempt(undefined, 429)).category).toBe('RATE_LIMITED');
    expect(classifyError(attempt(undefined, 503)).category).toBe('SERVER_ERROR');
  });
});
