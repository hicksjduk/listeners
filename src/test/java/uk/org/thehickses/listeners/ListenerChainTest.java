package uk.org.thehickses.listeners;

import static org.mockito.Mockito.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.Test;

import uk.org.thehickses.channel.Channel;

public class ListenerChainTest
{
    @Test
    public void testDefaultInterface()
    {
        int listenerCount = 3;
        @SuppressWarnings("unchecked")
        Listener<String>[] listeners = IntStream
                .range(0, listenerCount)
                .mapToObj(i -> mock(Listener.class))
                .toArray(Listener[]::new);
        ListenerChain<Listener<String>, String> chain = ListenerChain.newInstance();
        chain.fire("First");
        Stream.of(listeners).forEach(chain::addListener);
        chain.fire("Second");
        chain.removeListener(listeners[0]);
        chain.fire("Third");
        Stream.of(listeners).forEach(l -> verify(l).process("Second"));
        IntStream.range(1, listenerCount).mapToObj(i -> listeners[i]).forEach(
                l -> verify(l).process("Third"));
        verifyNoMoreInteractions((Object[]) listeners);
    }

    interface MyListener
    {
        void doIt(String event);
    }

    @Test
    public void testCustomInterface()
    {
        int listenerCount = 3;
        MyListener[] listeners = IntStream
                .range(0, listenerCount)
                .mapToObj(i -> mock(MyListener.class))
                .toArray(MyListener[]::new);
        ListenerChain<MyListener, String> chain = ListenerChain.newInstance(MyListener::doIt);
        chain.fire("First");
        Stream.of(listeners).forEach(chain::addListener);
        chain.fire("Second");
        chain.removeListener(listeners[0]);
        chain.fire("Third");
        Stream.of(listeners).forEach(l -> verify(l).doIt("Second"));
        IntStream.range(1, listenerCount).mapToObj(i -> listeners[i]).forEach(
                l -> verify(l).doIt("Third"));
        verifyNoMoreInteractions((Object[]) listeners);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testAsync()
    {
        int listenerCount = 40;
        int threadCount = 16;
        Listener<String>[] listeners = IntStream
                .range(0, listenerCount)
                .mapToObj(i -> mock(Listener.class))
                .toArray(Listener[]::new);
        Channel<Listener<String>> done = new Channel<>(listenerCount);
        Listener<String>[] wrappers = Stream.of(listeners).map(l -> (Listener<String>) e -> {
            l.process(e);
            done.put(l);
        }).toArray(Listener[]::new);
        ListenerChain<Listener<String>, String> chain = ListenerChain.newInstance(threadCount);
        Stream.of(wrappers).forEach(chain::addListener);
        chain.fire("Hello");
        IntStream.range(0, listenerCount).forEach(i -> verify(done.get().value).process("Hello"));
        verifyNoMoreInteractions((Object[]) listeners);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testAsyncWithExecutor()
    {
        int listenerCount = 200;
        int threadCount = 7;
        Listener<String>[] listeners = IntStream
                .range(0, listenerCount)
                .mapToObj(i -> mock(Listener.class))
                .toArray(Listener[]::new);
        Channel<Listener<String>> done = new Channel<>(listenerCount);
        Listener<String>[] wrappers = Stream.of(listeners).map(l -> (Listener<String>) e -> {
            l.process(e);
            done.put(l);
        }).toArray(Listener[]::new);
        ExecutorService threadPool = Executors.newFixedThreadPool(10);
        try
        {
            ListenerChain<Listener<String>, String> chain = ListenerChain.newInstance(threadCount,
                    threadPool);
            Stream.of(wrappers).forEach(chain::addListener);
            chain.fire("Hello");
            IntStream.range(0, listenerCount).forEach(
                    i -> verify(done.get().value).process("Hello"));
        }
        finally
        {
            threadPool.shutdown();
        }
        verifyNoMoreInteractions((Object[]) listeners);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testWithExceptionThrown()
    {
        ListenerChain<Listener<String>, String> chain = ListenerChain.newInstance();
        Listener<String> listener = mock(Listener.class);
        doThrow(IllegalArgumentException.class).when(listener).process(anyString());
        chain.addListener(listener);
        chain.fire("Aaarrggghhh!!!");
        verify(listener).process("Aaarrggghhh!!!");
        verifyNoMoreInteractions(listener);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testWithNegativeThreadCount()
    {
        ListenerChain<Listener<String>, String> chain = ListenerChain.newInstance(-4);
        Listener<String> listener = mock(Listener.class);
        chain.addListener(listener);
        chain.fire("Hej");
        verify(listener).process("Hej");
        verifyNoMoreInteractions(listener);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFullRemoval()
    {
        int listenerCount = 30;
        ListenerChain<Listener<Boolean>, Boolean> chain = ListenerChain.newInstance();
        Listener<Boolean>[] listeners = IntStream
                .range(0, listenerCount)
                .mapToObj(i -> mock(Listener.class))
                .toArray(Listener[]::new);
        Stream.of(listeners).forEach(chain::addListener);
        chain.fire(true);
        Stream.of(listeners).forEach(chain::removeListener);
        chain.fire(false);
        Stream.of(listeners).forEach(l -> verify(l).process(true));
        verifyNoMoreInteractions((Object[]) listeners);
    }
}
