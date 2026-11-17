/*
 * Audit page (admin): reads GET /rest/control/1.0/applies and /applies/facets and shows them. View only.
 * Layout follows docs/design/Audit.html: filter card, a table card grouped by day, chips for objects, a coloured
 * result badge and an expandable detail card per row.
 * Journal data is untrusted text: it is only ever put into the page with textContent / createElement, never as HTML.
 * ES5 on purpose: Jira's resource minifier must be able to parse this file.
 */
(function () {
    "use strict";

    var PAGE = 30;
    var SHORT_TARGETS = 3;

    /** Page texts from the servlet's data-i18n (control.applies.*); a missing text shows its key. {0}, {1} are arguments. */
    function texts(root) {
        var map = {};
        try { map = JSON.parse(root.getAttribute("data-i18n") || "{}") || {}; } catch (broken) { map = {}; }
        function has(key) { return Object.prototype.hasOwnProperty.call(map, key); }
        function text(key, a, b) {
            var value = has(key) ? String(map[key]) : key;
            if (a !== undefined) { value = value.replace(/\{0\}/g, a); }
            if (b !== undefined) { value = value.replace(/\{1\}/g, b); }
            return value;
        }
        /** The text of prefix + code, or null when there is none. */
        function lookup(prefix, code) {
            return code && has("control.applies." + prefix + code) ? String(map["control.applies." + prefix + code]) : null;
        }
        return {
            lookup: lookup,
            title: text("control.applies.title"),
            mostActive: text("control.applies.mostactive"),
            records: function (n) { return text(n === 1 ? "control.applies.mostactive.one" : "control.applies.mostactive.many", String(n)); },
            summary: function (shown, total) { return text("control.applies.summary", String(shown), String(total)); },
            who: text("control.applies.filter.who"),
            action: text("control.applies.filter.action"),
            state: text("control.applies.filter.state"),
            result: text("control.applies.filter.result"),
            from: text("control.applies.filter.from"),
            to: text("control.applies.filter.to"),
            any: text("control.applies.filter.any"),
            apply: text("control.applies.filter.apply"),
            reset: text("control.applies.filter.reset"),
            colTime: text("control.applies.col.time"),
            colWho: text("control.applies.col.who"),
            colAction: text("control.applies.col.action"),
            colTarget: text("control.applies.col.target"),
            colResult: text("control.applies.col.result"),
            more: function (n) { return text("control.applies.target.more", String(n)); },
            showMore: text("control.applies.showmore"),
            loading: text("control.applies.loading"),
            empty: text("control.applies.empty"),
            error: function (code) { return text("control.applies.error", String(code)); },
            retry: text("control.applies.retry"),
            copy: text("control.applies.copy"),
            copied: text("control.applies.copied"),
            dResult: text("control.applies.detail.result"),
            dTargets: text("control.applies.detail.targets"),
            dFieldChanges: text("control.applies.detail.fieldchanges"),
            fieldChangesUnavailable: text("control.applies.detail.fieldchanges.unavailable"),
            dLinks: text("control.applies.detail.links"),
            dState: text("control.applies.detail.state"),
            dStatus: text("control.applies.detail.status"),
            dErrorCode: text("control.applies.detail.errorcode"),
            dErrorMessage: text("control.applies.detail.errormessage"),
            dOperation: text("control.applies.detail.operation"),
            dEventType: text("control.applies.detail.eventtype"),
            dRollback: text("control.applies.detail.rollback"),
            yes: text("control.applies.yes"),
            no: text("control.applies.no")
        };
    }

    // ---- pure helpers (also used by the checks that run this file outside a browser) ----

    function pad(n) { return n < 10 ? "0" + n : String(n); }

    /** HH:mm:ss in the browser's time zone. */
    function formatTime(ms) {
        var d = new Date(ms);
        return pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds());
    }

    var WEEKDAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
    var MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
        "November", "December"];

    /** "Friday 23 October 2026" in the browser's time zone. */
    function formatDay(ms) {
        var d = new Date(ms);
        return WEEKDAYS[d.getDay()] + " " + d.getDate() + " " + MONTHS[d.getMonth()] + " " + d.getFullYear();
    }

    function dayKey(ms) {
        var d = new Date(ms);
        return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
    }

    /** Badge tones: green = created, blue = changed, orange = requirement, purple = cloned, grey = nothing changed. */
    var TONES = {
        CREATED: "created", ADDED: "created",
        CHANGED: "changed", UPDATED: "changed", ASSIGNED: "changed", MOVED: "changed", EDITED: "changed", REMOVED: "changed",
        PUBLISHED: "changed", RESTORED: "changed", COMPLETE: "changed",
        DRAFT_SAVED: "changed", UPDATED_INACTIVE: "changed", RENAMED: "changed",
        MADE_REQUIRED: "required", MADE_OPTIONAL: "required",
        CLONED: "cloned",
        EXISTS: "neutral", EXISTING: "neutral", UNCHANGED: "neutral", NO_CHANGE: "neutral", MIXED: "neutral",
        ALREADY_ASSIGNED: "neutral", ALREADY_CONFIGURED: "neutral", ALREADY_IN_PLACE: "neutral",
        ALREADY_REQUIRED: "neutral", ALREADY_OPTIONAL: "neutral", ALREADY_SEPARATE: "neutral",
        ALREADY_PRESENT: "neutral", NOT_PRESENT: "neutral", NO_DRAFT: "neutral",
        PARTIAL: "warning", PARTIALLY_CHANGED: "warning", PARTIALLY_ASSIGNED: "warning", PARTIALLY_CLONED: "warning",
        UNKNOWN: "warning",
        NOT_ATTEMPTED: "warning", PREVIEW_STALE: "warning"
    };

    /** A code without a text, in words: DRAFT_SAVED -> "Draft saved". */
    function words(code) {
        var text = String(code).toLowerCase().replace(/_/g, " ");
        return text.charAt(0).toUpperCase() + text.slice(1);
    }

    /** The text of a result or rollback code; the one fallback of the badge and the Result filter. */
    function resultLabel(code, t) {
        return t.lookup("result.", code) || t.lookup("rollback.", code) || words(code);
    }

    /**
     * The result of a row: a short badge label, its tone and an optional one-line explanation. A known tool status
     * that is not in TONES is an error (conflicts, Jira errors); a status without a text is shown in words as a
     * warning, so that a new partial result never looks like success.
     */
    function outcome(item, t) {
        var state = item.operationState;
        var status = item.domainStatus;
        if (item.eventType === "ROLLBACK") {
            var code = state === "FAILED" ? "FAILED" : status;
            var tone = code === "ROLLED_BACK" ? "changed" : code === "PARTIAL" ? "warning"
                : code === "BLOCKED" || code === "FAILED" ? "error" : "neutral";
            return {label: code ? t.lookup("rollback.", code) || words(code) : state ? words(state) : "\u2014", tone: tone,
                hint: t.lookup("hint.rollback.", code)};
        }
        if (state && state !== "COMPLETED") {
            return {label: t.lookup("state.", state) || words(state),
                tone: state === "CANCELLED" || state === "PARTIAL" ? "warning" : "error",
                hint: t.lookup("hint.state.", state)};
        }
        if (!status) { return {label: t.lookup("state.", "COMPLETED") || "COMPLETED", tone: "neutral", hint: null}; }
        if (!t.lookup("result.", status)) {
            return {label: words(status), tone: "warning", hint: t.lookup("hint.", "unlisted")};
        }
        return {label: resultLabel(status, t), tone: TONES[status] || "error", hint: t.lookup("hint.result.", status)};
    }

    function typeLabel(target, t) {
        return t.lookup("type.", target.type) || target.type || "";
    }

    /** A link is clickable only inside this Jira (base URL followed by a path boundary) and over http(s). */
    function isSafeUrl(url, baseUrl) {
        if (typeof url !== "string" || typeof baseUrl !== "string" || baseUrl === "") { return false; }
        var base = baseUrl.replace(/\/+$/, "");
        if (url.indexOf(base) !== 0) { return false; }
        var next = url.charAt(base.length);
        if (next !== "" && next !== "/" && next !== "?" && next !== "#") { return false; }
        return /^https?:\/\//i.test(url);
    }

    /** Query string of the applied filters; empty values are not sent. */
    function queryString(filters, before) {
        var parts = [];
        function add(name, value) {
            if (value !== null && value !== undefined && value !== "") {
                parts.push(encodeURIComponent(name) + "=" + encodeURIComponent(String(value)));
            }
        }
        add("actor", filters.actor);
        add("tool", filters.tool);
        add("state", filters.state);
        add("status", filters.status);
        add("from", filters.from);
        add("to", filters.to);
        add("before", before);
        add("limit", PAGE);
        return parts.join("&");
    }

    /** Two letters: first letters of the first two parts split by . - _ or space (alex.ivanov → AI), else the first two (admin → AD). */
    function initials(name) {
        var parts = String(name || "").split(/[.\-_\s]+/).filter(function (p) { return p !== ""; });
        if (parts.length === 0) { return "?"; }
        var text = parts.length > 1 ? parts[0].charAt(0) + parts[1].charAt(0) : parts[0].substring(0, 2);
        return text.toUpperCase();
    }

    /** yyyy-MM-dd from a date input to the start of that day (or of the next day) in local time, in ms. */
    function dayStart(value, addDays) {
        var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value || "");
        if (!m) { return ""; }
        return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]) + addDays).getTime();
    }

    // ---- DOM ----

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) { node.className = className; }
        if (text !== undefined && text !== null) { node.textContent = text; }
        return node;
    }

    /** The same hue for the same name on every page load. */
    function hue(name) {
        var value = 7, text = String(name || "");
        for (var i = 0; i < text.length; i++) { value = (value * 31 + text.charCodeAt(i)) % 360; }
        return value;
    }

    function crown() {
        var ns = "http://www.w3.org/2000/svg";
        var svg = document.createElementNS(ns, "svg");
        svg.setAttribute("class", "ca-crown");
        svg.setAttribute("width", "22");
        svg.setAttribute("height", "18");
        svg.setAttribute("viewBox", "0 0 22 18");
        var parts = [
            ["path", {d: "M2 15 L1 4 L6.5 8.5 L11 1.5 L15.5 8.5 L21 4 L20 15 Z", fill: "oklch(0.84 0.15 85)",
                stroke: "oklch(0.62 0.13 70)", "stroke-width": "1.2", "stroke-linejoin": "round"}],
            ["circle", {cx: "1", cy: "4", r: "1.4", fill: "oklch(0.62 0.13 70)"}],
            ["circle", {cx: "11", cy: "1.5", r: "1.4", fill: "oklch(0.62 0.13 70)"}],
            ["circle", {cx: "21", cy: "4", r: "1.4", fill: "oklch(0.62 0.13 70)"}],
            ["rect", {x: "2.5", y: "12.5", width: "17", height: "2.5", rx: "0.8", fill: "oklch(0.7 0.14 75)"}]
        ];
        for (var i = 0; i < parts.length; i++) {
            var node = document.createElementNS(ns, parts[i][0]);
            for (var name in parts[i][1]) {
                if (parts[i][1].hasOwnProperty(name)) { node.setAttribute(name, parts[i][1][name]); }
            }
            svg.appendChild(node);
        }
        return svg;
    }

    /** "Most active" badge for facets.mostActive {key, displayName, count}, or null when the journal is empty. */
    function leader(top, t) {
        if (!top || !top.count) { return null; }
        var name = top.displayName || top.key;
        var box = el("div", "ca-leader");
        var face = el("div", "ca-leader-face");
        var avatar = el("span", "ca-leader-avatar", initials(name));
        avatar.style.background = "oklch(0.92 0.05 " + hue(name) + ")";
        avatar.style.color = "oklch(0.4 0.1 " + hue(name) + ")";
        face.appendChild(avatar);
        face.appendChild(crown());
        box.appendChild(face);
        var label = el("div", "ca-leader-text");
        label.appendChild(el("span", "ca-leader-label", t.mostActive));
        var line = el("span", "ca-leader-name", name);
        line.appendChild(el("span", "ca-leader-count", t.records(top.count)));
        label.appendChild(line);
        box.appendChild(label);
        return box;
    }

    function badge(item, t) {
        var result = outcome(item, t);
        var node = el("span", "ca-badge ca-tone-" + result.tone);
        node.appendChild(el("span", "ca-badge-dot"));
        node.appendChild(document.createTextNode(result.label));
        if (result.hint) { node.title = result.hint; }
        return node;
    }

    function field(labelText, control, className) {
        var wrap = el("label", "ca-field " + className);
        wrap.appendChild(el("span", "ca-field-label", labelText));
        wrap.appendChild(control);
        return wrap;
    }

    function select(options, t) {
        var node = el("select", "ca-input");
        var first = el("option", null, t.any);
        first.value = "";
        node.appendChild(first);
        for (var i = 0; i < options.length; i++) {
            var option = el("option", null, options[i].label);
            option.value = options[i].value;
            node.appendChild(option);
        }
        return node;
    }

    function button(text, className, onClick) {
        var node = el("button", "ca-button " + className, text);
        node.type = "button";
        if (onClick) { node.addEventListener("click", onClick); }
        return node;
    }

    function request(url, done) {
        var xhr = new XMLHttpRequest();
        xhr.open("GET", url, true);
        xhr.setRequestHeader("Accept", "application/json");
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) { return; }
            var body = null;
            try { body = JSON.parse(xhr.responseText); } catch (ignore) { body = null; }
            if (xhr.status >= 200 && xhr.status < 300 && body !== null) { done(null, body); }
            else { done({status: xhr.status, message: body && typeof body.message === "string" ? body.message : null}); }
        };
        xhr.send();
    }

    function Page(root) {
        this.root = root;
        this.t = texts(root);
        this.baseUrl = root.getAttribute("data-base-url") || "";
        this.api = (root.getAttribute("data-context-path") || "") + "/rest/control/1.0";
        this.applied = {};
        this.nextBefore = null;
        this.ticket = 0;
        this.rowCount = 0;
        this.total = 0;
        this.lastDay = null;
    }

    Page.prototype.start = function () {
        var t = this.t;
        var root = this.root;
        while (root.firstChild) { root.removeChild(root.firstChild); }
        var header = el("div", "ca-header");
        header.appendChild(el("h1", "ca-title", t.title));
        this.leaderBox = el("div", "ca-leader-slot");
        header.appendChild(this.leaderBox);
        this.summary = el("div", "ca-summary");
        header.appendChild(this.summary);
        root.appendChild(header);
        this.filtersBox = el("form", "ca-card ca-filters");
        root.appendChild(this.filtersBox);
        this.status = el("div", "ca-status");
        root.appendChild(this.status);
        var card = el("div", "ca-card ca-table-card");
        this.table = el("div", "ca-table");
        var head = el("div", "ca-grid ca-head");
        var cols = [t.colTime, t.colWho, t.colAction, t.colTarget, t.colResult, ""];
        for (var i = 0; i < cols.length; i++) { head.appendChild(el("div", null, cols[i])); }
        this.table.appendChild(head);
        this.body = el("div", "ca-body");
        this.table.appendChild(this.body);
        card.appendChild(this.table);
        card.style.display = "none";
        this.card = card;
        root.appendChild(card);
        this.footer = el("div", "ca-footer");
        root.appendChild(this.footer);
        this.loadFacets();
    };

    Page.prototype.setStatus = function (node) {
        while (this.status.firstChild) { this.status.removeChild(this.status.firstChild); }
        if (node) { this.status.appendChild(node); }
    };

    Page.prototype.showLoading = function () {
        this.setStatus(el("div", "ca-note", this.t.loading));
    };

    Page.prototype.showError = function (failure, retry) {
        var t = this.t;
        var box = el("div", "ca-card ca-error");
        var code = failure && failure.status ? failure.status : 0;
        box.appendChild(el("span", null, t.error(code) + (failure && failure.message ? ": " + failure.message : "")));
        box.appendChild(button(t.retry, "ca-button-secondary", retry));
        this.setStatus(box);
    };

    Page.prototype.loadFacets = function () {
        var self = this;
        this.showLoading();
        request(this.api + "/applies/facets", function (failure, facets) {
            if (failure) { self.showError(failure, function () { self.loadFacets(); }); return; }
            self.buildFilters(facets);
            self.load(false);
        });
    };

    Page.prototype.buildFilters = function (facets) {
        var self = this;
        var t = this.t;
        var actors = [], tools = [], states = [], statuses = [], i;
        var f = facets || {};
        var mostActive = leader(f.mostActive, t);
        if (mostActive) { this.leaderBox.appendChild(mostActive); }
        for (i = 0; i < (f.actors || []).length; i++) {
            actors.push({value: f.actors[i].key, label: f.actors[i].displayName || f.actors[i].key});
        }
        for (i = 0; i < (f.tools || []).length; i++) {
            tools.push({value: f.tools[i].name, label: t.lookup("tool.", f.tools[i].name) || f.tools[i].name});
        }
        for (i = 0; i < (f.states || []).length; i++) {
            states.push({value: f.states[i], label: t.lookup("state.", f.states[i]) || words(f.states[i])});
        }
        for (i = 0; i < (f.statuses || []).length; i++) {
            statuses.push({value: f.statuses[i], label: resultLabel(f.statuses[i], t)});
        }
        this.inputs = {
            actor: select(actors, t), tool: select(tools, t), state: select(states, t), status: select(statuses, t),
            from: el("input", "ca-input"), to: el("input", "ca-input")
        };
        this.inputs.from.type = "date";
        this.inputs.to.type = "date";
        var form = this.filtersBox;
        while (form.firstChild) { form.removeChild(form.firstChild); }
        // A choice between fewer than two values filters nothing: such a filter is not shown.
        if (actors.length > 1) { form.appendChild(field(t.who, this.inputs.actor, "ca-field-narrow")); }
        if (tools.length > 1) { form.appendChild(field(t.action, this.inputs.tool, "ca-field-wide")); }
        if (states.length > 1) { form.appendChild(field(t.state, this.inputs.state, "ca-field-narrow")); }
        if (statuses.length > 1) { form.appendChild(field(t.result, this.inputs.status, "ca-field-narrow")); }
        form.appendChild(field(t.from, this.inputs.from, "ca-field-date"));
        form.appendChild(field(t.to, this.inputs.to, "ca-field-date"));
        var apply = el("button", "ca-button ca-button-primary", t.apply);
        apply.type = "submit";
        var buttons = el("div", "ca-buttons");
        buttons.appendChild(apply);
        buttons.appendChild(button(t.reset, "ca-button-secondary", function () {
            self.inputs.actor.value = "";
            self.inputs.tool.value = "";
            self.inputs.state.value = "";
            self.inputs.status.value = "";
            self.inputs.from.value = "";
            self.inputs.to.value = "";
            self.load(false);
        }));
        form.appendChild(buttons);
        form.addEventListener("submit", function (event) {
            event.preventDefault();
            self.load(false);
        });
    };

    Page.prototype.readFilters = function () {
        var i = this.inputs;
        return {
            actor: i.actor.value, tool: i.tool.value, state: i.state.value, status: i.status.value,
            from: dayStart(i.from.value, 0), to: dayStart(i.to.value, 1)
        };
    };

    /** append=false reloads from the first page with the filters now in the form; true continues with the same ones. */
    Page.prototype.load = function (append) {
        var self = this;
        if (!append) {
            this.applied = this.readFilters();
            this.nextBefore = null;
        }
        var before = append ? this.nextBefore : null;
        var ticket = ++this.ticket;
        this.showLoading();
        this.setFooter(null);
        request(this.api + "/applies?" + queryString(this.applied, before), function (failure, page) {
            if (ticket !== self.ticket) { return; }
            if (failure) {
                self.showError(failure, function () { self.load(append); });
                if (append) { self.setFooter(self.moreButton()); }
                return;
            }
            self.setStatus(null);
            if (!append) {
                while (self.body.firstChild) { self.body.removeChild(self.body.firstChild); }
                self.rowCount = 0;
                self.lastDay = null;
            }
            var items = page.items || [];
            for (var i = 0; i < items.length; i++) { self.addRow(items[i]); self.rowCount++; }
            self.total = typeof page.total === "number" ? page.total : self.rowCount;
            self.nextBefore = page.nextBefore === undefined ? null : page.nextBefore;
            self.summary.textContent = self.t.summary(self.rowCount, self.total);
            self.card.style.display = self.rowCount > 0 ? "" : "none";
            if (self.rowCount === 0) { self.setStatus(el("div", "ca-card ca-note", self.t.empty)); }
            self.setFooter(self.nextBefore !== null ? self.moreButton() : null);
        });
    };

    Page.prototype.setFooter = function (node) {
        while (this.footer.firstChild) { this.footer.removeChild(this.footer.firstChild); }
        if (node) { this.footer.appendChild(node); }
    };

    Page.prototype.moreButton = function () {
        var self = this;
        return button(this.t.showMore, "ca-button-secondary", function () { self.load(true); });
    };

    Page.prototype.addRow = function (item) {
        var t = this.t;
        var day = dayKey(item.recordedAt);
        if (day !== this.lastDay) {
            this.body.appendChild(el("div", "ca-day", formatDay(item.recordedAt)));
            this.lastDay = day;
        }
        var wrap = el("div", "ca-row");
        var row = el("div", "ca-grid ca-row-main");
        row.tabIndex = 0;
        row.setAttribute("role", "button");
        row.setAttribute("aria-expanded", "false");
        row.appendChild(el("div", "ca-time", formatTime(item.recordedAt)));
        var name = item.actor && (item.actor.displayName || item.actor.key) || "\u2014";
        var user = el("div", "ca-user");
        user.appendChild(el("span", "ca-avatar", initials(name)));
        user.appendChild(el("span", "ca-ellipsis", name));
        row.appendChild(user);
        row.appendChild(el("div", "ca-action", item.tool && (t.lookup("tool.", item.tool.name) || item.tool.name) || "\u2014"));
        row.appendChild(this.chips(item));
        var result = el("div");
        result.appendChild(badge(item, t));
        row.appendChild(result);
        var chevron = el("div", "ca-chevron", "\u203a");
        row.appendChild(chevron);
        wrap.appendChild(row);
        var detail = null;
        var self = this;
        function toggle() {
            if (detail) {
                wrap.removeChild(detail);
                detail = null;
                wrap.className = "ca-row";
                row.setAttribute("aria-expanded", "false");
                return;
            }
            detail = self.detail(item);
            wrap.appendChild(detail);
            wrap.className = "ca-row ca-open";
            row.setAttribute("aria-expanded", "true");
        }
        row.addEventListener("click", toggle);
        row.addEventListener("keydown", function (event) {
            if (event.key === "Enter" || event.key === " ") { event.preventDefault(); toggle(); }
        });
        this.body.appendChild(wrap);
    };

    /** Up to three objects as chips (type, then name), then "+N more" with N = the other items plus the dropped ones. */
    Page.prototype.chips = function (item) {
        var t = this.t;
        var box = el("div", "ca-chips");
        var items = (item.targets && item.targets.items) || [];
        var more = (item.targets && item.targets.more) || 0;
        if (items.length === 0 && more === 0) { box.appendChild(el("span", "ca-muted", "\u2014")); return box; }
        for (var i = 0; i < items.length && i < SHORT_TARGETS; i++) {
            var chip = el("span", "ca-chip");
            var type = typeLabel(items[i], t);
            if (type) { chip.appendChild(el("span", "ca-chip-type", type)); }
            chip.appendChild(el("span", "ca-chip-name", items[i].label));
            if (items[i].id) { chip.title = "ID " + items[i].id; }
            box.appendChild(chip);
        }
        var rest = Math.max(items.length - SHORT_TARGETS, 0) + more;
        if (rest > 0) { box.appendChild(el("span", "ca-chip ca-chip-more", t.more(rest))); }
        return box;
    };

    Page.prototype.detail = function (item) {
        var t = this.t;
        var baseUrl = this.baseUrl;
        var outer = el("div", "ca-detail");
        var list = el("div", "ca-detail-card");
        function row(label, value) {
            list.appendChild(el("div", "ca-detail-label", label));
            var cell = el("div", "ca-detail-value");
            if (typeof value === "string") { cell.textContent = value; } else { cell.appendChild(value); }
            list.appendChild(cell);
        }
        function link(label, url) {
            var a = el("a", null, label);
            a.setAttribute("href", url);
            a.setAttribute("rel", "noopener noreferrer");
            return a;
        }
        var result = outcome(item, t);
        if (result.hint) { row(t.dResult, result.hint); }
        // An object reported with a link of the same name gets that link on its own name; other links stay below.
        var links = (item.links || []).slice();
        var targets = (item.targets && item.targets.items) || [];
        var more = (item.targets && item.targets.more) || 0;
        var objects = el("div", "ca-detail-list");
        for (var i = 0; i < targets.length; i++) {
            var line = el("div");
            var type = typeLabel(targets[i], t);
            if (type) { line.appendChild(el("span", "ca-muted", type + ": ")); }
            var own = null;
            for (var k = 0; k < links.length; k++) {
                if (links[k].name === targets[i].label && isSafeUrl(links[k].url, baseUrl)) { own = links.splice(k, 1)[0]; break; }
            }
            line.appendChild(own ? link(targets[i].label, own.url) : document.createTextNode(targets[i].label));
            if (targets[i].id) { line.title = "ID " + targets[i].id; }
            objects.appendChild(line);
        }
        if (more > 0) { objects.appendChild(el("div", "ca-muted", t.more(more))); }
        row(t.dTargets, targets.length === 0 && more === 0 ? "\u2014" : objects);
        var fieldChanges = item.fieldChanges;
        if (fieldChanges && fieldChanges.available === false) {
            row(t.dFieldChanges, t.fieldChangesUnavailable);
        } else if (fieldChanges && fieldChanges.available === true && fieldChanges.items.length > 0) {
            var fields = el("div", "ca-detail-list");
            for (var f = 0; f < fieldChanges.items.length; f++) {
                var change = fieldChanges.items[f];
                fields.appendChild(el("div", null, change.fieldId + " \u2014 " + change.action));
            }
            row(t.dFieldChanges, fields);
        }
        if (links.length > 0) {
            var others = el("div", "ca-detail-list");
            for (var j = 0; j < links.length; j++) {
                var entry = el("div");
                var label = links[j].name || links[j].url;
                if (isSafeUrl(links[j].url, baseUrl)) { entry.appendChild(link(label, links[j].url)); }
                else { entry.textContent = label + (links[j].name ? " \u2014 " + links[j].url : ""); }
                others.appendChild(entry);
            }
            row(t.dLinks, others);
        }
        if (item.errorMessage) { row(t.dErrorMessage, item.errorMessage); }
        if (item.errorCode) { row(t.dErrorCode, item.errorCode); }
        row(t.dState, item.operationState || "\u2014");
        row(t.dStatus, item.domainStatus || "\u2014");
        if (item.operationRef) {
            var ref = el("div", "ca-ref");
            ref.appendChild(el("span", "ca-mono", item.operationRef));
            var copy = button(t.copy, "ca-button-small", null);
            copy.addEventListener("click", function (event) {
                event.stopPropagation();
                if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(item.operationRef); }
                copy.textContent = t.copied;
                setTimeout(function () { copy.textContent = t.copy; }, 1200);
            });
            ref.appendChild(copy);
            row(t.dOperation, ref);
        } else {
            row(t.dOperation, "\u2014");
        }
        row(t.dEventType, t.lookup("event.", item.eventType) || item.eventType || "\u2014");
        row(t.dRollback, item.rollbackRequested ? t.yes : t.no);
        outer.appendChild(list);
        return outer;
    };

    function init() {
        var root = document.getElementById("control-applies");
        if (!root) { return; }
        new Page(root).start();
    }

    var api = {
        formatTime: formatTime, formatDay: formatDay, outcome: outcome, isSafeUrl: isSafeUrl, initials: initials,
        queryString: queryString, dayStart: dayStart, init: init
    };
    if (typeof window !== "undefined") { window.ControlApplies = api; }
    if (typeof document !== "undefined") {
        if (document.readyState === "loading") { document.addEventListener("DOMContentLoaded", init); }
        else { init(); }
    }
}());
