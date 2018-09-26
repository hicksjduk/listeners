package uk.org.thehickses.listeners;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
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
public class ListenerChain<L, E>
{
    private final static Logger LOG = LoggerFactory.getLogger(ListenerChain.class);

    /**
     * Creates a chain of listeners of the default listener type, which notifies events of a specified event type
     * synchronously.
     * 
     * @param <E>
     *            The type of the events.
     * @return the chain.
     */
    public static <E> ListenerChain<Listener<E>, E> newInstance()
    {
        return new ListenerChain<>(Listener::process, null);
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
    public static <L, E> ListenerChain<L, E> newInstance(BiConsumer<L, E> notifier)
    {
        return new ListenerChain<>(notifier, null);
    }

    /**
     * Creates a chain of listeners of the default listener type, which notifies events of a specified event type. If
     * the specified thread count is greater than 0, the events are notified asynchronously by creating the specified
     * number of threads; otherwise they are notified synchronously.
     * 
     * @param <E>
     *            The type of the events.
     * @param threadCount
     *            the number of threads to use.
     * 
     * @return the chain.
     */
    public static <E> ListenerChain<Listener<E>, E> newInstance(int threadCount)
    {
        return new ListenerChain<>(Listener::process, executor(threadCount, null));
    }

    /**
     * Creates a chain of listeners of a specified listener type, which notifies events of a specified event type. If
     * the specified thread count is greater than 0, the events are notified asynchronously by creating the specified
     * number of threads; otherwise they are notified synchronously.
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
    public static <L, E> ListenerChain<L, E> newInstance(BiConsumer<L, E> notifier, int threadCount)
    {
        return new ListenerChain<>(notifier, executor(threadCount, null));
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
    public static <E> ListenerChain<Listener<E>, E> newInstance(int threadCount, Executor executor)
    {
        return new ListenerChain<>(Listener::process, executor(threadCount, executor));
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
    public static <L, E> ListenerChain<L, E> newInstance(BiConsumer<L, E> notifier, int threadCount,
            Executor executor)
    {
        return new ListenerChain<>(notifier, executor(threadCount, executor));
    }

    private static Executor executor(int threadCount, Executor executor)
    {
        if (threadCount > 0)
            return runnable -> IntStream.range(0, threadCount).forEach(i -> {
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

    private ChainLink<L, E> chain = null;
    private final Map<L, Predicate<E>> listeners = new HashMap<>();
    private final BiConsumer<L, E> notifier;
    private final Executor executor;
    private final Predicate<E> acceptAllSelector = event -> true;

    private ListenerChain(BiConsumer<L, E> notifier, Executor executor)
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

    private void fire(E event, BiConsumer<L, E> firer, Snapshot<L, E> snapshot)
    {
        if (snapshot.chain == null)
            return;
        snapshot.chain.accept(event, firer);
    }

    private void fireAsync(E event)
    {
        Snapshot<L, E> snapshot = new Snapshot<>(this);
        if (snapshot.chain == null)
            return;
        Channel<Runnable> ch = new Channel<>(snapshot.listenerCount);
        executor.execute(() -> ch.range(Runnable::run));
        BiConsumer<L, E> firer = (listener, evt) -> ch.put(() -> notifier.accept(listener, evt));
        fire(event, firer, snapshot);
        ch.closeWhenEmpty();
    }

    private void fireSync(E event)
    {
        fire(event, notifier, new Snapshot<>(this));
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
        Predicate<E> newSelector = selector == null ? acceptAllSelector : selector;
        Predicate<E> oldSelector = listeners.put(listener, newSelector);
        if (oldSelector == null)
            chain = linkListener(chain, listener, newSelector);
        else if (!Objects.equals(oldSelector, newSelector))
            chain = rebuildChain();
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
        if (listeners.remove(listener) != null)
            chain = rebuildChain();
    }

    private synchronized ChainLink<L, E> rebuildChain()
    {
        return listeners.entrySet().stream().reduce(null, this::linkListener, this::linkChains);
    }

    private ChainLink<L, E> linkListener(ChainLink<L, E> chain,
            Entry<L, Predicate<E>> listenerAndSelector)
    {
        return linkListener(chain, listenerAndSelector.getKey(), listenerAndSelector.getValue());
    }

    private ChainLink<L, E> linkListener(ChainLink<L, E> chain, L listener, Predicate<E> selector)
    {
        ChainLink<L, E> link = (event, firer) -> {
            if (selector.test(event))
                firer.accept(listener, event);
        };
        return linkChains(chain, link);
    }

    private ChainLink<L, E> linkChains(ChainLink<L, E> chain1, ChainLink<L, E> chain2)
    {
        if (chain1 == null)
            return chain2;
        if (chain2 == null)
            return chain1;
        return (event, firer) -> {
            chain1.accept(event, firer);
            chain2.accept(event, firer);
        };
    }

    private static interface ChainLink<L, E> extends BiConsumer<E, BiConsumer<L, E>>
    {
    }

    private static class Snapshot<L, E>
    {
        public final ChainLink<L, E> chain;
        public final int listenerCount;

        private Snapshot(ListenerChain<L, E> lc)
        {
            synchronized (lc)
            {
                chain = lc.chain;
                listenerCount = lc.listeners.size();
            }
        }
    }
}
