package cn.myflv.noactive.utils;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * API 102: Java reflection helpers as a drop-in replacement for
 * {@code de.robv.android.xposed.XposedHelpers}.
 * <p>
 * The Xposed legacy API is unavailable when running under LSPosed 2.0.3
 * with {@code targetApiVersion=102}; module code must use plain Java
 * reflection instead. This class collects the small subset of
 * XposedHelpers methods that the API 102 port actually uses, preserving
 * the original call-site ergonomics:
 * <ul>
 *   <li>{@link #getObjectField(Object, String)}</li>
 *   <li>{@link #getStaticObjectField(Class, String)}</li>
 *   <li>{@link #getBooleanField(Object, String)}</li>
 *   <li>{@link #getIntField(Object, String)}</li>
 *   <li>{@link #getStaticIntField(Class, String)}</li>
 *   <li>{@link #setBooleanField(Object, String, boolean)}</li>
 *   <li>{@link #setIntField(Object, String, int)}</li>
 *   <li>{@link #setObjectField(Object, String, Object)}</li>
 *   <li>{@link #callMethod(Object, String, Object...)}</li>
 *   <li>{@link #callStaticMethod(Class, String, Object...)}</li>
 *   <li>{@link #findMethodBestMatch(Class, String, Object...)}</li>
 *   <li>{@link #findClass(String, ClassLoader)}</li>
 *   <li>{@link #newInstance(Class, Object...)}</li>
 * </ul>
 * <p>
 * Behavior parity notes:
 * <ul>
 *   <li>Field lookup walks up the class hierarchy (matches XposedHelpers).</li>
 *   <li>Method lookup walks up the class hierarchy and picks the first
 *       name + arity + assignable match. This is a deliberate
 *       simplification of XposedHelpers' "best match" scoring; it is
 *       sufficient for the server wrapper classes that only call
 *       single-signature methods.</li>
 *   <li>On failure a {@link RuntimeException} is thrown wrapping the
 *       underlying reflective exception, mirroring XposedHelpers'
 *       "throw to caller" contract so existing try/catch blocks keep
 *       working unchanged.</li>
 * </ul>
 */
public final class ReflectionUtils {

    private static final String TAG = "NoActive";

    private ReflectionUtils() {
    }

    // ===== Field access =====

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable e) {
            Log.e(TAG, "getObjectField " + fieldName + " on " + obj.getClass().getName() + " failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            Field f = findField(clazz, fieldName);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable e) {
            Log.e(TAG, "getStaticObjectField " + fieldName + " on " + clazz.getName() + " failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        Object v = getObjectField(obj, fieldName);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        throw new ClassCastException("Field " + fieldName + " on " + obj.getClass().getName()
                + " is not boolean: " + (v == null ? "null" : v.getClass().getName()));
    }

    public static int getIntField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            f.setAccessible(true);
            return f.getInt(obj);
        } catch (Throwable e) {
            Log.e(TAG, "getIntField " + fieldName + " on " + obj.getClass().getName() + " failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static int getStaticIntField(Class<?> clazz, String fieldName) {
        try {
            Field f = findField(clazz, fieldName);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable e) {
            Log.e(TAG, "getStaticIntField " + fieldName + " on " + clazz.getName() + " failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            f.setAccessible(true);
            f.setBoolean(obj, value);
        } catch (Throwable e) {
            Log.e(TAG, "setBooleanField " + fieldName + " on " + obj.getClass().getName() + " failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            f.setAccessible(true);
            f.setInt(obj, value);
        } catch (Throwable e) {
            Log.e(TAG, "setIntField " + fieldName + " on " + obj.getClass().getName() + " failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable e) {
            Log.e(TAG, "setObjectField " + fieldName + " on " + obj.getClass().getName() + " failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ===== Method invocation =====

    public static Object callMethod(Object obj, String methodName, Object... args) {
        try {
            Method m = findMethodBestMatch(obj.getClass(), methodName, args);
            m.setAccessible(true);
            return m.invoke(obj, args);
        } catch (Throwable e) {
            Log.e(TAG, "callMethod " + methodName + " on " + obj.getClass().getName() + " failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        try {
            Method m = findMethodBestMatch(clazz, methodName, args);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Throwable e) {
            Log.e(TAG, "callStaticMethod " + methodName + " on " + clazz.getName() + " failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ===== Class loading =====

    /**
     * Load a class by name via {@link Class#forName(String, boolean, ClassLoader)}.
     * <p>
     * Drop-in replacement for {@code XposedHelpers.findClass}. The class is
     * not initialized (initialize=false) to match XposedHelpers semantics
     * and avoid triggering static initializers unexpectedly.
     */
    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (Throwable e) {
            Log.e(TAG, "findClass " + className + " failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Create a new instance of the given class by invoking the constructor
     * whose parameter types match the supplied arguments (best-effort
     * assignability match, mirroring {@code XposedHelpers.newInstance}).
     */
    public static Object newInstance(Class<?> clazz, Object... args) {
        try {
            Constructor<?>[] ctors = clazz.getDeclaredConstructors();
            Constructor<?> best = null;
            for (Constructor<?> c : ctors) {
                Class<?>[] params = c.getParameterTypes();
                if (params.length != args.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < params.length; i++) {
                    if (args[i] == null) {
                        if (params[i].isPrimitive()) {
                            ok = false;
                            break;
                        }
                    } else if (!isAssignable(params[i], args[i].getClass())) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    best = c;
                    break;
                }
            }
            if (best == null) {
                throw new NoSuchMethodException(clazz.getName() + ".<init> with " + args.length + " args");
            }
            best.setAccessible(true);
            return best.newInstance(args);
        } catch (Throwable e) {
            Log.e(TAG, "newInstance " + clazz.getName() + " failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Locate a method by name and arguments, walking up the class hierarchy.
     * <p>
     * Returns the first method whose name matches, parameter count matches
     * {@code args.length}, and every argument is assignable to the
     * corresponding parameter type. Primitive parameters accept boxed
     * arguments of the corresponding wrapper type (e.g. {@code int} accepts
     * {@code Integer}).
     */
    public static Method findMethodBestMatch(Class<?> clazz, String methodName, Object... args) {
        Class<?> c = clazz;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length != args.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < params.length; i++) {
                    if (args[i] == null) {
                        // null is assignable to any non-primitive type
                        if (params[i].isPrimitive()) {
                            ok = false;
                            break;
                        }
                    } else if (!isAssignable(params[i], args[i].getClass())) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        throw new RuntimeException(new NoSuchMethodException(clazz.getName() + "." + methodName
                + " with " + args.length + " args"));
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(clazz.getName() + "." + fieldName);
    }

    private static boolean isAssignable(Class<?> target, Class<?> source) {
        if (target.isPrimitive()) {
            // Map primitive to wrapper for assignability check
            if (target == int.class) return source == Integer.class || source == int.class;
            if (target == boolean.class) return source == Boolean.class || source == boolean.class;
            if (target == long.class) return source == Long.class || source == long.class;
            if (target == double.class) return source == Double.class || source == double.class;
            if (target == float.class) return source == Float.class || source == float.class;
            if (target == short.class) return source == Short.class || source == short.class;
            if (target == byte.class) return source == Byte.class || source == byte.class;
            if (target == char.class) return source == Character.class || source == char.class;
        }
        return target.isAssignableFrom(source);
    }
}
