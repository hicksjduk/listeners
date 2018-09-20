package uk.org.thehickses.listeners;

import static org.mockito.Mockito.*;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.Test;

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
        IntStream.range(1, listenerCount).mapToObj(i -> listeners[i]).forEach(l -> verify(l).process("Third"));
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
        IntStream.range(1, listenerCount).mapToObj(i -> listeners[i]).forEach(l -> verify(l).doIt("Third"));
        verifyNoMoreInteractions((Object[]) listeners);
    }
}
