package uk.org.thehickses.listeners;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.org.thehickses.channel.Channel;

/**
 * A generic implementation of the Observer design pattern. Can be used to register listeners of any specified type, and
 * fire events of any specified type to the registered listeners. Firing of events can be done either synchronously or
 * asynchronously. A listener can register to receive all events (the default), or can specify a selector which selects
 * which events it should receive based on their contents.
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
        Objects.requireNonNull(notifier);
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
        return new Listeners<>(Listener::process, asyncRunner(threadCount, null));
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
        Objects.requireNonNull(notifier);
        return new Listeners<>(notifier, asyncRunner(threadCount, null));
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
        return new Listeners<>(Listener::process, asyncRunner(threadCount, executor));
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
        Objects.requireNonNull(notifier);
        return new Listeners<>(notifier, asyncRunner(threadCount, executor));
    }

    private static BiConsumer<Integer, Runnable> asyncRunner(int threadCount, Executor executor)
    {
        if (threadCount < 1)
            return null;
        Executor ex = executor != null ? executor : runnable -> new Thread(runnable).start();
        return (listenerCount, runnable) -> IntStream
                .range(0, Math.min(listenerCount, threadCount))
                .forEach(i -> ex.execute(runnable));
    }

    private static <L, E> BiConsumer<Collection<L>, E> syncFirer(BiConsumer<L, E> notifier)
    {
        return (listeners, event) -> listeners.forEach(firer(event, notifier));
    }

    private static <L, E> Consumer<L> firer(E event, BiConsumer<L, E> notifier)
    {
        return listener -> notifier.accept(listener, event);
    }

    private static <L, E> BiConsumer<Collection<L>, E> asyncFirer(BiConsumer<L, E> notifier,
            BiConsumer<Integer, Runnable> asyncRunner)
    {
        return (listeners, event) -> fire(listeners, firer(event, notifier), asyncRunner);
    }

    private static <L, E> void fire(Collection<L> listeners, Consumer<L> firer,
            BiConsumer<Integer, Runnable> asyncRunner)
    {
        int listenerCount = listeners.size();
        if (listenerCount == 0)
            return;
        Channel<L> ch = new Channel<>(listenerCount);
        asyncRunner.accept(listenerCount, () -> ch.range(firer));
        listeners.forEach(ch::put);
        ch.closeWhenEmpty();
    }

    private static <L, E> BiConsumer<L, E> withFaultLogging(BiConsumer<L, E> notifier)
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

    private final Map<L, Predicate<? super E>> listenersAndTheirSelectors = new ConcurrentHashMap<>();
    private final BiConsumer<Collection<L>, E> eventFirer;

    private Listeners(BiConsumer<L, E> notifier, BiConsumer<Integer, Runnable> asyncRunner)
    {
        notifier = withFaultLogging(notifier);
        this.eventFirer = asyncRunner == null ? syncFirer(notifier)
                : asyncFirer(notifier, asyncRunner);
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
    public void addOrUpdateListener(L listener, Predicate<? super E> selector)
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

    /**
     * Fires an event to every registered listener whose selector accepts it.
     * 
     * @param event
     *            the event.
     */
    public void fire(E event)
    {
        Objects.requireNonNull(event);
        eventFirer.accept(listenersForEvent(event), event);
    }

    private Collection<L> listenersForEvent(E event)
    {
        return listenersAndTheirSelectors
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue().test(event))
                .map(entry -> entry.getKey())
                .collect(Collectors.toSet());
    }
}
