/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package accord.verify;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;

import static accord.verify.LinearizabilityVerifier.Witness.Type.UPDATE_SUCCESS;
import static accord.verify.LinearizabilityVerifier.Witness.Type.UPDATE_UNKNOWN;

/**
 * Linearizability checker.
 * <p>
 * We simply verify that there is no viewing of histories backwards or forwards in time (i.e. that the time periods each
 * unique list is witnessable for is disjoint) and that each list is a prefix of any lists witnessed later
 *
 * TODO (low priority): merge with SerializabilityVerifier.
 */
public class LinearizabilityVerifier
{
    // A client observation of a sequence, and the logical start
    // and end times of the client operation that witnessed it
    public static class Observation extends Witness implements Comparable<Observation>
    {
        final int[] sequence;

        public Observation(int[] sequence, int start, int end)
        {
            super(Type.READ, start, end);
            this.sequence = sequence;
        }

        // computes a PARTIAL ORDER on when the outcome occurred, i.e. for many pair-wise comparisons the answer is 0
        public int compareTo(Observation that)
        {
            if (this.end < that.start)
                return -1;
            if (that.end < this.start)
                return 1;
            return 0;
        }

        public String toString()
        {
            return String.format("((%3d,%3d), WITNESS, %s)", start, end, Arrays.toString(sequence));
        }
    }

    static class Witness
    {
        enum Type { UPDATE_SUCCESS, UPDATE_UNKNOWN, READ }

        final Witness.Type type;
        final int start;
        final int end;

        Witness(Witness.Type type, int start, int end)
        {
            this.type = type;
            this.start = start;
            this.end = end;
        }

        public String toString()
        {
            return String.format("((%3d,%3d),%s)", start, end, type);
        }
    }

    static class Event
    {
        final List<Witness> log = new ArrayList<>();

        final int eventId;
        int eventPosition = -1;
        int[] sequence;
        int visibleBy = Integer.MAX_VALUE; // witnessed by at least this time
        int visibleUntil = -1;             // witnessed until at least this time (i.e. witnessed nothing newer by then)
        Boolean result;                    // unknown, success or (implied by not being witnessed) failure

        Event(int eventId)
        {
            this.eventId = eventId;
        }
    }

    final int primaryKey;
    private Event[] byId;
    private final Queue<Event> unwitnessed = new ArrayDeque<>();
    private Event[] events = new Event[16];

    public LinearizabilityVerifier(int primaryKey)
    {
        this.primaryKey = primaryKey;
        byId = new Event[16];
    }

    public void witnessRead(Observation observed)
    {
        int eventPosition = observed.sequence.length;
        int eventId = eventPosition == 0 ? -1 : observed.sequence[eventPosition - 1];
        Event event = get(eventPosition, eventId);
        recordWitness(event, observed);
        recordVisibleBy(event, observed.end);
        recordVisibleUntil(event, observed.start);

        // see if any of the unwitnessed events can be ruled out
        if (!unwitnessed.isEmpty())
        {
            Iterator<Event> iter = unwitnessed.iterator();
            while (iter.hasNext())
            {
                Event e = iter.next();
                if (e.visibleBy < observed.start)
                {
                    if (e.result == null)
                    {
                        // still accessible byId, so if we witness it later we will flag the inconsistency
                        e.result = Boolean.FALSE;
                        iter.remove();
                    }
                    else if (e.result)
                    {
                        throw fail(primaryKey, "%d witnessed as absent at T%d", e.eventId, observed.end);
                    }
                }
            }
        }
    }

    public void witnessWrite(int eventId, int start, int end, boolean success)
    {
        Event event = ensureById(eventId);
        if (event == null)
        {
            byId[eventId] = event = new Event(eventId);
            unwitnessed.add(event);
        }

        event.log.add(new Witness(success ? UPDATE_SUCCESS : UPDATE_UNKNOWN, start, end));
        recordVisibleUntil(event, start);
        recordVisibleBy(event, end); // even the result is unknown, the result must be visible to other operations by the time we terminate
        if (success)
        {
            if (event.result == Boolean.FALSE)
                throw fail(primaryKey, "witnessed absence of %d but event returned success", eventId);
            event.result = Boolean.TRUE;
        }
    }

    void recordWitness(Event event, Observation witness)
    {
        recordWitness(event, witness.sequence.length, witness, witness.sequence);
    }

