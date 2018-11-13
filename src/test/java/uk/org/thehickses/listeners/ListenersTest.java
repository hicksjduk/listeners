package uk.org.thehickses.listeners;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.Test;

import uk.org.thehickses.channel.Channel;

public class ListenersTest
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
        Listeners<Listener<String>, String> testObj = Listeners.newInstance();
        testObj.fire("First");
        Stream.of(listeners).forEach(testObj::addOrUpdateListener);
        testObj.fire("Second");
        testObj.removeListener(listeners[0]);
        testObj.fire("Third");
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
        Listeners<MyListener, String> testObj = Listeners.newInstance(MyListener::doIt);
        testObj.fire("First");
        Stream.of(listeners).forEach(testObj::addOrUpdateListener);
        testObj.fire("Second");
        testObj.removeListener(listeners[0]);
        testObj.fire("Third");
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
        Listener<String>[] wrappers = Stream.of(listeners).<Listener<String>> map(l -> e -> {
            l.process(e);
            done.put(l);
        }).toArray(Listener[]::new);
        Listeners<Listener<String>, String> testObj = Listeners.newInstance(threadCount);
        Stream.of(wrappers).forEach(testObj::addOrUpdateListener);
        testObj.fire("Hello");
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
            Listeners<Listener<String>, String> testObj = Listeners.newInstance(threadCount,
                    threadPool);
            Stream.of(wrappers).forEach(testObj::addOrUpdateListener);
            testObj.fire("Hello");
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
        Listeners<Listener<String>, String> testObj = Listeners.newInstance();
        Listener<String> listener = mock(Listener.class);
        doThrow(IllegalArgumentException.class).when(listener).process(anyString());
        testObj.addOrUpdateListener(listener);
        testObj.fire("Aaarrggghhh!!!");
        verify(listener).process("Aaarrggghhh!!!");
        verifyNoMoreInteractions(listener);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testWithNegativeThreadCount()
    {
        Listeners<Listener<String>, String> testObj = Listeners.newInstance(-4);
        Listener<String> listener = mock(Listener.class);
        testObj.addOrUpdateListener(listener);
        testObj.fire("Hej");
        verify(listener).process("Hej");
        verifyNoMoreInteractions(listener);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFullRemoval()
    {
        int listenerCount = 30;
        Listeners<Listener<Boolean>, Boolean> testObj = Listeners.newInstance();
        Listener<Boolean>[] listeners = IntStream
                .range(0, listenerCount)
                .mapToObj(i -> mock(Listener.class))
                .toArray(Listener[]::new);
        Stream.of(listeners).forEach(testObj::addOrUpdateListener);
        testObj.fire(true);
        Stream.of(listeners).forEach(testObj::removeListener);
        testObj.fire(false);
        Stream.of(listeners).forEach(l -> verify(l).process(true));
        verifyNoMoreInteractions((Object[]) listeners);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSyncWithSelector()
    {
        int listenerCount = 30;
        Listeners<Listener<Boolean>, Boolean> testObj = Listeners.newInstance();
        Listener<Boolean>[] listeners = IntStream
                .range(0, listenerCount)
                .mapToObj(i -> mock(Listener.class))
                .toArray(Listener[]::new);
        Predicate<Boolean>[] selectors = IntStream
                .range(0, listenerCount)
                .<Predicate<Boolean>> mapToObj(i -> {
                    switch (i % 3)
                    {
                    case 0:
                        return null;
                    case 1:
                        return b -> b;
                    default:
                        return b -> !b;
                    }
                })
                .toArray(Predicate[]::new);
        IntStream.range(0, listenerCount).forEach(
                i -> testObj.addOrUpdateListener(listeners[i], selectors[i]));
        testObj.fire(true);
        Stream.of(listeners).forEach(l -> testObj.addOrUpdateListener(l, b -> b));
        testObj.fire(false);
        Stream.of(listeners).forEach(l -> testObj.addOrUpdateListener(l));
        testObj.fire(false);
        IntStream.range(0, listenerCount).forEach(i -> {
            if (i % 3 != 2)
                verify(listeners[i]).process(true);
            verify(listeners[i]).process(false);
        });
        verifyNoMoreInteractions((Object[]) listeners);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testAsyncWithSelectors()
    {
        int listenerCount = 30;
        Listener<Boolean>[] listeners = IntStream
                .range(0, listenerCount)
                .mapToObj(i -> mock(Listener.class))
                .toArray(Listener[]::new);
        Channel<Void> done = new Channel<>(listenerCount * 2);
        Listener<Boolean>[] wrappers = Stream.of(listeners).<Listener<Boolean>> map(l -> e -> {
            l.process(e);
            done.put(null);
        }).toArray(Listener[]::new);
        Predicate<Boolean>[] selectors = Stream
                .<Predicate<Boolean>> of(null, b -> b, b -> !b)
                .toArray(Predicate[]::new);
        Listeners<Listener<Boolean>, Boolean> testObj = Listeners.newInstance(5);
        IntStream.range(0, listenerCount).forEach(
                i -> testObj.addOrUpdateListener(wrappers[i], selectors[i % 3]));
        testObj.fire(true);
        Stream.of(wrappers).forEach(l -> testObj.addOrUpdateListener(l, b -> b));
        testObj.fire(false);
        Stream.of(wrappers).forEach(l -> testObj.addOrUpdateListener(l));
        testObj.fire(false);
        IntStream.range(0, listenerCount * 5 / 3).forEach(i -> done.get());
        IntStream.range(0, listenerCount).forEach(i -> {
            if (i % 3 != 2)
                verify(listeners[i]).process(true);
            verify(listeners[i]).process(false);
        });
        verifyNoMoreInteractions((Object[]) listeners);
    }
}
