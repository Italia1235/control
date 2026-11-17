import { setupShared, sharedScope } from '../../screen-scheme-ops-common.mjs';
export const confirmationScope = sharedScope;
export const confirmation = 'simulated-user';
export default async function setup(context) { return setupShared(context); }
