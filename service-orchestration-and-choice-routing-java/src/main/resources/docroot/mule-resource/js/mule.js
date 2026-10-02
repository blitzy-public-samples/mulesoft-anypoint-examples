/* Browser client for the AJAX channel endpoints of the order page (D-027, D-077). */
(function () {
  'use strict';

  /* Identifier of this page load, appended to the channel name of every RPC reply. */
  var clientId = Math.random().toString(36).slice(2) + Date.now().toString(36);

  /* Open subscriptions: one {channel, callback, source} record per subscribe call. */
  var subscriptions = [];

  /* Writes one console.error entry naming the channel and the error, when a console is present. */
  function reportFailure(channel, error) {
    if (window.console && typeof window.console.error === 'function') {
      window.console.error('mule.rpc: request to channel ' + channel + ' failed:', error);
    }
  }

  /*
   * POSTs data as text/plain to channel. When callback is a function, calls it
   * once with the single argument {channel: '<channel>#<clientId>', data: <response text>},
   * whatever the HTTP status. When the request or the body read fails, calls no
   * callback and reports the failure once. An error thrown by the callback rejects
   * only the last promise of the request chain. Returns undefined.
   */
  function rpc(channel, data, callback) {
    var replyChannel = channel + '#' + clientId;
    var init = {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      body: data == null ? '' : String(data)
    };

    window.fetch(channel, init)
      .then(function (response) {
        return response.text();
      })
      .then(function (text) {
        if (typeof callback === 'function') {
          callback({ channel: replyChannel, data: text });
        }
      }, function (error) {
        reportFailure(channel, error);
      });
  }

  /*
   * Opens a Server-Sent Events stream on channel and calls callback with
   * {channel: channel, data: <event data>} for every message event.
   */
  function subscribe(channel, callback) {
    var source = new window.EventSource(channel);

    source.addEventListener('message', function (event) {
      callback({ channel: channel, data: event.data });
    });
    subscriptions.push({ channel: channel, callback: callback, source: source });
  }

  /*
   * Closes the stream of the first subscription registered for this channel and
   * callback, and removes it. An unknown channel and callback pair changes nothing.
   */
  function unsubscribe(channel, callback) {
    var i;
    var record;

    for (i = 0; i < subscriptions.length; i += 1) {
      record = subscriptions[i];
      if (record.channel === channel && record.callback === callback) {
        record.source.close();
        subscriptions.splice(i, 1);
        return;
      }
    }
  }

  window.mule = {
    rpc: rpc,
    subscribe: subscribe,
    unsubscribe: unsubscribe
  };
}());
