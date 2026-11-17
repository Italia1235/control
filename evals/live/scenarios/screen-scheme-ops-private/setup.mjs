import { setupPrivate, privateScope } from '../../screen-scheme-ops-common.mjs';
export const confirmationScope = privateScope;
export const confirmation = 'simulated-user';
export default async function setup(context) { return setupPrivate(context); }
