package uk.org.thehickses.listeners;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
     * Creates a chain of listeners of the default listener type, which notifies events of a specified event type
     * synchronously.
     * 
     * @param <E>
     *            The type of the events.
     * @return the chain.
     */
    public static <E> Listeners<Listener<E>, E> newInstance()
    {
        return new Listeners<>(Listener::process, null);
    }

    /**
     * Creates a chain of listeners of a specified listener type, which notifies events of a specified event type
     * synchronously.
     * 
     * @param <L>
     *            The type of the listeners.
     * @param <E>
     *            The type of the events.
     * @param notifier
     *            an object that knows how to notify an event to a listener.
     * 
     * @return the chain.
     */
    public static <L, E> Listeners<L, E> newInstance(BiConsumer<L, E> notifier)
    {
        return new Listeners<>(notifier, null);
    }

    /**
     * Creates a chain of listeners of the default listener type, which notifies events of a specified event type. If
     * the specified thread count is greater than 0, the events are notified asynchronously by creating up to the
     * specified number of threads; otherwise they are notified synchronously.
     * 
     * @param <E>
     *            The type of the events.
     * @param threadCount
     *            the number of threads to use.
     * 
     * @return the chain.
     */
    public static <E> Listeners<Listener<E>, E> newInstance(int threadCount)
    {
        return new Listeners<>(Listener::process, executor(threadCount, null));
    }

    /**
     * Creates a chain of listeners of a specified listener type, which notifies events of a specified event type. If
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
     * @return the chain.
     */
    public static <L, E> Listeners<L, E> newInstance(BiConsumer<L, E> notifier, int threadCount)
    {
        return new Listeners<>(notifier, executor(threadCount, null));
    }

    /**
     * Creates a chain of listeners of the default listener type, which notifies events of a specified event type. If
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
     * @return the chain.
     */
    public static <E> Listeners<Listener<E>, E> newInstance(int threadCount, Executor executor)
    {
        return new Listeners<>(Listener::process, executor(threadCount, executor));
    }

    /**
     * Creates a chain of listeners of a specified listener type, which notifies events of a specified event type. If
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
     * @return the chain.
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

    private static <L, E> BiConsumer<L, E> faultLoggingNotifier(BiConsumer<L, E> notifier)
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

    private final Map<L, Predicate<E>> listeners = new HashMap<>();
    private final BiConsumer<L, E> notifier;
    private final BiConsumer<Integer, Runnable> executor;
    private final Predicate<E> acceptAllSelector = event -> true;

    private Listeners(BiConsumer<L, E> notifier, BiConsumer<Integer, Runnable> executor)
    {
        this.notifier = faultLoggingNotifier(Objects.requireNonNull(notifier));
        this.executor = executor;
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
        if (executor == null)
            fireSync(event);
        else
            fireAsync(event);
    }

    private void fire(E event, BiConsumer<L, E> firer, Collection<L> listeners)
    {
        if (listeners.isEmpty())
            return;
        listeners.forEach(listener -> firer.accept(listener, event));
    }

    private void fireAsync(E event)
    {
        Collection<L> listeners = listenersForEvent(event);
        if (listeners.isEmpty())
            return;
        int listenerCount = listeners.size();
        Channel<Runnable> ch = new Channel<>(listenerCount);
        executor.accept(listenerCount, () -> ch.range(Runnable::run));
        BiConsumer<L, E> firer = (listener, evt) -> ch.put(() -> notifier.accept(listener, evt));
        fire(event, firer, listeners);
        ch.closeWhenEmpty();
    }

    private void fireSync(E event)
    {
        fire(event, notifier, listenersForEvent(event));
    }

    /**
     * Registers the specified listener, if it is not already registered, or changes its selector. In either case, the
     * selector associated with the listener is set to one that selects all events.
     * 
     * @param listener
     *            the listener.
     */
    public synchronized void addOrUpdateListener(L listener)
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
    public synchronized void addOrUpdateListener(L listener, Predicate<E> selector)
    {
        Objects.requireNonNull(listener);
        listeners.put(listener, selector == null ? acceptAllSelector : selector);
    }

    /**
     * Unregisters the specified listener, if it is registered.
     * 
     * @param listener
     *            the listener.
     */
    public synchronized void removeListener(L listener)
    {
        Objects.requireNonNull(listener);
        listeners.remove(listener);
    }

    private synchronized Collection<L> listenersForEvent(E event)
    {
        return listeners
                .entrySet()
                .stream()
                .filter(e -> e.getValue().test(event))
                .map(e -> e.getKey())
                .collect(Collectors.toSet());
    }
}
