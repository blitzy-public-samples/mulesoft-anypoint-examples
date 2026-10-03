/*
 * Interactive API console of the RAML contract (D-009).
 *
 * Loads the console model api.json, writes the API title, lists every resource with one button
 * per method, shows the request format and expected responses of the selected method, and sends
 * the Try It request with fetch, writing the response status, headers and body.
 *
 * Model shape read from api.json (any value may be null; a null array reads as empty):
 *   {title, basePath, resources: [{path, displayName, uriParameters: [parameter],
 *     methods: [{method, description, queryParameters: [parameter], request: [body],
 *                responses: [{status, description, bodies: [body]}]}]}]}
 *   parameter: {name, type, required, enumValues, minLength, maxLength, defaultValue, example}
 *   body: {mediaType, schema, example}
 */
(function () {
  'use strict';

  // Column labels of the parameter table in the details panel
  var PARAMETER_COLUMNS = ['Name', 'In', 'Type', 'Required', 'Enum values', 'Min length',
    'Max length', 'Default', 'Example'];

  // Returns '' for null or undefined, else the value as a string
  function text(value) {
    return value === null || value === undefined ? '' : String(value);
  }

  // Returns true when the value is neither null nor undefined
  function present(value) {
    return value !== null && value !== undefined;
  }

  // Creates an element, with a text node child when a text is given
  function el(tag, textValue) {
    var node = document.createElement(tag);
    if (arguments.length > 1) {
      node.appendChild(document.createTextNode(text(textValue)));
    }
    return node;
  }

  // Removes every child of the node
  function clear(node) {
    while (node.firstChild) {
      node.removeChild(node.firstChild);
    }
  }

  // Returns the value when it is an array, else an empty array
  function arr(value) {
    return Array.isArray(value) ? value : [];
  }

  // Calls fn with every object entry of a model array, in array order
  function each(list, fn) {
    var values = arr(list);
    for (var i = 0; i < values.length; i += 1) {
      if (values[i] !== null && typeof values[i] === 'object') {
        fn(values[i]);
      }
    }
  }

  // Returns the first entry of a model array when it is an object, else null
  function first(list) {
    var values = arr(list);
    return values.length > 0 && values[0] !== null && typeof values[0] === 'object' ? values[0] : null;
  }

  // Returns the upper-case method name of a model method
  function methodName(method) {
    return text(method.method).toUpperCase();
  }

  // Returns the message of an error, else its string form
  function errorText(err) {
    return err && err.message ? err.message : String(err);
  }

  // Returns the node, or throws when the console page lacks it
  function required(node, selector) {
    if (!node) {
      throw new Error('API console page element ' + selector + ' is missing');
    }
    return node;
  }

  // Console page elements (D-009)
  var titleNode = required(document.getElementById('api-title'), '#api-title');
  var resourcesNode = required(document.getElementById('resources'), '#resources');
  var detailsNode = required(document.getElementById('details'), '#details');
  var tryItNode = required(document.getElementById('try-it'), '#try-it');
  var paramsNode = required(document.querySelector('#try-it .try-it-params'),
    '#try-it .try-it-params');
  // Request body input and response body output of Try It (D-009)
  var requestBodyNode = required(document.getElementById('try-it-request-body'),
    '#try-it-request-body');
  var responseBodyNode = required(document.getElementById('try-it-body'), '#try-it-body');
  var sendNode = required(document.getElementById('try-it-send'), '#try-it-send');
  var statusNode = required(document.getElementById('try-it-status'), '#try-it-status');
  var headersNode = required(document.getElementById('try-it-headers'), '#try-it-headers');

  // Listener base path of the API, the selected resource and method, and the current request number
  var basePath = '';
  var selectedResource = null;
  var selectedMethod = null;
  var requestNumber = 0;

  // Returns the URI parameters of the resource and the query parameters of the method, in that order
  function parameterRows(resource, method) {
    var rows = [];
    each(resource.uriParameters, function (param) {
      rows.push({param: param, location: 'uri'});
    });
    each(method.queryParameters, function (param) {
      rows.push({param: param, location: 'query'});
    });
    return rows;
  }

  // Returns the Try It value of a parameter: its example, else its default, else ''
  function parameterValue(param) {
    if (present(param.example)) {
      return text(param.example);
    }
    if (present(param.defaultValue)) {
      return text(param.defaultValue);
    }
    return '';
  }

  // Appends a label and a pre element holding the text, when the text is present
  function appendBlock(container, label, value) {
    if (present(value)) {
      container.appendChild(el('p', label));
      container.appendChild(el('pre', value));
    }
  }

  // Returns a list of the bodies (media type, schema, example), or null when there is none
  function bodyList(list) {
    var listNode = el('ul');
    each(list, function (body) {
      var item = el('li');
      item.appendChild(el('p', body.mediaType));
      appendBlock(item, 'Schema', body.schema);
      appendBlock(item, 'Example', body.example);
      listNode.appendChild(item);
    });
    return listNode.firstChild ? listNode : null;
  }

  // Renders the parameter table of the details panel; nothing when there are no parameters
  function renderParameters(rows) {
    if (rows.length === 0) {
      return;
    }
    var table = el('table');
    var head = el('thead');
    var headRow = el('tr');
    PARAMETER_COLUMNS.forEach(function (label) {
      var cell = el('th', label);
      cell.setAttribute('scope', 'col');
      headRow.appendChild(cell);
    });
    head.appendChild(headRow);
    table.appendChild(head);
    var body = el('tbody');
    rows.forEach(function (row) {
      var param = row.param;
      var tableRow = el('tr');
      var nameCell = el('th', param.name);
      nameCell.setAttribute('scope', 'row');
      tableRow.appendChild(nameCell);
      [
        row.location,
        text(param.type),
        text(param.required),
        arr(param.enumValues).map(text).join(', '),
        text(param.minLength),
        text(param.maxLength),
        text(param.defaultValue),
        text(param.example)
      ].forEach(function (value) {
        tableRow.appendChild(el('td', value));
      });
      body.appendChild(tableRow);
    });
    table.appendChild(body);
    detailsNode.appendChild(el('h3', 'Parameters'));
    detailsNode.appendChild(table);
  }

  // Fills the details panel: heading, description, parameters, request format, expected responses
  function renderDetails(resource, method, rows) {
    clear(detailsNode);
    detailsNode.appendChild(el('h2', methodName(method) + ' ' + text(resource.path)));
    if (present(method.description)) {
      detailsNode.appendChild(el('p', method.description));
    }
    renderParameters(rows);

    detailsNode.appendChild(el('h3', 'Request format'));
    var requestBodies = bodyList(method.request);
    detailsNode.appendChild(requestBodies || el('p', 'none declared'));

    detailsNode.appendChild(el('h3', 'Expected responses'));
    var responseList = el('ul');
    each(method.responses, function (response) {
      var item = el('li');
      item.appendChild(el('h4', response.status));
      if (present(response.description)) {
        item.appendChild(el('p', response.description));
      }
      var responseBodies = bodyList(response.bodies);
      if (responseBodies) {
        item.appendChild(responseBodies);
      }
      responseList.appendChild(item);
    });
    if (responseList.firstChild) {
      detailsNode.appendChild(responseList);
    }
  }

  // Fills the Try It parameter inputs: one labelled text input per URI and query parameter
  function renderTryItParameters(rows) {
    clear(paramsNode);
    rows.forEach(function (row) {
      var field = el('div');
      var label = el('label');
      var input = el('input');
      label.appendChild(document.createTextNode(text(row.param.name) + ' '));
      input.setAttribute('type', 'text');
      input.setAttribute('data-param', text(row.param.name));
      input.setAttribute('data-in', row.location);
      input.value = parameterValue(row.param);
      label.appendChild(input);
      field.appendChild(label);
      paramsNode.appendChild(field);
    });
  }

  // Clears the Try It status, headers and response body outputs
  function clearOutputs() {
    statusNode.textContent = '';
    headersNode.textContent = '';
    responseBodyNode.textContent = '';
  }

  // Selects a method: fills the details panel and Try It, then shows both
  function select(resource, method) {
    var rows = parameterRows(resource, method);
    var firstRequest = first(method.request);
    selectedResource = resource;
    selectedMethod = method;
    requestNumber += 1;
    renderDetails(resource, method, rows);
    renderTryItParameters(rows);
    requestBodyNode.value = firstRequest ? text(firstRequest.example) : '';
    sendNode.textContent = methodName(method);
    clearOutputs();
    detailsNode.style.display = 'block';
    tryItNode.style.display = 'block';
  }

  // Returns the request URL: base path, resource path with URI parameters filled, query string
  function requestUrl() {
    var url = basePath + text(selectedResource.path);
    var uriInputs = paramsNode.querySelectorAll('input[data-in="uri"]');
    var queryInputs = paramsNode.querySelectorAll('input[data-in="query"]');
    var pairs = [];
    var i;
    for (i = 0; i < uriInputs.length; i += 1) {
      url = url.split('{' + text(uriInputs[i].getAttribute('data-param')) + '}')
        .join(encodeURIComponent(uriInputs[i].value));
    }
    for (i = 0; i < queryInputs.length; i += 1) {
      if (queryInputs[i].value !== '') {
        pairs.push(encodeURIComponent(text(queryInputs[i].getAttribute('data-param'))) + '='
          + encodeURIComponent(queryInputs[i].value));
      }
    }
    return pairs.length > 0 ? url + '?' + pairs.join('&') : url;
  }

  // Returns the fetch options: method, and the request body with its media type when one is entered
  function requestInit() {
    var init = {method: methodName(selectedMethod), headers: {}};
    var firstRequest = first(selectedMethod.request);
    var payload = requestBodyNode.value;
    if (firstRequest && payload !== '') {
      init.headers['Content-Type'] = text(firstRequest.mediaType);
      init.body = payload;
    }
    return init;
  }

  // Sends the Try It request and writes the response status, headers and body (D-009)
  function send() {
    if (!selectedResource || !selectedMethod) {
      return;
    }
    requestNumber += 1;
    var current = requestNumber;
    clearOutputs();
    fetch(requestUrl(), requestInit())
      .then(function (response) {
        if (current !== requestNumber) {
          return null;
        }
        var lines = [];
        statusNode.textContent = String(response.status);
        response.headers.forEach(function (value, name) {
          lines.push(name + ': ' + value);
        });
        headersNode.textContent = lines.join('\n');
        return response.text().then(function (body) {
          if (current === requestNumber) {
            responseBodyNode.textContent = text(body);
          }
        });
      })
      .then(null, function (err) {
        if (current === requestNumber) {
          responseBodyNode.textContent = errorText(err);
        }
      });
  }

  // Renders the API title and one heading and one button per method for every resource
  function render(model) {
    var data = model !== null && typeof model === 'object' ? model : {};
    basePath = text(data.basePath);
    titleNode.textContent = text(data.title);
    clear(resourcesNode);
    each(data.resources, function (resource) {
      var section = el('section');
      var heading = text(resource.path);
      if (present(resource.displayName)) {
        heading += ' ' + text(resource.displayName);
      }
      section.appendChild(el('h2', heading));
      // Renders one button per method, in model order
      each(resource.methods, function (method) {
        var name = methodName(method);
        var button = el('button', name);
        button.setAttribute('type', 'button');
        button.setAttribute('data-resource', text(resource.path));
        button.setAttribute('data-method', name);
        button.addEventListener('click', function () {
          select(resource, method);
        });
        section.appendChild(button);
      });
      resourcesNode.appendChild(section);
    });
  }

  // Writes the load failure text into the resource list
  function renderLoadError(err) {
    clear(resourcesNode);
    resourcesNode.appendChild(document.createTextNode(errorText(err)));
  }

  // Loads the console model api.json and renders it
  function load() {
    fetch('api.json')
      .then(function (response) {
        if (!response.ok) {
          throw new Error('api.json ' + response.status);
        }
        return response.json();
      })
      .then(render)
      .then(null, renderLoadError);
  }

  // Attaches the Try It send listener once and loads the console model
  sendNode.addEventListener('click', send);
  load();
})();
