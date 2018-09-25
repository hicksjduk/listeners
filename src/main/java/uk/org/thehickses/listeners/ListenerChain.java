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

public class ListenerChain<L, E>
{
    private final static Logger LOG = LoggerFactory.getLogger(ListenerChain.class);

    public static <E> ListenerChain<Listener<E>, E> newInstance()
    {
        return new ListenerChain<>((listener, event) -> listener.process(event), 0, null);
    }

    public static <L, E> ListenerChain<L, E> newInstance(BiConsumer<L, E> invoker)
    {
        return new ListenerChain<>(invoker, 0, null);
    }

    public static <E> ListenerChain<Listener<E>, E> newInstance(int threadCount)
    {
        return new ListenerChain<>((listener, event) -> listener.process(event), threadCount, null);
    }

    public static <L, E> ListenerChain<L, E> newInstance(BiConsumer<L, E> invoker, int threadCount)
    {
        return new ListenerChain<>(invoker, threadCount, null);
    }

    public static <E> ListenerChain<Listener<E>, E> newInstance(int threadCount, Executor executor)
    {
        return new ListenerChain<>((listener, event) -> listener.process(event), threadCount,
                executor);
    }

    public static <L, E> ListenerChain<L, E> newInstance(BiConsumer<L, E> invoker, int threadCount,
            Executor executor)
    {
        return new ListenerChain<>(invoker, threadCount, executor);
    }

    private ChainLink<L, E> chain = null;
    private final Set<L> listeners = new HashSet<>();
    private final BiConsumer<L, E> invoker;
    private final Executor executor;

    private ListenerChain(BiConsumer<L, E> invoker, int threadCount, Executor executor)
    {
        this.invoker = wrappedInvoker(Objects.requireNonNull(invoker));
        this.executor = threadCount == 0 ? null : threadRunner(threadCount, executor);
    }

    private static <L, E> BiConsumer<L, E> wrappedInvoker(BiConsumer<L, E> invoker)
    {
        return (l, e) -> {
            try
            {
                invoker.accept(l, e);
            }
            catch (Throwable ex)
            {
                LOG.error("Unexpected error sending event {} to listener {}", e, l, ex);
            }
        };
    }

    private static Executor threadRunner(int threadCount, Executor executor)
    {
        Executor ex = executor == null ? r -> new Thread(r).start() : executor;
        return r -> IntStream.range(0, threadCount).forEach(i -> ex.execute(r));
    }

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
        Channel<Runnable> ch = new Channel<>(snapshot.listenerCount);
        executor.execute(() -> ch.range(Runnable::run));
        AtomicInteger notFiredYet = new AtomicInteger(snapshot.listenerCount);
        BiConsumer<L, E> firer = (l, e) -> {
            ch.put(() -> invoker.accept(l, e));
            if (notFiredYet.decrementAndGet() == 0)
                ch.closeWhenEmpty();
        };
        fire(event, firer, snapshot);
    }

    private void fireSync(E event)
    {
        fire(event, invoker, new Snapshot<>(this));
    }

    public synchronized void addListener(L listener)
    {
        Objects.requireNonNull(listener);
        if (listeners.add(listener))
            chain = linkListener(chain, listener);
    }

    public synchronized void removeListener(L listener)
    {
        Objects.requireNonNull(listener);
        if (listeners.remove(listener))
            chain = listeners.stream().reduce(null, this::linkListener,
                    (c1, c2) -> c1 == null ? c2 : c2 == null ? c1 : c1.andThen(c2));
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
            return (e, c) -> Stream.of(this, link).forEach(cl -> cl.accept(e, c));
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
