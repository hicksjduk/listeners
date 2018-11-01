# Listeners

The `Listeners` class provides a generic implementation of the Observer design pattern.
It supports the notification of a single event object at a time to registered listeners; listeners and events
can be of any type. Notification can be either synchronous or asynchronous. Listeners can elect to receive all
events, or to limit those events they receive by specifying a selector which decides whether an event is of
interest to its associated listener.

## Creating a Listeners object

When creating a Listeners object, the user has the following choices:
* The listener type.
* The event type.
* Whether notification is to be synchronous or asynchronous; and if the latter, the maximum number of threads
to use, and whether to use an `Executor` (thread pool), or to create threads on the fly.

A Listeners object is created by calling the `newInstance` static factory method of Listeners, passing some,
all or none of the following parameters (in the order shown, where more than one is passed):
* `notifier` - an object which when invoked for a particular listener and event notifies the event to the listener. If this parameter is not specified, the default is that the supplied `Listener` interface is used as the listener type.
* `threadCount` - the maximum number of threads to use when notifying events asynchronously. If this is not
specified, or the specified value is less than 1, notification is synchronous; otherwise it is asynchronous.
* `executor` - an `Executor` to use when running asynchronous notification processes. If this is not specified,
or a null value is specified, a new thread is created to run every notification processor; otherwise
the processors are run using the specified executor. Note that it makes no sense to specify this parameter apart
from the `threadCount` parameter, and so the specification of `executor` without `threadCount` is not
supported; and if the specified `threadCount` is less than 1 the value of `executor` is ignored.

## Listeners and selectors

Each listener that is registered with a `Listeners` object, is associated with a selector, which determines, for any given event, whether the listener is interested in the event. The default
selector, which is used if no selector or a null selector is specified, selects
all events.  

## Registering and unregistering listeners 

A listener is registered with a `Listeners` object by calling its `addOrUpdateListener` instance method.
The first parameter to this method is the listener, which may optionally be followed by a selector. 
If the listener is not already registered, it is registered; otherwise it remains registered. In either case, its associated selector is set to the specified or defaulted selector.

A listener is unregistered by calling the `removeListener` instance method. If the listener is not registered,
this method has no effect; if it is, the listener ceases to be registered.

## Firing events

An event is fired to all registered and interested listeners by calling the `fire` instance method, which:
* Determines which of the registered listeners are interested in the event, by calling the selector associated with
each one.
* Notifies the event to all the registered and interested listeners.