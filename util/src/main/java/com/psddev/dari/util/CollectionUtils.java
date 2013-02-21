package com.psddev.dari.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Collection utility methods. */
public class CollectionUtils {

    /**
     * Returns a value from the given collection-like {@code object}
     * using the given {@code path} that identifies where the item
     * is located.
     */
    public static Object getByPath(Object object, String path) {
        if (path == null) {
            return object instanceof Map ?
                    ((Map<?, ?>) object).get(null) :
                    null;
        }

        for (String key; path != null; ) {
            if (object == null) {
                return null;
            }

            int slashAt = path.indexOf('/');

            if (slashAt > -1) {
                key = path.substring(0, slashAt);
                path = path.substring(slashAt + 1);

            } else {
                key = path;
                path = null;
            }

            if (object instanceof Map) {
                object = ((Map<?, ?>) object).get(key);

            } else if (object instanceof List) {
                Integer index = ObjectUtils.to(Integer.class, key);

                if (index != null) {
                    List<?> list = (List<?>) object;
                    int listSize = list.size();

                    if (index < 0) {
                        index += listSize;
                    }
                    if (index >= 0 && index < listSize) {
                        object = list.get(index);
                        continue;
                    }
                }

                return null;

            } else {
                Method getter = TypeDefinition.getInstance(object.getClass()).getAllGetters().get(key);

                if (getter == null) {
                    return null;

                } else {
                    try {
                        return getter.invoke(object);

                    } catch (IllegalAccessException error) {
                        throw new IllegalStateException(error);
                    } catch (InvocationTargetException error) {
                        ErrorUtils.rethrow(error);
                    }
                }
            }
        }

        return object;
    }

    /** Takes path like {@code x/y/z} and puts into a {@code Map}. */
    @SuppressWarnings("unchecked")
    public static Object putByPath(Map<String, Object> map, String path, Object value) {
        if (path == null) {
            return map.put(null, value);
        }

        String[] names = StringUtils.split(path, "/");
        int len = names.length - 1;
        for (int i = 0; i < len; i ++) {
            String name = names[i];
            Object newMap = map.get(name);
            if (!(newMap instanceof Map)) {
                newMap = new HashMap<String, Object>();
                map.put(name, newMap);
            }
            map = (Map<String, Object>) newMap;
        }
        return map.put(names[len], value);
    }

    /**
     * Puts all the values in the given source {@code Map} into the
     * destination {@code Map}, merging any {@code Map} values.
     */
    @SuppressWarnings("unchecked")
    public static void putAllRecursively(Map<String, Object> destination, Map<String, Object> source) {
        for (Map.Entry<String, Object> e : source.entrySet()) {
            String key = e.getKey();
            Object srcValue = e.getValue();
            Object dstValue = destination.get(key);
            if (srcValue instanceof Map && dstValue instanceof Map) {
                putAllRecursively((Map<String, Object>) dstValue,
                        (Map<String, Object>) srcValue);
            } else {
                destination.put(key, srcValue);
            }
        }
    }

    /**
     * Sets a value in the given {@code List} without throwing a
     * {@link IndexOutOfBoundsException} on an index that may be greater
     * than the size of the {@code List}.
     */
    public static <T> T set(List<T> list, int index, T value) {
        if (index < 0) {
            throw new IndexOutOfBoundsException();
        }
        int delta = index - list.size();
        if (delta > 0) {
            list.add(null);
            list.add(value);
            return null;
        } else {
            return list.set(index, value);
        }
    }

    /** Takes path like {@code 0/1/2} and puts into a {@code List}. */
    @SuppressWarnings("unchecked")
    public static <T> T setByPath(List<Object> list, String path, T value) {
        String[] names = StringUtils.split(path, "/");
        int len = names.length - 1;
        for (int i = 0; i < len; i ++) {
            int index = Integer.parseInt(names[i]);
            Object newList = list.get(i);
            if (!(newList instanceof List)) {
                newList = new ArrayList<Object>();
                set(list, index, newList);
            }
            list = (List<Object>) newList;
        }
        return (T) set(list, Integer.parseInt(names[len]), value);
    }
}
