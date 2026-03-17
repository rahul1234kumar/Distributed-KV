package org.example.node;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Versioned key-value store.
 *
 * Each value is stored with a version (timestamp in milliseconds).
 * Version is used during quorum reads to pick the latest value
 * when two nodes return different answers.
 */
public class Store {

    // Inner class holding value + version together
    public static class VersionedValue {
        public final String value;
        public final long version; // millisecond timestamp

        public VersionedValue(String value, long version) {
            this.value = value;
            this.version = version;
        }

        // Serialize to string for sending over TCP
        // format: "version:value"
        // e.g.   "1710234567891:Bangalore"
        public String serialize() {
            return version + ":" + value;
        }

        // Deserialize from "version:value" string
        public static VersionedValue deserialize(String raw) {
            // split on FIRST colon only — value might contain colons
            int idx = raw.indexOf(":");
            long version = Long.parseLong(raw.substring(0, idx));
            String value = raw.substring(idx + 1);
            return new VersionedValue(value, version);
        }
    }

    private final ConcurrentHashMap<String, VersionedValue> data
            = new ConcurrentHashMap<>();

    // PUT with explicit version (used during replication)
    public void put(String key, String value, long version) {
        data.put(key, new VersionedValue(value, version));
    }

    // PUT with auto-generated version (used for new client writes)
    public void put(String key, String value) {
        data.put(key, new VersionedValue(value, System.currentTimeMillis()));
    }

    // Returns VersionedValue or null if not found
    public VersionedValue get(String key) {
        return data.get(key);
    }

    public void delete(String key) {
        data.remove(key);
    }
}