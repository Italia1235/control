import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../../src/main/resources/js/control.js', import.meta.url), 'utf8');

class Node {
    constructor(tagName) {
        this.tagName = tagName;
        this.children = [];
        this.attributes = {};
        this.listeners = {};
        this.style = {};
        this.value = '';
        this.ownText = '';
    }
    set textContent(value) { this.ownText = String(value); this.children = []; }
    get textContent() { return this.ownText + this.children.map(child => child.textContent).join(''); }
    get firstChild() { return this.children[0] || null; }
    appendChild(child) { this.children.push(child); return child; }
    removeChild(child) { this.children.splice(this.children.indexOf(child), 1); }
    setAttribute(key, value) { this.attributes[key] = value; }
    getAttribute(key) { return this.attributes[key] || null; }
    addEventListener(key, handler) { this.listeners[key] = handler; }
}

function find(node, className) {
    if ((node.className || '').split(' ').includes(className)) { return node; }
    for (const child of node.children) {
        const found = find(child, className);
        if (found) { return found; }
    }
    return null;
}

function openDetail(fieldChanges, keyboard = false) {
    const root = new Node('div');
    root.setAttribute('data-i18n', JSON.stringify({
        'control.applies.detail.fieldchanges': 'Изменения полей',
        'control.applies.detail.fieldchanges.unavailable': 'Изменения полей недоступны.'
    }));
    const item = { recordedAt: 0, operationState: 'SUCCEEDED', domainStatus: 'CHANGED', fieldChanges };
    class XMLHttpRequest {
        open(method, url) { this.url = url; }
        setRequestHeader() {}
        send() {
            this.status = 200;
            this.readyState = 4;
            this.responseText = JSON.stringify(this.url.endsWith('/facets') ? {} : { items: [item], total: 1 });
            this.onreadystatechange();
        }
    }
    const document = {
        readyState: 'complete',
        getElementById: id => id === 'control-applies' ? root : null,
        createElement: tag => new Node(tag),
        createTextNode: text => { const node = new Node('#text'); node.textContent = text; return node; }
    };
    vm.runInNewContext(source, { document, window: {}, XMLHttpRequest });
    const row = find(root, 'ca-row-main');
    assert.equal(row.getAttribute('role'), 'button');
    assert.equal(row.tabIndex, 0);
    if (keyboard) { row.listeners.keydown({ key: 'Enter', preventDefault() {} }); }
    else { row.listeners.click(); }
    assert.equal(row.getAttribute('aria-expanded'), 'true');
    const detail = find(root, 'ca-detail-card');
    const index = detail.children.findIndex(child => child.textContent === 'Изменения полей');
    return { row, root, value: index === -1 ? null : detail.children[index + 1] };
}

test('actual detail renders field IDs and literal added/deleted as text, accessible by keyboard', () => {
    const unsafe = '<img src=x onerror=alert(1)>';
    const { row, root, value } = openDetail({ available: true, items: [
        { fieldId: unsafe, action: 'added' }, { fieldId: 'customfield_10001', action: 'deleted' }
    ] }, true);
    assert.deepEqual(value.children[0].children.map(line => line.textContent), [
        unsafe + ' — added', 'customfield_10001 — deleted'
    ]);
    for (const line of value.children[0].children) { assert.equal(line.children.length, 0); }
    row.listeners.keydown({ key: ' ', preventDefault() {} });
    assert.equal(row.getAttribute('aria-expanded'), 'false');
    assert.equal(find(root, 'ca-detail'), null);
});

test('unknown actual changes render the localized unavailable message, not claimed changes', () => {
    const { value } = openDetail({ available: false, items: [{ fieldId: 'summary', action: 'added' }] });
    assert.equal(value.textContent, 'Изменения полей недоступны.');
});

for (const [name, value] of [
    ['legacy missing', undefined], ['legacy null', null], ['known empty', { available: true, items: [] }]
]) {
    test(name + ' has no field changes section', () => {
        assert.equal(openDetail(value).value, null);
    });
}
