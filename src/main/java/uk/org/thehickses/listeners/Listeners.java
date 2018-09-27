package uk.org.thehickses.listeners;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.org.thehickses.channel.Channel;

/**
 * A generic implementation of the Listener design pattern. Can be used to register listeners of any type, and fire
 * events of any type to all registered listeners. Firing of events can be done either synchronously or asynchronously,
 * and can be conditional or unconditional - a listener can register to receive all events, or just a subset based on
 * the event contents.
 * 
 * @author Jeremy Hicks
 *
 * @param <L>
 *            The type of the listeners.
 * @param <E>
 *            The type of the events.
 */
public class Listeners<L, E>
{
    private final static Logger LOG = LoggerFactory.getLogger(Listeners.class);

    /**
     * Creates an instance which synchronously notifies events of a specified event type to listeners of the default
     * listener type.
     * 
     * @param <E>
     *            The type of the events.
     * @return the instance.
     */
    public static <E> Listeners<Listener<E>, E> newInstance()
    {
        return new Listeners<>(Listener::process, null);
    }

    /**
     * Creates an instance which synchronously notifies events of a specified event type to listeners of a specified
     * listener type.
     * 
     * @param <L>
     *            The type of the listeners.
     * @param <E>
     *            The type of the events.
     * @param notifier
     *            an object that knows how to notify an event to a listener.
     * 
     * @return the instance.
     */
    public static <L, E> Listeners<L, E> newInstance(BiConsumer<L, E> notifier)
    {
        return new Listeners<>(notifier, null);
    }

    /**
     * Creates an instance which notifies events of a specified event type to listeners of the default listener type. If
     * the specified thread count is greater than 0, the events are notified asynchronously by creating up to the
     * specified number of threads; otherwise they are notified synchronously.
     * 
     * @param <E>
     *            The type of the events.
     * @param threadCount
     *            the number of threads to use.
     * 
     * @return the instance.
     */
    public static <E> Listeners<Listener<E>, E> newInstance(int threadCount)
    {
        return new Listeners<>(Listener::process, executor(threadCount, null));
    }

    /**
     * Creates an instance which notifies events of a specified event type to listeners of a specified listener type. If
     * the specified thread count is greater than 0, the events are notified asynchronously by creating up to the
     * specified number of threads; otherwise they are notified synchronously.
     * 
     * @param <L>
     *            The type of the listeners.
     * @param <E>
     *            The type of the events.
     * @param notifier
     *            an object that knows how to notify an event to a listener.
     * @param threadCount
     *            the number of threads to use.
     * 
     * @return the instance.
     */
    public static <L, E> Listeners<L, E> newInstance(BiConsumer<L, E> notifier, int threadCount)
    {
        return new Listeners<>(notifier, executor(threadCount, null));
    }

    /**
     * Creates an instance which notifies events of a specified event type to listeners of the default listener type. If
     * the specified thread count is greater than 0, the events are notified asynchronously, otherwise synchronously. If
     * they are notified asynchronously and the specified executor is not null, the threads used are taken from the
     * executor rather than creating new ones.
     * 
     * @param <E>
     *            The type of the events.
     * @param threadCount
     *            the number of threads to use. Note that if this exceeds the number of threads supported by the
     *            executor, the executor's limit takes precedence.
     * @param executor
     *            the executor. May be null, in which case asynchronous notifications are done by creating new threads.
     * 
     * @return the instance.
     */
    public static <E> Listeners<Listener<E>, E> newInstance(int threadCount, Executor executor)
    {
        return new Listeners<>(Listener::process, executor(threadCount, executor));
    }

    /**
     * Creates an instance which notifies events of a specified event type to listeners of a specified listener type. If
     * the specified thread count is greater than 0, the events are notified asynchronously, otherwise synchronously. If
     * they are notified asynchronously and the specified executor is not null, the threads used are taken from the
     * executor rather than creating new ones.
     * 
     * @param <L>
     *            The type of the listeners.
     * @param <E>
     *            The type of the events.
     * @param notifier
     *            an object that knows how to notify an event to a listener.
     * @param threadCount
     *            the number of threads to use. Note that if this exceeds the number of threads supported by the
     *            executor, the executor's limit takes precedence.
     * @param executor
     *            the executor. May be null, in which case asynchronous notifications are done by creating new threads.
     * 
     * @return the instance.
     */
    public static <L, E> Listeners<L, E> newInstance(BiConsumer<L, E> notifier, int threadCount,
            Executor executor)
    {
        return new Listeners<>(notifier, executor(threadCount, executor));
    }

