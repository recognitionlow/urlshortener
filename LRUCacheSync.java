import java.util.Collections;
import java.util.Map;

public class LRUCacheSync<K, V> {

    private final Map<K,V> cache;

    public LRUCacheSync(int capacity) {
        this.cache = Collections.synchronizedMap(new LRUCache<>(capacity));
    }

    public V getValue(K key) {
        return cache.get(key);
    }

    public synchronized void putValue(K key, V value) {
        cache.put(key, value);
    }

}
