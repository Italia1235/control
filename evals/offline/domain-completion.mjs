// Domain success is independent of the managed-operation wrapper status.
export function domainSucceeded(tool, result, expectedStatus, previewArguments) {
  if (!result || result.errorCode || result.status === 'FAILED') return false;
  if (tool !== 'add_field_to_screens') {
    return typeof expectedStatus === 'string' && expectedStatus.length > 0 &&
      expectedStatus !== 'FAILED' && !expectedStatus.includes('PARTIAL') && result.status === expectedStatus;
  }
  const fields = previewArguments?.fieldIds;
  const screens = previewArguments?.screens;
  if (result.dryRun !== false || !fields?.length || !screens?.length ||
      !Array.isArray(result.fields) || result.fields.length !== fields.length) return false;
  return fields.every(id => {
    const matches = result.fields.filter(field => field.fieldId === id);
    if (matches.length !== 1) return false;
    const field = matches[0];
    return !field.errorCode && Array.isArray(field.results) && field.results.length === screens.length &&
      screens.every(({ screenId }) => {
        const pairs = field.results.filter(row => row.screenId === screenId);
        return pairs.length === 1 && pairs[0].status === 'ADDED' && !pairs[0].errorCode;
      });
  });
}
