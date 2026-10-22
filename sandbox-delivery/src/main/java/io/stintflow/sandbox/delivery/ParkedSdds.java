package io.stintflow.sandbox.delivery;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SDD S.10, RF5 and 8j: the SDDs parked in the DLQ, waiting for the technician. Stint has no listing of instances by
 * state, so the Delivery keeps its own list (the same state {@code check-sdds} stands on): an SDD is parked when it is
 * dead-lettered and unparked when the technician decides. Ids and counts only.
 */
public interface ParkedSdds {

    record Parked(String projectId, String cycle, String sddId, int attempts) {
    }

    void park(Parked parked);

    void unpark(String projectId, String cycle, String sddId);

    /** Everything parked now, by project, cycle and SDD. */
    List<Parked> all();

    /** The list of the tests and of a domain without a table. */
    final class InMemory implements ParkedSdds {

        private final Map<String, Parked> parked = new ConcurrentHashMap<>();

        @Override
        public void park(Parked p) {
            parked.put(key(p.projectId(), p.cycle(), p.sddId()), p);
        }

        @Override
        public void unpark(String projectId, String cycle, String sddId) {
            parked.remove(key(projectId, cycle, sddId));
        }

        @Override
        public List<Parked> all() {
            return parked.values().stream().sorted(Comparator.comparing(Parked::projectId)
                    .thenComparing(Parked::cycle).thenComparing(Parked::sddId)).toList();
        }
    }

    static String key(String projectId, String cycle, String sddId) {
        return projectId + "#" + cycle + "#" + sddId;
    }
}
