import { prepare } from '../field-context-extend/setup.mjs';

export default async function setup(context) {
  return prepare(context, true);
}
