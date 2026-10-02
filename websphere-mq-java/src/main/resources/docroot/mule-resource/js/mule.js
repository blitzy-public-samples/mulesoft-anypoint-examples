/*
 * Browser client for the AJAX channel endpoints of the WMQ example page
 * (D-027, D-077, D-142, D-143). Defines window.mule with rpc, subscribe and
 * unsubscribe. rpc sends an HTTP POST to the channel path; subscribe and
 * unsubscribe open and close a Server-Sent Events stream on the channel path.
 * Channel paths are passed to fetch and EventSource unchanged and resolve
 * against the origin of the page. window.fetch and window.EventSource are
 * looked up on every call.
 */
(function (window) {
    'use strict';

    /* EventSource.readyState value of a stream that has closed. */
    var EVENT_SOURCE_CLOSED = 2;

    /* Identifier of this page load, appended to the channel name of every RPC reply. */
    var clientId = Math.random().toString(36).slice(2);

    /* One {channel, callback, source} record per subscribed channel and callback pair. */
    var subscriptions = [];

    /*
     * Returns the index in subscriptions of the record registered for this
     * channel and callback, or -1 when no such record exists.
     */
    function indexOfSubscription(channel, callback) {
        var i;
        var record;

        for (i = 0; i < subscriptions.length; i += 1) {
            record = subscriptions[i];
            if (record.channel === channel && record.callback === callback) {
                return i;
            }
        }
        return -1;
    }

    /* Writes one console.error entry naming the channel and the error, when a console exists. */
    function reportFailure(channel, error) {
        var log = window.console;

        if (log && typeof log.error === 'function') {
            log.error('mule.rpc: request to channel ' + channel + ' failed:', error);
        }
    }

    /*
     * POSTs data as text/plain to channel: '' for null or undefined data,
     * String(data) otherwise. When callback is a function, calls it once with
     * the single argument {channel: '<channel>#<clientId>', data: <reply>}.
     * <reply> is the response text, whatever the HTTP status (D-077). When the
     * request or the body read fails, the failure is reported once with
     * console.error and <reply> is the error converted to a string ('' for an
     * absent error) (D-142). Without a function callback no callback is called.
     * An error thrown by the callback rejects only the last promise of the
     * request chain. Returns undefined.
     */
    function rpc(channel, data, callback) {
        var replyChannel = channel + '#' + clientId;

        window.fetch(channel, {
            method: 'POST',
            headers: {'Content-Type': 'text/plain;charset=UTF-8'},
            body: data == null ? '' : String(data)
        }).then(function (response) {
            return response.text();
        }).then(function (text) {
            if (typeof callback === 'function') {
                callback({channel: replyChannel, data: text});
            }
        }, function (error) {
            reportFailure(channel, error);
            if (typeof callback === 'function') {
                callback({channel: replyChannel, data: error == null ? '' : String(error)});
            }
        });
    }

    /*
     * When callback is a function, opens a Server-Sent Events stream on channel
     * and calls callback with {channel: channel, data: <event data>} for every
     * unnamed (message) event. A channel and callback pair that already holds an
     * open or connecting stream is left unchanged, and one whose stream has
     * closed gets a new stream. A callback that is not a function opens nothing
     * (D-143). Returns undefined.
     */
    function subscribe(channel, callback) {
        var index;
        var source;

        if (typeof callback !== 'function') {
            return;
        }
        index = indexOfSubscription(channel, callback);
        if (index !== -1) {
            if (subscriptions[index].source.readyState !== EVENT_SOURCE_CLOSED) {
                return;
            }
            subscriptions.splice(index, 1);
        }

        source = new window.EventSource(channel);
        source.onmessage = function (event) {
            callback({channel: channel, data: event.data});
        };
        subscriptions.push({channel: channel, callback: callback, source: source});
    }

    /*
     * Closes the stream of the subscription registered for this channel and
     * callback, and removes it. An unknown channel and callback pair changes
     * nothing. Returns undefined.
     */
    function unsubscribe(channel, callback) {
        var index = indexOfSubscription(channel, callback);

        if (index !== -1) {
            subscriptions[index].source.close();
            subscriptions.splice(index, 1);
        }
    }

    window.mule = {
        rpc: rpc,
        subscribe: subscribe,
        unsubscribe: unsubscribe
    };
}(window));