    void recordWitness(Event event, int eventPosition, Observation witness, int[] sequence)
    {
        while (true)
        {
            event.log.add(witness);
            if (event.sequence != null)
            {
                if (!Arrays.equals(event.sequence, sequence))
                    throw fail(primaryKey, "%s previously witnessed %s", witness, event.sequence);
                return;
            }

            event.sequence = sequence;
            event.eventPosition = eventPosition;

            event = prev(event);
            if (event == null)
                break;

            if (event.sequence != null)
            {
                // verify it's a strict prefix
                if (!equal(event.sequence, sequence, sequence.length - 1))
                    throw fail(primaryKey, "%s previously witnessed %s", sequence, event.sequence);
                break;
            }

            // if our predecessor event hasn't been witnessed directly, witness it by this event, even if
            // we say nothing about the times it may have been witnessed (besides those implied by the write event)
            eventPosition -= 1;
            sequence = Arrays.copyOf(sequence, eventPosition);
        }
    }

    void recordVisibleBy(Event event, int visibleBy)
    {
        if (visibleBy < event.visibleBy)
        {
            event.visibleBy = visibleBy;
            Event prev = prev(event);
            if (prev != null && prev.visibleUntil >= visibleBy)
                throw fail(primaryKey, "%s not witnessed >= %d, but also witnessed <= %d", event.sequence, event.eventId, prev.visibleUntil, event.visibleBy);
        }
    }

    void recordVisibleUntil(Event event, int visibleUntil)
    {
        if (visibleUntil > event.visibleUntil)
        {
            event.visibleUntil = visibleUntil;
            Event next = next(event);
            if (next != null && visibleUntil >= next.visibleBy)
                throw fail(primaryKey, "%s %d not witnessed >= %d, but also witnessed <= %d", next.sequence, next.eventId, event.visibleUntil, next.visibleBy);
        }
    }

    Event ensureById(int id)
    {
        if (byId.length <= id)
            byId = Arrays.copyOf(byId, id + 1 + (id / 2));
        return byId[id];
    }

    /**
     * Initialise the Event representing both eventPosition and eventId for witnessing
     */
    Event get(int eventPosition, int eventId)
    {
        if (eventPosition >= events.length)
            events = Arrays.copyOf(events, Integer.max(eventPosition + 1, events.length * 2));

        Event event = events[eventPosition];
        if (event == null)
        {
            if (eventId < 0)
            {
                assert eventId == -1;
                events[eventPosition] = event = new Event(eventId);
            }
            else
            {
                event = ensureById(eventId);
                if (event != null)
                {
                    if (event.eventPosition >= 0)
                        throw fail(primaryKey, "%d occurs at positions %d and %d", eventId, eventPosition, event.eventPosition);
                    events[eventPosition] = event;
                    unwitnessed.remove(event);
                }
                else
                {
                    byId[eventId] = events[eventPosition] = event = new Event(eventId);
                }
            }
        }
        else
        {
            if (eventId != event.eventId)
                throw fail(primaryKey, "(eventId, eventPosition): (%d, %d) != (%d, %d)", eventId, eventPosition, event.eventId, event.eventPosition);
            else if (eventPosition != event.eventPosition)
                throw fail(primaryKey, "%d occurs at positions %d and %d", eventId, eventPosition, event.eventPosition);
        }
        return event;
    }

    Event prev(Event event)
    {
        // we can reach here via recordOutcome without knowing our Observation,
        // in which case we won't know our predecessor event, so we cannot do anything useful
        if (event.sequence == null)
            return null;

        int eventPosition = event.eventPosition - 1;
        if (eventPosition < 0)
            return null;

        // initialise the event, if necessary importing information from byId
        return get(eventPosition, eventPosition == 0 ? -1 : event.sequence[eventPosition - 1]);
    }

    Event next(Event event)
    {
        int eventPosition = event.eventPosition + 1;
        if (eventPosition == 0 || eventPosition >= events.length)
            return null;

        // we cannot initialise the event meaningfully, so just return what is already known (if anything)
        return events[eventPosition];
    }

    void print()
    {
        for (Event e : events)
        {
            if (e == null) break;
            System.err.printf("%d: (%4d,%4d) %s %s\n", primaryKey, e.visibleBy, e.visibleUntil, Arrays.toString(e.sequence), e.log);
        }
        for (Event e : byId)
        {
            if (e == null) continue;
            System.err.printf("%s: %s\n", e.eventId, e.log);
        }
    }

    static Error fail(int primaryKey, String message, Object ... params)
    {
        for (int i = 0 ; i < params.length ; ++i)
            if (params[i] instanceof int[]) params[i] = Arrays.toString((int[]) params[i]);
        throw new HistoryViolation(primaryKey, "history violation on " + primaryKey + ": " + String.format(message, params));
    }

    static boolean equal(int[] a, int [] b, int count)
    {
        for (int i = 0 ; i < count ; ++i)
            if (a[i] != b[i])
                return false;
        return true;
    }
}