    private static BiConsumer<Integer, Runnable> executor(int threadCount, Executor executor)
    {
        if (threadCount > 0)
            return (listenerCount, runnable) -> IntStream
                    .range(0, Math.min(listenerCount, threadCount))
                    .forEach(i -> {
                        if (executor == null)
                            new Thread(runnable).start();
                        else
                            executor.execute(runnable);
                    });
        return null;
    }

    private final Map<L, Predicate<E>> listenersAndTheirSelectors = new ConcurrentHashMap<>();
    private final BiConsumer<Collection<L>, E> firer;

    private Listeners(BiConsumer<L, E> notifier, BiConsumer<Integer, Runnable> executor)
    {
        BiConsumer<L, E> wrappedNotifier = faultLoggingNotifier(Objects.requireNonNull(notifier));
        this.firer = executor == null ? syncFirer(wrappedNotifier)
                : asyncFirer(wrappedNotifier, executor);
    }

    private BiConsumer<L, E> faultLoggingNotifier(BiConsumer<L, E> notifier)
    {
        return (listener, event) -> {
            try
            {
                notifier.accept(listener, event);
            }
            catch (Throwable ex)
            {
                LOG.error("Unexpected error sending event {} to listener {}", event, listener, ex);
            }
        };
    }

    /**
     * Fires an event to all registered listeners whose selector accepts it.
     * 
     * @param event
     *            the event.
     */
    public void fire(E event)
    {
        Objects.requireNonNull(event);
        firer.accept(listenersForEvent(event), event);
    }

    private BiConsumer<Collection<L>, E> syncFirer(BiConsumer<L, E> notifier)
    {
        return (listeners, event) -> fire(listeners, event, notifier);
    }

    private void fire(Collection<L> listeners, E event, BiConsumer<L, E> firer)
    {
        if (listeners.isEmpty())
            return;
        listeners.forEach(listener -> firer.accept(listener, event));
    }

    private BiConsumer<Collection<L>, E> asyncFirer(BiConsumer<L, E> notifier,
            BiConsumer<Integer, Runnable> executor)
    {
        return (listeners, event) -> fire(listeners, event, notifier, executor);
    }

    private void fire(Collection<L> listeners, E event, BiConsumer<L, E> notifier,
            BiConsumer<Integer, Runnable> executor)
    {
        if (listeners.isEmpty())
            return;
        int listenerCount = listeners.size();
        Channel<Runnable> ch = new Channel<>(listenerCount);
        executor.accept(listenerCount, () -> ch.range(Runnable::run));
        BiConsumer<L, E> firer = (listener, evt) -> ch.put(() -> notifier.accept(listener, evt));
        fire(listeners, event, firer);
        ch.closeWhenEmpty();
    }

    /**
     * Registers the specified listener, if it is not already registered, or changes its selector. In either case, the
     * selector associated with the listener is set to one that selects all events.
     * 
     * @param listener
     *            the listener.
     */
    public void addOrUpdateListener(L listener)
    {
        addOrUpdateListener(listener, null);
    }

    /**
     * Registers the specified listener, if it is not already registered, or changes its selector. In either case, the
     * selector associated with the listener is set to the specified selector, or to one that selects all events if the
     * selector is null.
     * 
     * @param listener
     *            the listener.
     * @param selector
     *            the selector. May be null, in which case a selector is used that selects all events.
     */
    public void addOrUpdateListener(L listener, Predicate<E> selector)
    {
        Objects.requireNonNull(listener);
        listenersAndTheirSelectors.put(listener, selector == null ? event -> true : selector);
    }

    /**
     * Unregisters the specified listener, if it is registered.
     * 
     * @param listener
     *            the listener.
     */
    public void removeListener(L listener)
    {
        Objects.requireNonNull(listener);
        listenersAndTheirSelectors.remove(listener);
    }

    private Collection<L> listenersForEvent(E event)
    {
        return listenersAndTheirSelectors
                .entrySet()
                .stream()
                .filter(e -> e.getValue().test(event))
                .map(e -> e.getKey())
                .collect(Collectors.toSet());
    }
}
