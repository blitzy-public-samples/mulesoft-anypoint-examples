// Interactive API console: renders api.json and sends Try It requests (D-009).
// ES5 syntax and APIs only, one IIFE, no global variable, no external resource (D-009).
(function (window, document) {
    'use strict';

    // Element ids of index.html that this script reads and writes (D-009).
    var IDS = [
        'api-title', 'raml-link', 'console-error', 'resource-list', 'details', 'details-title',
        'details-description', 'request-format', 'responses', 'try-it', 'try-it-url', 'try-it-params',
        'try-it-media-type', 'try-it-body-section', 'try-it-send', 'response', 'response-status',
        'response-headers', 'response-body'
    ];

    // Header rows of the parameter and response-header tables.
    var PARAMETER_COLUMNS = [
        'Name', 'Type', 'Required', 'Min length', 'Max length', 'Enum', 'Default', 'Example', 'Description'
    ];
    var HEADER_COLUMNS = ['Name', 'Type', 'Description'];

    // Events of a Try It field that refresh the request URL.
    var FIELD_EVENTS = ['input', 'change', 'keyup'];

    // Module state: the loaded model, the method buttons, the selected resource and method, and the
    // sequence number of the latest request.
    var state = { model: null, buttons: [], resource: null, method: null, sequence: 0 };

    // Returns the element with the given id, or null.
    function byId(id) {
        return document.getElementById(id);
    }

    // Removes every child node of an element.
    function clear(element) {
        while (element.firstChild) {
            element.removeChild(element.firstChild);
        }
    }

    // Creates an element, sets each attribute with setAttribute and appends content as a text node
    // when content is neither null nor undefined.
    function make(tag, attrs, content) {
        var element = document.createElement(tag);
        var name;
        if (attrs) {
            for (name in attrs) {
                if (Object.prototype.hasOwnProperty.call(attrs, name)) {
                    element.setAttribute(name, String(attrs[name]));
                }
            }
        }
        if (content !== null && content !== undefined) {
            element.appendChild(document.createTextNode(String(content)));
        }
        return element;
    }

    // Sets the className of an element and returns the element.
    function styled(element, name) {
        element.className = name;
        return element;
    }

    // Returns '' for null or undefined, otherwise String(value).
    function text(value) {
        return value === null || value === undefined ? '' : String(value);
    }

    // Whether a model member holds a value that renders as non-empty text.
    function has(value) {
        return text(value) !== '';
    }

    // Returns the value when it is an array, otherwise an empty array.
    function list(value) {
        return Array.isArray(value) ? value : [];
    }

    // Replaces the text of an element; null and undefined write ''.
    function setText(element, value) {
        element.textContent = text(value);
    }

    // Writes a message to the browser console when one is available.
    function report(message) {
        if (window.console && typeof window.console.error === 'function') {
            window.console.error('API console: ' + message);
        }
    }

    // Message of a caught error or rejection reason.
    function errorMessage(err) {
        return err && err.message ? err.message : String(err);
    }

    // Path segments, non-empty, lower-cased and without braces, joined with '-':
    // '/teams/{teamId}' gives 'teams-teamid'.
    function slug(path) {
        return text(path).split('/').filter(function (segment) {
            return segment !== '';
        }).map(function (segment) {
            return segment.toLowerCase().replace(/[{}]/g, '');
        }).join('-');
    }

    // Id of a method button, for example 'method-get-teams' (D-009).
    function methodId(verb, path) {
        return 'method-' + text(verb).toLowerCase() + '-' + slug(path);
    }

    // Scheme and authority of the page: location.protocol + '//' + location.host.
    function origin() {
        return window.location.protocol + '//' + window.location.host;
    }

    // API base path: the model's basePath when it is a string, otherwise the page path before
    // '/console'.
    function basePath() {
        var pathname = window.location.pathname;
        if (state.model && typeof state.model.basePath === 'string') {
            return state.model.basePath;
        }
        return pathname.substring(0, pathname.indexOf('/console'));
    }

    // Upper-case verb of a model method.
    function verbOf(method) {
        return text(method.method).toUpperCase();
    }

    // 'status statusText', or the status alone when statusText is empty.
    function statusLine(status, statusText) {
        return text(statusText) !== '' ? String(status) + ' ' + statusText : String(status);
    }

    // Text of the Required column: 'yes', 'no', or '' when the member is absent.
    function requiredText(required) {
        if (required === true) {
            return 'yes';
        }
        return required === false ? 'no' : '';
    }

    // Whether a link target is a relative reference: non-empty, no scheme, no leading '//' and no
    // backslash.
    function isRelative(href) {
        return href !== '' && !/^[A-Za-z][A-Za-z0-9+.\-]*:/.test(href) && href.indexOf('//') !== 0
            && href.indexOf('\\') < 0;
    }

    // Adds or removes 'selected' in the className of a method button and sets its aria-pressed state.
    function markSelected(button, selected) {
        var names = button.className.split(/\s+/).filter(function (name) {
            return name !== '' && name !== 'selected';
        });
        if (selected) {
            names.push('selected');
        }
        button.className = names.join(' ');
        button.setAttribute('aria-pressed', selected ? 'true' : 'false');
    }

    // Sends one request with window.fetch and reports the status, header lines and body text.
    function fetchRequest(method, url, headers, body, finish, fail) {
        var options = { method: method, headers: headers };
        var pending;
        if (body !== null && body !== undefined) {
            options.body = body;
        }
        try {
            pending = window.fetch(url, options);
        } catch (err) {
            fail(errorMessage(err));
            return;
        }
        pending.then(function (response) {
            var lines = [];
            response.headers.forEach(function (value, name) {
                lines.push(name + ': ' + value);
            });
            return response.text().then(function (bodyText) {
                finish(response.status, response.statusText, lines, bodyText);
            });
        }).then(null, function (err) {
            fail(errorMessage(err));
        });
    }

    // Sends one asynchronous XMLHttpRequest and reports the status, header lines and body text;
    // status 0 reports 'Network error'.
    function xhrRequest(method, url, headers, body, finish, fail) {
        var xhr;
        var name;
        try {
            xhr = new XMLHttpRequest();
            xhr.open(method, url, true);
            for (name in headers) {
                if (Object.prototype.hasOwnProperty.call(headers, name)) {
                    xhr.setRequestHeader(name, headers[name]);
                }
            }
        } catch (err) {
            fail(errorMessage(err));
            return;
        }
        xhr.onreadystatechange = function () {
            var lines;
            if (xhr.readyState !== 4) {
                return;
            }
            if (xhr.status === 0) {
                fail('Network error');
                return;
            }
            lines = String(xhr.getAllResponseHeaders() || '').split(/\r?\n/).filter(function (line) {
                return line !== '';
            });
            finish(xhr.status, xhr.statusText, lines, xhr.responseText);
        };
        try {
            xhr.send(body === undefined ? null : body);
        } catch (err) {
            fail(errorMessage(err));
        }
    }

    // Sends every request: fetch when present, otherwise XMLHttpRequest (D-009, D-503).
    // onDone(status, statusText, headerLines, bodyText) runs once for every HTTP status;
    // onError(message) runs once when no response arrives.
    function request(method, url, headers, body, onDone, onError) {
        var settled = false;

        function finish(status, statusText, lines, bodyText) {
            if (settled) {
                return;
            }
            settled = true;
            onDone(status, statusText, lines, bodyText);
        }

        function fail(message) {
            if (settled) {
                report(method + ' ' + url + ': ' + message);
                return;
            }
            settled = true;
            onError(message);
        }

        if (typeof window.fetch === 'function') {
            fetchRequest(method, url, headers, body, finish, fail);
        } else {
            xhrRequest(method, url, headers, body, finish, fail);
        }
    }

    // Builds a table.params with one header row and one row per list of cell texts.
    function table(columns, rows) {
        var element = styled(make('table'), 'params');
        var head = make('thead');
        var headRow = make('tr');
        var bodyElement = make('tbody');
        columns.forEach(function (column) {
            headRow.appendChild(make('th', { scope: 'col' }, column));
        });
        head.appendChild(headRow);
        rows.forEach(function (cells) {
            var row = make('tr');
            cells.forEach(function (cell) {
                row.appendChild(make('td', null, cell));
            });
            bodyElement.appendChild(row);
        });
        element.appendChild(head);
        element.appendChild(bodyElement);
        return element;
    }

    // Cell texts of a URI or query parameter row, in PARAMETER_COLUMNS order.
    function parameterCells(parameter) {
        return [
            text(parameter.name),
            text(parameter.type),
            requiredText(parameter.required),
            text(parameter.minLength),
            text(parameter.maxLength),
            list(parameter['enum']).map(text).join(', '),
            text(parameter['default']),
            text(parameter.example),
            text(parameter.description)
        ];
    }

    // Cell texts of a response header row, in HEADER_COLUMNS order.
    function headerCells(header) {
        return [text(header.name), text(header.type), text(header.description)];
    }

    // Appends an h5 title, a pre with the text exactly as provided and a link to its file, each when
    // present.
    function appendContract(container, title, name, content, file) {
        var href = text(file);
        if (!has(content) && !has(file)) {
            return;
        }
        container.appendChild(make('h5', null, title));
        if (has(content)) {
            container.appendChild(styled(make('pre', null, text(content)), name));
        }
        if (isRelative(href)) {
            container.appendChild(make('a', { href: href }, href));
        }
    }

    // Appends the media type, the schema and the example of one body entry, each when present.
    function appendBody(container, body) {
        if (has(body.mediaType)) {
            container.appendChild(make('p', null, body.mediaType));
        }
        appendContract(container, 'Schema', 'schema', body.schema, body.schemaFile);
        appendContract(container, 'Example', 'example', body.example, body.exampleFile);
    }

    // Fills #request-format: URI parameters, query parameters and request bodies, each when present.
    function renderRequestFormat(resource, method) {
        var container = byId('request-format');
        var uriParameters = list(resource.uriParameters);
        var queryParameters = list(method.queryParameters);
        var bodies = list(method.body);
        clear(container);
        if (uriParameters.length > 0) {
            container.appendChild(make('h4', null, 'URI parameters'));
            container.appendChild(table(PARAMETER_COLUMNS, uriParameters.map(parameterCells)));
        }
        if (queryParameters.length > 0) {
            container.appendChild(make('h4', null, 'Query parameters'));
            container.appendChild(table(PARAMETER_COLUMNS, queryParameters.map(parameterCells)));
        }
        bodies.forEach(function (body) {
            container.appendChild(make('h4', null, 'Body'));
            appendBody(container, body);
        });
        if (uriParameters.length === 0 && queryParameters.length === 0 && bodies.length === 0) {
            container.appendChild(make('p', null, 'No parameters or body.'));
        }
    }

    // Fills #responses with one div.response per declared response, in model order.
    function renderResponses(method) {
        var container = byId('responses');
        var responses = list(method.responses);
        clear(container);
        responses.forEach(function (response) {
            var status = text(response.status);
            var headers = list(response.headers);
            var entry = styled(make('div', { 'data-status': status }), 'response');
            entry.appendChild(make('h4', null, status));
            if (has(response.description)) {
                entry.appendChild(make('p', null, response.description));
            }
            if (headers.length > 0) {
                entry.appendChild(make('h5', null, 'Headers'));
                entry.appendChild(table(HEADER_COLUMNS, headers.map(headerCells)));
            }
            list(response.body).forEach(function (body) {
                appendBody(entry, body);
            });
            container.appendChild(entry);
        });
        if (responses.length === 0) {
            container.appendChild(make('p', null, 'No responses declared.'));
        }
    }

    // Value of the input with the given id, or '' when the input is absent.
    function inputValue(id) {
        var input = byId(id);
        return input ? text(input.value) : '';
    }

    // Request URL from the current input values: origin, base path and path with each {name}
    // replaced, then the non-empty query parameters in model order.
    function buildUrl() {
        var pairs = [];
        var path = text(state.resource.path).replace(/\{([^}]+)\}/g, function (match, name) {
            return encodeURIComponent(inputValue('param-uri-' + name));
        });
        list(state.method.queryParameters).forEach(function (parameter) {
            var name = text(parameter.name);
            var value = inputValue('param-query-' + name);
            if (value !== '') {
                pairs.push(encodeURIComponent(name) + '=' + encodeURIComponent(value));
            }
        });
        return origin() + basePath() + path + (pairs.length > 0 ? '?' + pairs.join('&') : '');
    }

    // Writes the request URL of the selected method into #try-it-url; a value that cannot be
    // encoded writes the error message.
    function refreshUrl() {
        if (!state.method) {
            return;
        }
        try {
            setText(byId('try-it-url'), buildUrl());
        } catch (err) {
            setText(byId('try-it-url'), errorMessage(err));
        }
    }

    // Sets data-state of #response; aria-busy is 'true' while the state is 'pending'.
    function setResultState(name) {
        var response = byId('response');
        response.setAttribute('data-state', name);
        if (name === 'pending') {
            response.setAttribute('aria-busy', 'true');
        } else {
            response.removeAttribute('aria-busy');
        }
    }

    // Clears the status, headers and body of the result area, then sets its data-state.
    function resetResult(name) {
        var status = byId('response-status');
        setText(status, '');
        status.removeAttribute('data-status');
        setText(byId('response-headers'), '');
        setText(byId('response-body'), '');
        setResultState(name);
    }

    // Appends a div.param with a label and a text input pre-filled with the example, else the
    // default, else ''; returns the input.
    function appendField(container, prefix, parameter) {
        var name = text(parameter.name);
        var id = prefix + name;
        var value = has(parameter.example) ? text(parameter.example) : text(parameter['default']);
        var wrapper = styled(make('div'), 'param');
        var input = make('input', { type: 'text', id: id, 'data-name': name, value: value });
        input.value = value;
        if (parameter.required === true) {
            input.setAttribute('aria-required', 'true');
        }
        wrapper.appendChild(make('label', { 'for': id }, parameter.required === true ? name + ' *' : name));
        wrapper.appendChild(input);
        container.appendChild(wrapper);
        return input;
    }

    // Binds the URL refresh to every event of FIELD_EVENTS on a Try It field.
    function watchField(field) {
        FIELD_EVENTS.forEach(function (type) {
            field.addEventListener(type, refreshUrl);
        });
    }

    // Fills Try It for a method: parameter inputs, body editor, media type and send button label;
    // resets the result area and stores the selection.
    function renderTryIt(resource, method) {
        var params = byId('try-it-params');
        var bodySection = byId('try-it-body-section');
        var bodies = list(method.body);
        var fields = [];
        var example;
        var textarea;
        clear(params);
        clear(bodySection);
        list(resource.uriParameters).forEach(function (parameter) {
            fields.push(appendField(params, 'param-uri-', parameter));
        });
        list(method.queryParameters).forEach(function (parameter) {
            fields.push(appendField(params, 'param-query-', parameter));
        });
        if (bodies.length > 0) {
            example = text(bodies[0].example);
            textarea = make('textarea', { id: 'try-it-body', rows: 10 }, example);
            textarea.value = example;
            bodySection.appendChild(make('label', { 'for': 'try-it-body' }, 'Body'));
            bodySection.appendChild(textarea);
            fields.push(textarea);
            setText(byId('try-it-media-type'), bodies[0].mediaType);
        } else {
            setText(byId('try-it-media-type'), '');
        }
        setText(byId('try-it-send'), verbOf(method));
        state.resource = resource;
        state.method = method;
        state.sequence += 1;
        resetResult('idle');
        fields.forEach(watchField);
        refreshUrl();
    }

    // Opens the details panel of a method: title, description, request format, responses and Try It.
    function select(resource, method, button) {
        state.buttons.forEach(function (other) {
            markSelected(other, other === button);
        });
        byId('details').style.display = 'block';
        setText(byId('details-title'), verbOf(method) + ' ' + text(resource.path));
        setText(byId('details-description'), method.description);
        renderRequestFormat(resource, method);
        renderResponses(method);
        renderTryIt(resource, method);
    }

    // Click handler of one method button.
    function selectHandler(resource, method, button) {
        return function () {
            select(resource, method, button);
        };
    }

    // One div.resource: the path, the display name when present and one button per method.
    function resourceEntry(resource) {
        var path = text(resource.path);
        var entry = styled(make('div', { 'data-path': path }), 'resource');
        entry.appendChild(styled(make('div', null, path), 'resource-path'));
        if (has(resource.displayName)) {
            entry.appendChild(styled(make('div', null, resource.displayName), 'resource-name'));
        }
        list(resource.methods).forEach(function (method) {
            var verb = verbOf(method);
            var button = styled(make('button', {
                type: 'button',
                id: methodId(verb, path),
                'data-method': verb,
                'data-path': path,
                'aria-label': verb + ' ' + path,
                'aria-pressed': 'false'
            }, verb), 'method-button');
            button.addEventListener('click', selectHandler(resource, method, button));
            state.buttons.push(button);
            entry.appendChild(button);
        });
        return entry;
    }

    // Writes 'Unable to load api.json: <reason>' into #console-error, shows it and empties the resource
    // list.
    function loadFailed(reason) {
        var box = byId('console-error');
        var message = 'Unable to load api.json: ' + reason;
        state.model = null;
        state.buttons = [];
        clear(byId('resource-list'));
        setText(box, message);
        box.style.display = 'block';
        report(message);
    }

    // Stores the model and fills the title, the RAML link and the resource list in model order.
    function renderModel(model) {
        var title = [text(model.title).trim(), text(model.version).trim()].filter(function (part) {
            return part !== '';
        }).join(' ');
        var ramlFile = text(model.ramlFile);
        var link = byId('raml-link');
        var resourceList = byId('resource-list');
        var resources = list(model.resources);
        state.model = model;
        state.buttons = [];
        if (title !== '') {
            setText(byId('api-title'), title);
            document.title = title;
        }
        if (isRelative(ramlFile)) {
            link.setAttribute('href', ramlFile);
            setText(link, ramlFile);
        }
        clear(resourceList);
        resources.forEach(function (resource) {
            resourceList.appendChild(resourceEntry(resource));
        });
        if (resources.length === 0) {
            resourceList.appendChild(make('p', null, 'No resources declared.'));
        }
    }

    // Requests api.json relative to the page and renders it; a non-2xx status, an unparsable body or
    // a failed request reports a load failure.
    function load() {
        request('GET', 'api.json', { 'Accept': 'application/json' }, null,
            function (status, statusText, lines, bodyText) {
                var model;
                if (status < 200 || status > 299) {
                    loadFailed(statusLine(status, statusText));
                    return;
                }
                try {
                    model = JSON.parse(bodyText);
                } catch (err) {
                    loadFailed(errorMessage(err));
                    return;
                }
                if (model === null || typeof model !== 'object' || Array.isArray(model)) {
                    loadFailed('the document is not a JSON object');
                    return;
                }
                try {
                    renderModel(model);
                } catch (renderError) {
                    loadFailed(errorMessage(renderError));
                }
            },
            loadFailed);
    }

    // Shows any HTTP response, 4xx and 5xx included; data-state 'done' is written last.
    function showResponse(status, statusText, lines, bodyText) {
        var statusElement = byId('response-status');
        setText(statusElement, statusLine(status, statusText));
        statusElement.setAttribute('data-status', String(status));
        setText(byId('response-headers'), list(lines).join('\n'));
        setText(byId('response-body'), bodyText);
        setResultState('done');
    }

    // Shows a request that received no response; data-state 'error' is written last.
    function showError(message) {
        var statusElement = byId('response-status');
        statusElement.removeAttribute('data-status');
        setText(statusElement, '');
        setText(byId('response-headers'), '');
        setText(byId('response-body'), message);
        setResultState('error');
    }

    // Sends the selected method with the current Try It values; completions of an older request are
    // ignored. Does nothing while no method is selected.
    function send() {
        var method = state.method;
        var headers = { 'Accept': '*/*' };
        var body = null;
        var bodies;
        var textarea;
        var url;
        var sequence;
        if (!method) {
            return;
        }
        state.sequence += 1;
        sequence = state.sequence;
        resetResult('pending');
        try {
            url = buildUrl();
        } catch (err) {
            showError(errorMessage(err));
            return;
        }
        setText(byId('try-it-url'), url);
        bodies = list(method.body);
        if (bodies.length > 0) {
            textarea = byId('try-it-body');
            body = textarea ? textarea.value : '';
            if (has(bodies[0].mediaType)) {
                headers['Content-Type'] = text(bodies[0].mediaType);
            }
        }
        request(verbOf(method), url, headers, body,
            function (status, statusText, lines, bodyText) {
                if (sequence === state.sequence) {
                    showResponse(status, statusText, lines, bodyText);
                }
            },
            function (message) {
                if (sequence === state.sequence) {
                    showError(message);
                }
            });
    }

    // Checks the page's element ids, binds the send button once and loads api.json.
    function start() {
        var missing = IDS.filter(function (id) {
            return !byId(id);
        });
        var box = byId('console-error');
        var message;
        if (missing.length > 0) {
            message = 'Console page is missing #' + missing.join(', #');
            if (box) {
                setText(box, message);
                box.style.display = 'block';
            }
            report(message);
            return;
        }
        byId('try-it-send').addEventListener('click', function () {
            send();
        });
        load();
    }

    start();
}(window, document));

