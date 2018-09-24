package uk.org.thehickses.listeners;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import uk.org.thehickses.channel.Channel;

public class ListenerChain<L, E>
{
    private interface ChainLink<L, E> extends BiConsumer<E, BiConsumer<L, E>>
    {
        default ChainLink<L, E> andThen(ChainLink<L, E> link)
        {
            return (e, c) -> Stream.of(this, link).forEach(cl -> cl.accept(e, c));
        }
    }

    public static <E> ListenerChain<Listener<E>, E> newInstance()
    {
        return new ListenerChain<>((listener, event) -> listener.process(event), 0);
    }

    public static <L, E> ListenerChain<L, E> newInstance(BiConsumer<L, E> invoker)
    {
        return new ListenerChain<>(invoker, 0);
    }

    public static <E> ListenerChain<Listener<E>, E> newInstance(int threadCount)
    {
        return new ListenerChain<>((listener, event) -> listener.process(event), threadCount);
    }

    public static <L, E> ListenerChain<L, E> newInstance(BiConsumer<L, E> invoker, int threadCount)
    {
        return new ListenerChain<>(invoker, threadCount);
    }

    private ChainLink<L, E> chain = null;
    private final Set<L> listeners = new HashSet<>();
    private final AtomicInteger listenerCount = new AtomicInteger();
    private final BiConsumer<L, E> invoker;
    private final int threadCount;

    private ListenerChain(BiConsumer<L, E> invoker, int threadCount)
    {
        this.invoker = Objects.requireNonNull(invoker);
        this.threadCount = threadCount;
    }

    public void fire(E event)
    {
        Objects.requireNonNull(event);
        if (threadCount == 0)
            fireSync(event);
        else
            fireAsync(event);
    }

    private void fire(E event, BiConsumer<L, E> firer)
    {
        ChainLink<L, E> chainCopy;
        synchronized (this)
        {
            if (chain == null)
                return;
            chainCopy = chain;
        }
        chainCopy.accept(event, firer);
    }

    private void fireAsync(E event)
    {
        AtomicInteger lCount = new AtomicInteger(listenerCount.get());
        Channel<Runnable> ch = new Channel<>(lCount.get());
        IntStream
                .range(0, threadCount)
                .mapToObj(i -> new Thread(() -> ch.range(Runnable::run)))
                .forEach(Thread::start);
        BiConsumer<L, E> firer = (l, e) -> {
            ch.put(() -> invoker.accept(l, e));
            if (lCount.decrementAndGet() == 0)
                ch.closeWhenEmpty();
        };
        fire(event, firer);
    }

    private void fireSync(E event)
    {
        fire(event, invoker);
    }

    public synchronized void addListener(L listener)
    {
        Objects.requireNonNull(listener);
        if (listeners.add(listener))
        {
            chain = linkListener(chain, listener);
            listenerCount.incrementAndGet();
        }
    }

    public synchronized void removeListener(L listener)
    {
        Objects.requireNonNull(listener);
        if (listeners.remove(listener))
        {
            chain = listeners.stream().reduce(null, this::linkListener,
                    (c1, c2) -> c1 == null ? c2 : c2 == null ? c1 : c1.andThen(c2));
            listenerCount.decrementAndGet();
        }
    }

    private ChainLink<L, E> linkListener(ChainLink<L, E> chain, L listener)
    {
        ChainLink<L, E> link = (event, invoker) -> invoker.accept(listener, event);
        return chain == null ? link : chain.andThen(link);
    }
}
