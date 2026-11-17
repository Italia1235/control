import {DEFAULT_RESOLUTIONS} from './resolution-mock.mjs';

export function generateWorkflowScenario(seed, options = {}) {
  if (!Number.isInteger(seed) || seed < 0 || seed > 0xffffffff) throw new Error('seed must be an unsigned 32-bit integer');
  let state = seed >>> 0;
  const random = () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let x = Math.imul(state ^ (state >>> 15), 1 | state);
    x ^= x + Math.imul(x ^ (x >>> 7), 61 | x);
    return ((x ^ (x >>> 14)) >>> 0) / 4294967296;
  };
  const domains = ['Согласование договора', 'Релиз продукта', 'Поддержка L2', 'Закупка оборудования'];
  const domain = domains[Math.floor(random() * domains.length)];
  const count = 5 + Math.floor(random() * 6);
  const suffix = `EV${seed.toString(16).padStart(8, '0')}`;
  const middle = ['Анализ', 'Проверка', 'Согласование', 'Подготовка', 'Исполнение', 'Контроль', 'Ожидание', 'Приёмка'];
  for (let i = middle.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [middle[i], middle[j]] = [middle[j], middle[i]];
  }
  const names = ['Новая заявка', ...middle.slice(0, count - 2), 'Завершено'];
  const statuses = names.map((name, i) => ({
    name: `${name} ${suffix}`, category: i === 0 ? 'TO_DO' : i === count - 1 ? 'DONE' : random() < 0.2 ? 'TO_DO' : 'IN_PROGRESS',
  }));
  // The forward chain guarantees connectivity and reachability of the final status.
  const transitions = statuses.slice(1).map((s, i) => ({key: `transition-${i + 1}`, name: `Перейти: ${names[i + 1]}`, from: statuses[i].name, to: s.name}));
  transitions.push({key:`transition-${transitions.length + 1}`,name:'Вернуть на доработку', from:statuses.at(-1).name, to:statuses[1].name});
  if (random() < 0.5) transitions.push({key:`transition-${transitions.length + 1}`,name:'Повторная проверка',from:statuses[2].name,to:statuses[1].name});
  // Resolution fixtures consume no randomness: old graph, screen and omission draws stay unchanged.
  const resolutions = structuredClone(options.resolutions ?? DEFAULT_RESOLUTIONS);
  const completed = resolutions.find(value => value.name.toLowerCase() === 'completed');
  if (!completed) throw new Error('scenario resolutions must include Completed');
  for (const transition of transitions) {
    if (statuses.find(status => status.name === transition.to)?.category === 'DONE') {
      transition.resolution = {action:'SET',value:completed.id};
    } else if (statuses.find(status => status.name === transition.from)?.category === 'DONE') {
      transition.resolution = {action:'CLEAR'};
    }
  }
  const scenario = {
    scenario: `workflow_live_seed_${seed}`, seed, persona:'cooperative_admin_ru', omissions:[], transitionScreens:[], resolutions,
    workflow:{name:`${domain} ${suffix}`, statuses, initialStatus:statuses[0].name, transitions},
  };
  if (options.stage !== 2) return scenario;
  const personas = ['cooperative_admin_ru', 'hasty_admin_ru', 'nontechnical_user_ru', 'vague_user_ru', 'mindchanger_admin_ru'];
  const slot = (seed + 4) % personas.length;
  scenario.stage = 2;
  scenario.persona = personas[slot];
  // Sorting a copy makes the seed independent of REST catalog ordering.
  const screens = [...(options.screens ?? [])].sort((a, b) =>
    String(a.id).localeCompare(String(b.id), 'en') || a.name.localeCompare(b.name, 'en'));
  if (screens.some(screen => screen.id == null || typeof screen.name !== 'string' || !screen.name.trim())) {
    throw new Error('screens must contain existing screen identities with nonempty names');
  }
  if (screens.length) {
    for (const transitionIndex of [0, transitions.length - 1]) {
      const screen = screens[Math.floor(random() * screens.length)];
      transitions[transitionIndex].screen = screen.name;
      scenario.transitionScreens.push({
        transitionIndex, transitionName: transitions[transitionIndex].name,
        screenId: String(screen.id), screenName: screen.name,
      });
    }
  }
  const candidates = [
    {id:'status-category', path:'statuses.1.category', kind:'category', expected:statuses[1].category},
    {id:'initial-status', path:'initialStatus', kind:'initialStatus', expected:statuses[0].name},
    screens.length
      ? {id:'transition-screen', path:'transitions.0.screen', kind:'transitionScreen', expected:transitions[0].screen}
      : {id:'transition-name', path:'transitions.0.name', kind:'transitionName', expected:transitions[0].name},
  ];
  scenario.omissions = candidates.slice(0, [0, 1, 2, 3, 2][slot]);
  if (scenario.persona === 'mindchanger_admin_ru') {
    scenario.initialWorkflow = structuredClone(scenario.workflow);
    const transition = transitions[1];
    scenario.revision = {
      path:'transitions.1.name', initial:transition.name,
      expected:`${transition.name} — подтверждено`, afterFirstPreview:true,
    };
    transition.name = scenario.revision.expected;
  }
  return scenario;
}
