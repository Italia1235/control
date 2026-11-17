import { setupPrivate } from '../../itss-mappings-common.mjs';
export { confirmationScope } from '../../itss-mappings-common.mjs';
export const confirmation = 'simulated-user';
export default async function setup(context) { return setupPrivate(context); }
