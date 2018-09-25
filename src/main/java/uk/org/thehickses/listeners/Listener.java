package uk.org.thehickses.listeners;

/**
 * The default listener interface. This is a functional interface.
 * 
 * @author Jeremy Hicks
 *
 * @param <E>
 *            the type of the events processed by objects of this interface.
 */
@FunctionalInterface
public interface Listener<E>
{
    /**
     * Processes an event.
     * 
     * @param event
     *            the event.
     */
    void process(E event);
}
