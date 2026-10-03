/* Interactive API console (D-009): renders api.json and sends Try It requests. */
(function () {
  'use strict';

  /* Console model parsed from api.json. */
  var model = null;

  /* Resource and method of the current selection; null until a method is selected. */
  var selectedResource = null;
  var selectedMethod = null;

  /* Number of the latest Try It request or selection; a response carrying an older number is not shown. */
  var requestSequence = 0;

  /* ---- DOM helpers ---------------------------------------------------- */

  /* Element with the given id, or null. */
  function byId(id) {
    return document.getElementById(id);
  }

  /* Removes every child of node. */
  function clear(node) {
    while (node.firstChild) {
      node.removeChild(node.firstChild);
    }
  }

  /* True for a value that is neither null nor undefined. */
  function present(value) {
    return value !== null && value !== undefined;
  }

  /* String form of value, or the empty string for null and undefined. */
  function textOf(value) {
    return present(value) ? String(value) : '';
  }

  /* Writes value as the text content of node; null and undefined write the empty string. */
  function setText(node, value) {
    node.textContent = textOf(value);
  }

  /* New tag element with the given className (when given) and a text node of text (when text is set). */
  function element(tag, className, text) {
    var node = document.createElement(tag);
    if (present(className) && className !== '') {
      node.className = className;
    }
    if (present(text)) {
      node.appendChild(document.createTextNode(String(text)));
    }
    return node;
  }

  /* A pre element holding text as one text node. */
  function preBlock(text) {
    return element('pre', null, textOf(text));
  }

  /* value when it is an array, otherwise an empty array. */
  function list(value) {
    return Array.isArray(value) ? value : [];
  }

  /* Upper-case HTTP method name of a model method entry. */
  function methodName(method) {
    return textOf(method.method).toUpperCase();
  }

  /* ---- HTTP ----------------------------------------------------------- */

  /* HTTP helper: fetch when available, otherwise XMLHttpRequest (D-535).
   * Calls callback(error, result) exactly once; result is {status: number, headers: string, body: string}. */
  function send(method, url, headers, body, callback) {
    var done = false;

    function finish(error, result) {
      if (done) {
        return;
      }
      done = true;
      callback(error, result);
    }

    if (typeof window.fetch === 'function') {
      var promise;
      try {
        promise = window.fetch(url, {
          method: method,
          headers: headers,
          body: body === null ? undefined : body,
          credentials: 'same-origin'
        }).then(function (response) {
          var lines = [];
          response.headers.forEach(function (value, name) {
            lines.push(name + ': ' + value);
          });
          return response.text().then(function (text) {
            finish(null, {status: response.status, headers: lines.join('\n'), body: text});
          });
        });
      } catch (error) {
        finish(error, null);
        return;
      }
      promise['catch'](function (error) {
        finish(error, null);
      });
      return;
    }

    var xhr = new XMLHttpRequest();
    try {
      xhr.open(method, url, true);
      for (var name in headers) {
        if (headers.hasOwnProperty(name)) {
          xhr.setRequestHeader(name, headers[name]);
        }
      }
      xhr.onreadystatechange = function () {
        if (xhr.readyState !== 4) {
          return;
        }
        if (xhr.status === 0) {
          finish(new Error('Network error'), null);
          return;
        }
        finish(null, {status: xhr.status, headers: xhr.getAllResponseHeaders(), body: xhr.responseText});
      };
      xhr.onerror = function () {
        finish(new Error('Network error'), null);
      };
      xhr.send(body === null ? null : body);
    } catch (error) {
      finish(error, null);
    }
  }

  /* ---- Resource list -------------------------------------------------- */

  /* Writes the api.json load failure into #resources. */
  function showLoadError(detail) {
    setText(byId('resources'), 'Unable to load api.json: ' + detail);
  }

  /* Click listener that selects method of resource. */
  function selectHandler(resource, method) {
    return function () {
      select(resource, method);
    };
  }

  /* div.resource of one model resource with one button.method per method. */
  function resourceBlock(resource) {
    var path = textOf(resource.path);
    var block = element('div', 'resource', null);
    block.setAttribute('data-path', path);
    block.appendChild(element('span', 'resource-path', path));
    var methods = list(resource.methods);
    for (var i = 0; i < methods.length; i++) {
      var method = methods[i];
      if (!method || typeof method !== 'object') {
        continue;
      }
      var name = methodName(method);
      var button = element('button', 'method', name);
      button.setAttribute('type', 'button');
      button.setAttribute('data-path', path);
      button.setAttribute('data-method', name);
      button.addEventListener('click', selectHandler(resource, method), false);
      block.appendChild(button);
    }
    return block;
  }

  /* Writes the model title into #console-title and one div.resource per resource into #resources, in model order. */
  function render() {
    if (present(model.title)) {
      setText(byId('console-title'), model.title);
    }
    var container = byId('resources');
    clear(container);
    var resources = list(model.resources);
    for (var i = 0; i < resources.length; i++) {
      var resource = resources[i];
      if (resource && typeof resource === 'object') {
        container.appendChild(resourceBlock(resource));
      }
    }
  }

  /* ---- Details panel -------------------------------------------------- */

  /* Description line of a parameter: its name, then type, required, enum, default, minLength, maxLength and example when set. */
  function parameterText(parameter) {
    var parts = [textOf(parameter.name)];
    if (present(parameter.type)) {
      parts.push('type: ' + String(parameter.type));
    }
    if (present(parameter.required)) {
      parts.push('required: ' + (parameter.required === true ? 'true' : 'false'));
    }
    var values = parameter['enum'];
    if (Array.isArray(values)) {
      if (values.length > 0) {
        parts.push('enum: ' + values.map(textOf).join(' | '));
      }
    } else if (present(values)) {
      parts.push('enum: ' + String(values));
    }
    if (present(parameter['default'])) {
      parts.push('default: ' + String(parameter['default']));
    }
    if (present(parameter.minLength)) {
      parts.push('minLength: ' + String(parameter.minLength));
    }
    if (present(parameter.maxLength)) {
      parts.push('maxLength: ' + String(parameter.maxLength));
    }
    if (present(parameter.example)) {
      parts.push('example: ' + String(parameter.example));
    }
    return parts.join(', ');
  }

  /* Appends an h4 heading and one div.parameter per parameter entry to target. */
  function appendParameters(target, heading, parameters) {
    target.appendChild(element('h4', null, heading));
    for (var i = 0; i < parameters.length; i++) {
      if (parameters[i] && typeof parameters[i] === 'object') {
        target.appendChild(element('div', 'parameter', parameterText(parameters[i])));
      }
    }
  }

  /* Appends the div.media-type of a body entry to target, then its schema and its example, each when set, under a label. */
  function appendBody(target, body) {
    if (!body || typeof body !== 'object') {
      return;
    }
    target.appendChild(element('div', 'media-type', textOf(body.mediaType)));
    if (present(body.schema)) {
      target.appendChild(element('div', 'body-label', 'Schema'));
      target.appendChild(preBlock(body.schema));
    }
    if (present(body.example)) {
      target.appendChild(element('div', 'body-label', 'Example'));
      target.appendChild(preBlock(body.example));
    }
  }

  /* Writes the description, URI and query parameters and request bodies of the selection into #request-format. */
  function renderRequestFormat(resource, method) {
    var target = byId('request-format');
    clear(target);
    if (present(method.description)) {
      target.appendChild(element('p', 'method-description', method.description));
    }
    var uriParameters = list(resource.uriParameters);
    if (uriParameters.length > 0) {
      appendParameters(target, 'URI parameters', uriParameters);
    }
    var queryParameters = list(method.queryParameters);
    if (queryParameters.length > 0) {
      appendParameters(target, 'Query parameters', queryParameters);
    }
    var bodies = list(method.body);
    if (bodies.length > 0) {
      target.appendChild(element('h4', null, 'Body'));
      for (var i = 0; i < bodies.length; i++) {
        appendBody(target, bodies[i]);
      }
    } else {
      target.appendChild(element('p', 'no-body', 'No request body'));
    }
  }

  /* Writes one div.response per declared response of method into #responses. */
  function renderResponses(method) {
    var target = byId('responses');
    clear(target);
    var responses = list(method.responses);
    for (var i = 0; i < responses.length; i++) {
      var response = responses[i];
      if (!response || typeof response !== 'object') {
        continue;
      }
      var block = element('div', 'response', null);
      block.appendChild(element('div', 'response-status', textOf(response.status)));
      if (present(response.description)) {
        block.appendChild(element('p', 'response-description', response.description));
      }
      var bodies = list(response.body);
      if (bodies.length > 0) {
        for (var j = 0; j < bodies.length; j++) {
          appendBody(block, bodies[j]);
        }
      } else {
        block.appendChild(element('p', 'no-body', 'No response body'));
      }
      target.appendChild(block);
    }
  }

  /* div.try-it-param holding a label and a text input for one parameter; place is 'uri' or 'query'. */
  function parameterField(parameter, place, index) {
    var id = 'try-it-param-' + index;
    var name = textOf(parameter.name);
    var field = element('div', 'try-it-param', null);
    var label = element('label', null, name + (parameter.required === true ? ' *' : ''));
    label.setAttribute('for', id);
    var input = element('input', null, null);
    input.setAttribute('type', 'text');
    input.setAttribute('id', id);
    input.setAttribute('data-param', name);
    input.setAttribute('data-location', place);
    if (present(parameter.example)) {
      input.value = String(parameter.example);
    } else if (present(parameter['default'])) {
      input.value = String(parameter['default']);
    } else {
      input.value = '';
    }
    field.appendChild(label);
    field.appendChild(input);
    return field;
  }

  /* Empties the Try It status, headers and body outputs. */
  function clearOutputs() {
    setText(byId('try-it-status'), '');
    setText(byId('try-it-headers'), '');
    setText(byId('try-it-body'), '');
  }

  /* Builds the Try It parameter inputs, the request body field and the send button label of the selection. */
  function renderTryIt(resource, method, name) {
    var params = byId('try-it-params');
    var request = byId('try-it-request');
    clear(params);
    clear(request);
    clearOutputs();
    var index = 0;
    var uriParameters = list(resource.uriParameters);
    for (var i = 0; i < uriParameters.length; i++) {
      if (uriParameters[i] && typeof uriParameters[i] === 'object') {
        params.appendChild(parameterField(uriParameters[i], 'uri', index));
        index++;
      }
    }
    var queryParameters = list(method.queryParameters);
    for (var j = 0; j < queryParameters.length; j++) {
      if (queryParameters[j] && typeof queryParameters[j] === 'object') {
        params.appendChild(parameterField(queryParameters[j], 'query', index));
        index++;
      }
    }
    var bodies = list(method.body);
    if (bodies.length > 0) {
      var first = bodies[0] && typeof bodies[0] === 'object' ? bodies[0] : {};
      var label = element('label', null, textOf(first.mediaType));
      label.setAttribute('for', 'try-it-request-body');
      var area = element('textarea', null, null);
      area.setAttribute('id', 'try-it-request-body');
      area.setAttribute('rows', '10');
      area.setAttribute('cols', '80');
      area.value = textOf(first.example);
      request.appendChild(label);
      request.appendChild(area);
    }
    setText(byId('try-it-send'), name);
  }

  /* Shows the details panel of method on resource and makes it the current selection. */
  function select(resource, method) {
    selectedResource = resource;
    selectedMethod = method;
    requestSequence++;
    var name = methodName(method);
    byId('details').style.display = 'block';
    setText(byId('details-title'), name + ' ' + textOf(resource.path));
    renderRequestFormat(resource, method);
    renderResponses(method);
    renderTryIt(resource, method, name);
  }

  /* ---- Try It --------------------------------------------------------- */

  /* Value of the first Try It input with the given data-location and data-param, or the empty string. */
  function inputValue(inputs, place, name) {
    for (var i = 0; i < inputs.length; i++) {
      if (inputs[i].getAttribute('data-location') === place && inputs[i].getAttribute('data-param') === name) {
        return inputs[i].value;
      }
    }
    return '';
  }

  /* Sends the selected method to /api on the page's origin and writes the status, headers and body. */
  function tryIt() {
    if (!selectedResource || !selectedMethod) {
      return;
    }
    var inputs = byId('try-it-params').getElementsByTagName('input');
    var path = textOf(selectedResource.path).replace(/\{([^}]+)\}/g, function (match, name) {
      return encodeURIComponent(inputValue(inputs, 'uri', name));
    });
    var query = [];
    for (var i = 0; i < inputs.length; i++) {
      var input = inputs[i];
      if (input.getAttribute('data-location') === 'query' && input.value !== '') {
        query.push(encodeURIComponent(input.getAttribute('data-param')) + '=' + encodeURIComponent(input.value));
      }
    }
    var queryString = query.join('&');
    var url = location.protocol + '//' + location.host + '/api' + path + (queryString ? '?' + queryString : '');

    var headers = {};
    var body = null;
    var bodies = list(selectedMethod.body);
    if (bodies.length > 0) {
      var first = bodies[0] && typeof bodies[0] === 'object' ? bodies[0] : {};
      if (present(first.mediaType)) {
        headers['Content-Type'] = String(first.mediaType);
      }
      var area = byId('try-it-request-body');
      body = area ? area.value : '';
    }

    clearOutputs();
    requestSequence++;
    var sequence = requestSequence;
    send(methodName(selectedMethod), url, headers, body, function (error, result) {
      if (sequence !== requestSequence) {
        return;
      }
      if (error) {
        setText(byId('try-it-body'), error.message || String(error));
        return;
      }
      setText(byId('try-it-status'), String(result.status));
      setText(byId('try-it-headers'), result.headers);
      setText(byId('try-it-body'), result.body);
    });
  }

  /* ---- Start-up ------------------------------------------------------- */

  /* Binds the Try It button once, then loads api.json and renders the resource list. */
  function init() {
    var sendButton = byId('try-it-send');
    if (sendButton) {
      sendButton.addEventListener('click', function () {
        tryIt();
      }, false);
    }
    send('GET', 'api.json', {}, null, function (error, result) {
      if (error) {
        showLoadError(error.message || String(error));
        return;
      }
      if (result.status !== 200) {
        showLoadError(String(result.status));
        return;
      }
      var parsed;
      try {
        parsed = JSON.parse(result.body);
      } catch (parseError) {
        showLoadError(parseError.message || String(parseError));
        return;
      }
      if (!parsed || typeof parsed !== 'object') {
        showLoadError('not a JSON object');
        return;
      }
      model = parsed;
      render();
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init, false);
  } else {
    init();
  }
}());
