package uk.org.thehickses.listeners;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ListenerChain<L, E>
{
    private Consumer<E> chain = null;
    private final Set<L> listeners = new HashSet<>();
    private final BiConsumer<L, E> invoker;

    public ListenerChain(BiConsumer<L, E> invoker)
    {
        this.invoker = Objects.requireNonNull(invoker);
    }

    public void fire(E event)
    {
        Objects.requireNonNull(event);
        Consumer<E> chainCopy;
        synchronized (this)
        {
            if (chain == null)
                return;
            chainCopy = chain;
        }
        chainCopy.accept(event);
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

    private Consumer<E> linkListener(Consumer<E> chain, L listener)
    {
        Consumer<E> link = event -> invoker.accept(listener, event);
        return chain == null ? link : chain.andThen(link);
    }
}
