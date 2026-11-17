export const LAB_PAID_RUN = Object.freeze({
  scenario: 'customfields_lab_two_screens',
  model: 'openai-codex/gpt-6.1-sol',
  mode: 'soft',
  limit: 300000,
});

export const CREATE_SCREEN_NAMES_PAID_RUN = Object.freeze({
  ...LAB_PAID_RUN,
  scenario: 'customfields_create_screen_names',
});

export const ISSUE_TYPES_PARTIAL_PAID_RUN = Object.freeze({
  ...LAB_PAID_RUN,
  scenario: 'issuetypes_partial_result',
});

export const WORKFLOW_READ_NO_COUNTS_PAID_RUNS = Object.freeze(['assign', 'clone'].map(variant =>
  Object.freeze({ ...LAB_PAID_RUN, scenario: `workflow_read_no_counts_${variant}` })));

export const WORKFLOW_DRAFT_PUBLISH_PAID_RUN = Object.freeze({ ...LAB_PAID_RUN, scenario: 'workflow_draft_publish' });

export const PAID_RUNS = Object.freeze({
  [LAB_PAID_RUN.scenario]: LAB_PAID_RUN,
  [CREATE_SCREEN_NAMES_PAID_RUN.scenario]: CREATE_SCREEN_NAMES_PAID_RUN,
  [ISSUE_TYPES_PARTIAL_PAID_RUN.scenario]: ISSUE_TYPES_PARTIAL_PAID_RUN,
  ...Object.fromEntries(WORKFLOW_READ_NO_COUNTS_PAID_RUNS.map(run => [run.scenario, run])),
  [WORKFLOW_DRAFT_PUBLISH_PAID_RUN.scenario]: WORKFLOW_DRAFT_PUBLISH_PAID_RUN,
});

export function getPaidRun(scenario = LAB_PAID_RUN.scenario) {
  if (!Object.hasOwn(PAID_RUNS, scenario)) {
    throw new Error(`PAID_RUN_CONFIG: unapproved scenario ${scenario}`);
  }
  return PAID_RUNS[scenario];
}

export function assertPaidConfiguration(config, scenario = LAB_PAID_RUN.scenario) {
  const approved = getPaidRun(scenario);
  if (config.provider !== 'omp' || config.ompModel !== approved.model) {
    throw new Error(`PAID_RUN_CONFIG: require OMP model ${approved.model}; aliases and API transport are not allowed`);
  }
  if (config.tokenLimit?.mode !== approved.mode || config.tokenLimit?.limit !== approved.limit) {
    throw new Error(`PAID_RUN_CONFIG: require ${approved.mode} token ceiling ${approved.limit}`);
  }
  return { provider: config.provider, model: config.ompModel,
    tokenLimitMode: config.tokenLimit.mode, maxTokensPerRun: config.tokenLimit.limit };
}
