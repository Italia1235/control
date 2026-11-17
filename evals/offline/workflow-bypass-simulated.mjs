import {readFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {createResolutionCatalog, normalizeWorkflowResolution, workflowResolutionPreview} from './resolution-mock.mjs';

// No HTTP client: state is derived only from accepted preview arguments.
export async function createBypassSimulation({resolutions} = {}) {
  const base=new URL('../../src/main/resources/llm/',import.meta.url);
  const instructions=await readFile(new URL('rules.md',base),'utf8');
  const tools=[];
  for(const file of ['workflow-tools.json','resolution-tools.json','rollback-tools.json']) {
    for(const tool of JSON.parse(await readFile(new URL(file,base),'utf8'))) {
      if(['configure_workflow','get_resolutions','create_resolution','list_statuses','list_workflows','start_operation','get_operation'].includes(tool.name)) tools.push({...tool,inputSchema:tool.input_schema});
    }
  }
  const plans=new Map(),operations=new Map(),workflows=new Map();
  const catalog=createResolutionCatalog(resolutions);
  const ownership={sessionId:randomUUID(),changeSetId:randomUUID()};
  const backend={mode:'simulated',ownership,
    async initialize(){return {instructions,tools,pluginVersion:null};},
    async request(resource){if(resource!=='/rest/api/2/serverInfo') throw new Error(`No simulated REST: ${resource}`);return null;},
    async call(name,args){
      if(name==='list_statuses') return {statuses:[],total:0};
      if(name==='get_resolutions' || name==='create_resolution') return catalog.execute(name,args);
      if(name==='list_workflows') return {workflows:[...workflows.values()].map(w=>({name:w.name})),total:workflows.size};
      if(name==='configure_workflow') {
        if(args.dryRun!==true) throw new Error('Direct apply prohibited');
        if (!args.create || Object.keys(args).sort().join(',') !== 'create,dryRun') return {status:'FAILED',errorCode:'VALIDATION_ERROR'};
        const requested=structuredClone(args.create);
        const workflow=normalizeWorkflowResolution(requested,catalog);
        if(workflow.status==='FAILED') return workflow;
        const names=new Set(workflow.statuses?.map(s=>s.name));
        if(!workflow.name || !names.has(workflow.initialStatus) || names.size!==workflow.statuses.length ||
          new Set(workflow.transitions?.map(t=>t.key)).size!==workflow.transitions?.length ||
          workflow.transitions?.some(t=>!t.key || !names.has(t.to)||(!t.fromAny && t.from!=='*' && !names.has(t.from)))) return {status:'FAILED',errorCode:'VALIDATION_ERROR'};
        const preview=workflowResolutionPreview(requested,catalog);
        const planId=randomUUID();plans.set(planId,workflow);
        return {status:'WOULD_CREATE',dryRun:true,planId,name:workflow.name,statuses:workflow.statuses.map((s,i)=>({...s,id:String(i+1),isNew:true})),initialStatus:workflow.initialStatus,transitions:preview.transitions,warnings:[]};
      }
      if(name==='start_operation') {
        if(!['configure_workflow','create_resolution'].includes(args.toolName) || args.arguments?.dryRun!==false || Object.keys(args.arguments).sort().join(',')!=='dryRun,planId') throw new Error('Invalid managed apply');
        let result;
        if(args.toolName==='create_resolution') result=catalog.apply(args.arguments);
        else {
          const workflow=plans.get(args.arguments.planId);
          if(!workflow || workflows.has(workflow.name)) throw new Error('Stale or reused plan');
          const current=normalizeWorkflowResolution(workflow,catalog);
          if(current.status==='FAILED') result={...current,dryRun:false,errorCode:'PREVIEW_STALE'};
          else {
            workflows.set(workflow.name,structuredClone(workflow));
            result={status:'CREATED',dryRun:false,name:workflow.name,statuses:workflow.statuses.map((s,i)=>({...s,id:String(i+1),isNew:true})),transitions:workflowResolutionPreview(workflow,catalog).transitions};
          }
          plans.delete(args.arguments.planId);
        }
        const operationId=randomUUID();
        operations.set(operationId,{status:'COMPLETED',operationId,result});
        return {status:'QUEUED',operationId};
      }
      if(name==='get_operation') {
        if(!operations.has(args.operationId)) throw new Error('Foreign operation');
        return structuredClone(operations.get(args.operationId));
      }
      throw new Error(`Unsupported simulated tool: ${name}`);
    }};
  class MemoryState {
    async snapshot(){return {workflows:[...workflows.values()].map(w=>({name:w.name})),statuses:[...workflows.values()].flatMap(w=>w.statuses.map((s,i)=>({...s,id:String(i+1)})))};}
    async readWorkflow(name){return structuredClone(workflows.get(name)??null);}
  }
  return {createToolBackend:()=>backend,JiraWorkflowState:MemoryState};
}
