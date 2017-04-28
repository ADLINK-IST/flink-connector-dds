package com.prismtech.vortex.flink;

import java.lang.reflect.Array;
import java.util.Arrays;

/**
 * Created by Vortex.
 */
public final class VortexUtils {
    private VortexUtils(){}

    @SuppressWarnings("unchecked")
    public static <T> T[] filterType(Object[] source, Class<T> type) {

        return Arrays.stream(source)
                .filter(type::isInstance)
                .map(type::cast)
                .toArray(size -> (T[]) Array.newInstance(type, size));
    }
}
