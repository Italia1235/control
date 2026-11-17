export const SOFT_LIMIT_NOTICE = 'soft limit, возможен перерасход на один запрос плюс retries';
const count = value => Number.isSafeInteger(value) && value >= 0;

export function usageCounts(usage) {
  if (!usage || typeof usage !== 'object') return {total:null,cached:null};
  let total = count(usage.totalTokens) ? usage.totalTokens : count(usage.total_tokens) ? usage.total_tokens : null;
  if (total == null && count(usage.prompt_tokens) && count(usage.completion_tokens)) total = usage.prompt_tokens + usage.completion_tokens;
  if (total == null && ['input','output','cacheRead','cacheWrite'].every(key=>count(usage[key]))) total = usage.input + usage.output + usage.cacheRead + usage.cacheWrite;
  const cached = count(usage.cacheRead) ? usage.cacheRead
    : count(usage.prompt_tokens_details?.cached_tokens) ? usage.prompt_tokens_details.cached_tokens
    : count(usage.input_tokens_details?.cached_tokens) ? usage.input_tokens_details.cached_tokens : null;
  return {total,cached};
}

export function tokenLimitConfiguration(env=process.env) {
  const mode=env.EVAL_TOKEN_LIMIT_MODE || 'hard';
  if (!['hard','soft'].includes(mode)) throw new Error('EVAL_TOKEN_LIMIT_MODE must be hard or soft');
  const raw=env.EVAL_MAX_TOKENS_PER_RUN;
  const limit=raw==null || raw==='' ? null : Number(raw);
  if (limit!=null && (!Number.isSafeInteger(limit) || limit<=0)) throw new Error('EVAL_MAX_TOKENS_PER_RUN должен быть положительным целым числом');
  if (mode==='soft' && limit==null) throw new Error('EVAL_TOKEN_LIMIT_MODE=soft requires EVAL_MAX_TOKENS_PER_RUN');
  return {mode,limit};
}

export function initializeTokenBudget(context,configuration) {
  if (configuration.mode!=='soft') return;
  context.maxAssistantCalls=Math.min(context.maxAssistantCalls,context.softAssistantCallLimit ?? 10);
  context.diagnostics.tokenBudget ??= {mode:'soft',limit:configuration.limit,notice:SOFT_LIMIT_NOTICE,
    observedTokens:0,totalTokens:0,usageComplete:true,enforcement:'usage',reached:false,maxAssistantCalls:context.maxAssistantCalls,byRole:{}};
}

export function recordTokenUsage(context,role,usage) {
  const budget=context.diagnostics.tokenBudget;
  if (!budget) return;
  const {total,cached}=usageCounts(usage);
  const row=budget.byRole[role] ??= {responses:0,observedTokens:0,totalTokens:0,observedCachedTokens:0,cachedTokens:0,usageComplete:true,cacheComplete:true};
  row.responses++;
  if (total==null) {
    row.usageComplete=false;budget.usageComplete=false;budget.enforcement='request-count-only';
    context.maxAssistantCalls=Math.min(context.maxAssistantCalls,10);
    budget.maxAssistantCalls=context.maxAssistantCalls;
  } else {row.observedTokens+=total;budget.observedTokens+=total;}
  if (cached==null) row.cacheComplete=false;
  else row.observedCachedTokens+=cached;
  row.cachedTokens=row.cacheComplete?row.observedCachedTokens:null;
  row.totalTokens=row.usageComplete?row.observedTokens:null;
  budget.totalTokens=budget.usageComplete?budget.observedTokens:null;
  budget.reached=budget.usageComplete && budget.observedTokens>=budget.limit;
}
