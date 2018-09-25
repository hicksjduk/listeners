package uk.org.thehickses.listeners;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.org.thehickses.channel.Channel;

/**
 * A chain of listeners for some arbitrary type of event.
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
        return new ListenerChain<>(defaultInvoker(), null);
    }

    /**
     * Creates a chain of listeners of a specified listener type, which notifies events of a specified event type
     * synchronously.
     * 
     * @param <L>
     *            The type of the listeners.
     * @param <E>
     *            The type of the events.
     * @param invoker
     *            an object that knows how to notify an event to a listener.
     * 
     * @return the chain.
     */
    public static <L, E> ListenerChain<L, E> newInstance(BiConsumer<L, E> invoker)
    {
        return new ListenerChain<>(invoker, null);
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
        return new ListenerChain<>(defaultInvoker(), executor(threadCount, null));
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
     * @param invoker
     *            an object that knows how to notify an event to a listener.
     * @param threadCount
     *            the number of threads to use.
     * 
     * @return the chain.
     */
    public static <L, E> ListenerChain<L, E> newInstance(BiConsumer<L, E> invoker, int threadCount)
    {
        return new ListenerChain<>(invoker, executor(threadCount, null));
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
        return new ListenerChain<>(defaultInvoker(), executor(threadCount, executor));
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
     * @param invoker
     *            an object that knows how to notify an event to a listener.
     * @param threadCount
     *            the number of threads to use. Note that if this exceeds the number of threads supported by the
     *            executor, the executor's limit takes precedence.
     * @param executor
     *            the executor. May be null, in which case asynchronous notifications are done by creating new threads.
     * 
     * @return the chain.
     */
    public static <L, E> ListenerChain<L, E> newInstance(BiConsumer<L, E> invoker, int threadCount,
            Executor executor)
    {
        return new ListenerChain<>(invoker, executor(threadCount, executor));
    }

    private static <E> BiConsumer<Listener<E>, E> defaultInvoker()
    {
        return (listener, event) -> listener.process(event);
    }

    private static Executor executor(int threadCount, Executor executor)
    {
        return threadCount > 0 ? runnable -> IntStream.range(0, threadCount).forEach(i -> {
            if (executor == null)
                new Thread(runnable).start();
            else
                executor.execute(runnable);
        }) : null;
    }

    private static <L, E> BiConsumer<L, E> faultLoggingInvoker(BiConsumer<L, E> invoker)
    {
        return (listener, event) -> {
            try
            {
                invoker.accept(listener, event);
            }
            catch (Throwable ex)
            {
                LOG.error("Unexpected error sending event {} to listener {}", event, listener, ex);
            }
        };
    }

    private ChainLink<L, E> chain = null;
    private final Set<L> listeners = new HashSet<>();
    private final BiConsumer<L, E> invoker;
    private final Executor executor;

    private ListenerChain(BiConsumer<L, E> invoker, Executor executor)
    {
        this.invoker = faultLoggingInvoker(Objects.requireNonNull(invoker));
        this.executor = executor;
    }

    /**
     * Fires an event to all registered listeners.
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
        AtomicInteger notFiredYet = new AtomicInteger(snapshot.listenerCount);
        BiConsumer<L, E> firer = (listener, evt) -> {
            ch.put(() -> invoker.accept(listener, evt));
            if (notFiredYet.decrementAndGet() == 0)
                ch.closeWhenEmpty();
        };
        fire(event, firer, snapshot);
    }

    private void fireSync(E event)
    {
        fire(event, invoker, new Snapshot<>(this));
    }

    /**
     * Registers the specified listener, if it is not already registered.
     * 
     * @param listener
     *            the listener.
     */
    public synchronized void addListener(L listener)
    {
        Objects.requireNonNull(listener);
        if (listeners.add(listener))
            chain = linkListener(chain, listener);
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
        if (listeners.remove(listener))
            chain = listeners.stream().reduce(null, this::linkListener,
                    (chain1, chain2) -> chain1 == null ? chain2
                            : chain2 == null ? chain1 : chain1.andThen(chain2));
    }

    private ChainLink<L, E> linkListener(ChainLink<L, E> chain, L listener)
    {
        ChainLink<L, E> link = (event, invoker) -> invoker.accept(listener, event);
        return chain == null ? link : chain.andThen(link);
    }

    private static interface ChainLink<L, E> extends BiConsumer<E, BiConsumer<L, E>>
    {
        default ChainLink<L, E> andThen(ChainLink<L, E> link)
        {
            return (event, firer) -> Stream.of(this, link).forEach(cl -> cl.accept(event, firer));
        }
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
