/*
 * Interactive API console of the RAML contract netsuite-api.raml (D-009).
 *
 * Loads the console model api.json, writes the API title and version into #api-title and lists one
 * link per resource method in #resources. Selecting a method rebuilds #details (description,
 * request parameters and body, declared responses) and #try-it (parameter fields pre-filled from
 * the RAML defaults, a method button, and the status, headers and body of the sent request).
 *
 * Model shape read from api.json (a missing array reads as empty, a missing string as absent):
 *   {title, version, baseUri, resources: [{path, methods: [{method, description,
 *     queryParameters: [parameter], uriParameters: [parameter], body,
 *     responses: [{status, mediaType, schema, example, exampleFile}]}]}]}
 *   parameter: {name, description, type, enum, default}
 *   body: null, or {mediaType, schema, example}
 */
(function () {
  'use strict';

  // Sequence number of the latest selection or Try It request; older responses are dropped
  var requestSequence = 0;

  // Resource method links of #resources, in rendering order
  var operationLinks = [];

  // Returns the element with the given id, or null
  function byId(id) {
    return document.getElementById(id);
  }

  // Returns true when the value is neither null nor undefined
  function present(value) {
    return value !== null && value !== undefined;
  }

  // Returns the value as a string, or '' for null and undefined
  function text(value) {
    return present(value) ? String(value) : '';
  }

  // Returns true when the value converts to a non-empty string
  function filled(value) {
    return text(value) !== '';
  }

  // Returns the value when it is an array, else an empty array
  function list(value) {
    return Array.isArray(value) ? value : [];
  }

  // Returns the object entries of a model array in array order, skipping every other entry
  function entries(value) {
    var result = [];
    list(value).forEach(function (entry) {
      if (entry !== null && typeof entry === 'object') {
        result.push(entry);
      }
    });
    return result;
  }

  // Creates an element; a given value becomes its only child, as a text node
  function el(tag, value) {
    var node = document.createElement(tag);
    if (arguments.length > 1) {
      node.appendChild(document.createTextNode(text(value)));
    }
    return node;
  }

  // Appends the child to the parent and returns the child
  function add(parent, child) {
    parent.appendChild(child);
    return child;
  }

  // Removes every child of the node
  function clear(node) {
    while (node.firstChild) {
      node.removeChild(node.firstChild);
    }
  }

  // Returns the message of a thrown or rejected value as text
  function errorText(error) {
    if (!present(error)) {
      return 'Request failed';
    }
    return String(error.message || error);
  }

  // Returns raw response header lines with \n line ends and no trailing line end
  function headerText(raw) {
    return text(raw).replace(/\r\n?/g, '\n').replace(/\n+$/, '');
  }

  /*
   * Sends one HTTP request and calls callback exactly once with
   * {status: <number>, headers: <one "Name: value" line per header>, body: <response text>}.
   * Uses fetch when the window provides it, else XMLHttpRequest. A given accept value is sent as
   * the Accept header, the only request header set here; no body is sent. A request that fails
   * before a response arrives is reported with status 0.
   */
  function send(method, url, callback, accept) {
    var finished = false;
    var xhr;

    function finish(status, headers, body) {
      if (!finished) {
        finished = true;
        callback({status: status, headers: headers, body: body});
      }
    }

    if (typeof window.fetch === 'function') {
      window.fetch(url, {method: method, headers: accept ? {'Accept': accept} : {}}).then(
        function (response) {
          var lines = [];
          var status;
          try {
            status = response.status;
            response.headers.forEach(function (value, name) {
              lines.push(name + ': ' + value);
            });
            response.text().then(function (body) {
              finish(status, lines.join('\n'), text(body));
            }, function (error) {
              finish(0, '', errorText(error));
            });
          } catch (error) {
            finish(0, '', errorText(error));
          }
        },
        function (error) {
          finish(0, '', errorText(error));
        }
      );
      return;
    }

    xhr = new XMLHttpRequest();
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) {
        return;
      }
      if (xhr.status === 0) {
        finish(0, '', 'Network error');
      } else {
        finish(xhr.status, headerText(xhr.getAllResponseHeaders()), text(xhr.responseText));
      }
    };
    try {
      xhr.open(method, url, true);
      if (accept) {
        xhr.setRequestHeader('Accept', accept);
      }
      xhr.send(null);
    } catch (error) {
      finish(0, '', errorText(error));
    }
  }

  // Returns the upper-case HTTP method name of a model method
  function methodNameOf(method) {
    return text(method.method).toUpperCase();
  }

  // Returns the link id of a resource method: op-<method>-<non-empty path segments without braces>
  function operationId(methodName, path) {
    var segments = [];
    text(path).split('/').forEach(function (segment) {
      if (segment !== '') {
        segments.push(segment.replace(/[{}]/g, ''));
      }
    });
    return 'op-' + methodName.toLowerCase() + '-' + segments.join('-');
  }

  // Returns the enum values of a parameter as text, in model order
  function enumValues(parameter) {
    var values = [];
    list(parameter['enum']).forEach(function (value) {
      values.push(text(value));
    });
    return values;
  }

  // Returns the parameters of a model array that carry a name
  function namedParameters(parameters) {
    var result = [];
    entries(parameters).forEach(function (parameter) {
      if (filled(parameter.name)) {
        result.push(parameter);
      }
    });
    return result;
  }

  // Returns the media type of the first declared response, or '' when it declares none
  function firstMediaType(responses) {
    var first = list(responses)[0];
    if (first === null || typeof first !== 'object') {
      return '';
    }
    return text(first.mediaType);
  }

  // Returns the page path up to its last /console segment, or '' when there is none
  function apiBasePath() {
    var path = text(window.location.pathname);
    var index = path.lastIndexOf('/console');
    return index === -1 ? '' : path.substring(0, index);
  }

  /*
   * Returns the Try It request URL: the API base path, the resource path with each {name} replaced
   * by its encoded URI field value, and the non-empty query fields in model order.
   */
  function requestUrl(path, uriFields, queryFields) {
    var target = text(path);
    var query = [];
    uriFields.forEach(function (field) {
      target = target.split('{' + field.name + '}').join(encodeURIComponent(text(field.input.value)));
    });
    queryFields.forEach(function (field) {
      var value = text(field.input.value);
      if (value !== '') {
        query.push(encodeURIComponent(field.name) + '=' + encodeURIComponent(value));
      }
    });
    return apiBasePath() + target + (query.length > 0 ? '?' + query.join('&') : '');
  }

  // Appends a term and its description to a description list
  function addFact(facts, term, value) {
    add(facts, el('dt', term));
    add(facts, el('dd', value));
  }

  /*
   * Appends one titled list of parameters: name, then description, type, enum values and default
   * where present. Returns false and appends nothing when there is no named parameter.
   */
  function renderParameterList(parent, title, parameters) {
    var items = namedParameters(parameters);
    var parameterList;
    if (items.length === 0) {
      return false;
    }
    add(parent, el('h4', title));
    parameterList = add(parent, el('ul'));
    items.forEach(function (parameter) {
      var item = add(parameterList, el('li'));
      var facts = el('dl');
      var values = enumValues(parameter);
      add(item, el('code', parameter.name));
      if (filled(parameter.description)) {
        addFact(facts, 'Description', parameter.description);
      }
      if (filled(parameter.type)) {
        addFact(facts, 'Type', parameter.type);
      }
      if (values.length > 0) {
        addFact(facts, 'Enum values', values.join(', '));
      }
      if (present(parameter['default'])) {
        addFact(facts, 'Default', String(parameter['default']));
      }
      if (facts.firstChild) {
        add(item, facts);
      }
    });
    return true;
  }

  // Appends the request body of a method: its media type, schema and example, or No request body
  function renderBody(parent, body) {
    var facts;
    if (body === null || typeof body !== 'object') {
      add(parent, el('p', 'No request body'));
      return;
    }
    add(parent, el('h4', 'Body'));
    if (filled(body.mediaType)) {
      facts = add(parent, el('dl'));
      addFact(facts, 'Media type', body.mediaType);
    }
    if (filled(body.schema)) {
      add(parent, el('h5', 'Schema'));
      add(parent, el('pre', body.schema));
    }
    if (present(body.example)) {
      add(parent, el('h5', 'Example'));
      // Shows the example text verbatim (D-045).
      add(parent, el('pre', body.example));
    }
  }

  // Appends one entry per declared response: status, media type, schema, example and example file
  function renderResponses(parent, responses) {
    var items = entries(responses);
    var responseList;
    if (items.length === 0) {
      add(parent, el('p', 'No responses declared'));
      return;
    }
    responseList = add(parent, el('ul'));
    items.forEach(function (response) {
      var item = add(responseList, el('li'));
      var facts = add(item, el('dl'));
      var fileLine;
      var fileLink;
      addFact(facts, 'Status', response.status);
      if (filled(response.mediaType)) {
        addFact(facts, 'Media type', response.mediaType);
      }
      if (filled(response.schema)) {
        add(item, el('h4', 'Schema'));
        add(item, el('pre', response.schema));
      }
      if (present(response.example)) {
        add(item, el('h4', 'Example'));
        // Shows the example text verbatim (D-045).
        add(item, el('pre', response.example));
      }
      if (filled(response.exampleFile)) {
        fileLine = add(item, el('p', 'Example file: '));
        fileLink = add(fileLine, el('a', response.exampleFile));
        fileLink.setAttribute('href', text(response.exampleFile));
      }
    });
  }

  // Rebuilds the details panel: heading, description, request section and responses section
  function renderDetails(panel, resource, method) {
    var request;
    var responses;
    var hasQuery;
    var hasUri;
    add(panel, el('h2', methodNameOf(method) + ' ' + text(resource.path)));
    if (filled(method.description)) {
      add(panel, el('p', method.description));
    }

    request = add(panel, el('section'));
    add(request, el('h3', 'Request'));
    hasUri = renderParameterList(request, 'URI parameters', method.uriParameters);
    hasQuery = renderParameterList(request, 'Query parameters', method.queryParameters);
    if (!hasUri && !hasQuery) {
      add(request, el('p', 'No parameters'));
    }
    renderBody(request, method.body);

    responses = add(panel, el('section'));
    add(responses, el('h3', 'Responses'));
    renderResponses(responses, method.responses);
  }

  /*
   * Appends a label and a text input with the id <prefix><name> for one parameter. The value is
   * the parameter default when one exists; enum values are shown as placeholder and title.
   */
  function addField(panel, prefix, parameter) {
    var name = text(parameter.name);
    var id = prefix + name;
    var label = add(panel, el('label', name));
    var input = add(panel, el('input'));
    var values = enumValues(parameter);
    label.setAttribute('for', id);
    input.setAttribute('type', 'text');
    input.setAttribute('id', id);
    input.setAttribute('name', name);
    if (present(parameter['default'])) {
      input.setAttribute('value', String(parameter['default']));
      input.value = String(parameter['default']);
    }
    if (values.length > 0) {
      input.setAttribute('placeholder', values.join(', '));
      input.setAttribute('title', 'One of: ' + values.join(', '));
    }
    return input;
  }

  /*
   * Rebuilds the Try It section: one field per URI and query parameter, the method button and the
   * empty status, headers and body outputs. The button sends the request to the API path on the
   * page origin and writes the response into the outputs.
   */
  function renderTryIt(panel, resource, method) {
    var methodName = methodNameOf(method);
    var accept = firstMediaType(method.responses);
    var uriFields = [];
    var queryFields = [];
    var button;
    var statusLine;
    var status;
    var headers;
    var body;

    add(panel, el('h2', 'Try It'));
    namedParameters(method.uriParameters).forEach(function (parameter) {
      uriFields.push({name: text(parameter.name), input: addField(panel, 'uri-', parameter)});
    });
    namedParameters(method.queryParameters).forEach(function (parameter) {
      queryFields.push({name: text(parameter.name), input: addField(panel, 'param-', parameter)});
    });

    button = add(panel, el('button', methodName));
    button.setAttribute('type', 'button');
    button.setAttribute('id', 'send');

    add(panel, el('h3', 'Response'));
    statusLine = add(panel, el('p', 'Status: '));
    statusLine.setAttribute('aria-live', 'polite');
    status = add(statusLine, el('span'));
    status.setAttribute('id', 'response-status');
    add(panel, el('h4', 'Headers'));
    headers = add(panel, el('pre'));
    headers.setAttribute('id', 'response-headers');
    add(panel, el('h4', 'Body'));
    body = add(panel, el('pre'));
    body.setAttribute('id', 'response-body');

    button.addEventListener('click', function () {
      var sequence;
      requestSequence += 1;
      sequence = requestSequence;
      status.textContent = '';
      headers.textContent = '';
      body.textContent = '';
      send(methodName, requestUrl(resource.path, uriFields, queryFields), function (result) {
        if (sequence !== requestSequence) {
          return;
        }
        status.textContent = String(result.status);
        headers.textContent = result.headers;
        body.textContent = result.body;
      }, accept);
    });
  }

  // Opens a resource method: marks its link as current and rebuilds #details and #try-it
  function openMethod(resource, method, link) {
    var details = byId('details');
    var tryIt = byId('try-it');
    requestSequence += 1;
    operationLinks.forEach(function (other) {
      other.removeAttribute('aria-current');
    });
    link.setAttribute('aria-current', 'true');
    if (details) {
      clear(details);
      renderDetails(details, resource, method);
    }
    if (tryIt) {
      clear(tryIt);
      renderTryIt(tryIt, resource, method);
    }
  }

  // Replaces the content of #resources with one line naming the model failure
  function showModelError(reason) {
    var resources = byId('resources');
    var line;
    if (!resources) {
      return;
    }
    clear(resources);
    line = add(resources, el('p', 'Console model unavailable: ' + reason));
    line.setAttribute('role', 'alert');
  }

  /*
   * Renders the model: one block per resource with its path and one link per method in
   * #resources, then the title and version in #api-title. Every node is built before the page
   * changes; a model that fails to render throws and leaves the page unchanged.
   */
  function renderModel(model) {
    var resources = byId('resources');
    var title = byId('api-title');
    var fragment = document.createDocumentFragment();
    var links = [];
    var heading = [];

    entries(model.resources).forEach(function (resource) {
      var block = add(fragment, el('section'));
      var operations = el('ul');
      add(block, el('h2', resource.path));
      entries(resource.methods).forEach(function (method) {
        var methodName = methodNameOf(method);
        var item = add(operations, el('li'));
        var link = add(item, el('a', methodName + ' ' + text(resource.path)));
        link.setAttribute('href', '#');
        link.setAttribute('id', operationId(methodName, resource.path));
        link.addEventListener('click', function (event) {
          event.preventDefault();
          openMethod(resource, method, link);
        });
        links.push(link);
      });
      if (operations.firstChild) {
        add(block, operations);
      } else {
        add(block, el('p', 'No methods'));
      }
    });
    if (!fragment.firstChild) {
      add(fragment, el('p', 'No resources'));
    }
    if (filled(model.title)) {
      heading.push(text(model.title));
    }
    if (filled(model.version)) {
      heading.push(text(model.version));
    }

    if (resources) {
      clear(resources);
      resources.appendChild(fragment);
    }
    operationLinks = links;
    if (title && heading.length > 0) {
      title.textContent = heading.join(' ');
      document.title = heading.join(' ');
    }
  }

  // Loads api.json and renders it, or reports the HTTP status or an invalid model in #resources
  function init() {
    send('GET', 'api.json', function (result) {
      var model;
      if (result.status !== 200) {
        showModelError('HTTP ' + result.status);
        return;
      }
      try {
        model = JSON.parse(result.body);
      } catch (error) {
        showModelError('invalid model');
        return;
      }
      if (model === null || typeof model !== 'object' || Array.isArray(model)) {
        showModelError('invalid model');
        return;
      }
      try {
        renderModel(model);
      } catch (error) {
        showModelError('invalid model');
      }
    });
  }

  if (document.readyState !== 'loading') {
    init();
  } else {
    document.addEventListener('DOMContentLoaded', init);
  }
}());

