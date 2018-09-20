package uk.org.thehickses.listeners;

@FunctionalInterface
public interface Listener<E>
{
    void process(E event);
}
