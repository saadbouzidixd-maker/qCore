package studio.qsa3ed.qcore.api;

import java.time.Duration;
import java.util.List;

/** Typed access to a node's configuration. */
public interface ConfigurationService {

    String getString(String key, String defaultValue);

    int getInt(String key, int defaultValue);

    long getLong(String key, long defaultValue);

    double getDouble(String key, double defaultValue);

    boolean getBoolean(String key, boolean defaultValue);

    Duration getDuration(String key, Duration defaultValue);

    List<String> getStringList(String key);

    boolean has(String key);
}
