// Copyright (c) Corporation for National Research Initiatives
package org.python.core;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.lang.reflect.Field;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;

import org.objectweb.asm.ClassReader;
import org.python.util.Generic;

/**
 * Utility class for loading compiled python modules and java classes defined in python modules.
 */
public class BytecodeLoader {

    /**
     * Turn the java byte code in data into a java class.
     *
     * @param name
     *            the name of the class
     * @param data
     *            the java byte code.
     * @param referents
     *            superclasses and interfaces that the new class will reference.
     */
    @SuppressWarnings("unchecked")
    public static Class<?> makeClass(String name, byte[] data, Class<?>... referents) {
        @SuppressWarnings("resource")
        Loader loader = new Loader();
        for (Class<?> referent : referents) {
            try {
                ClassLoader cur = referent.getClassLoader();
                if (cur != null) {
                    loader.addParent(cur);
                }
            } catch (SecurityException e) {
            }
        }
        Class<?> c = loader.loadClassFromBytes(name, data);
        if (ContainsPyBytecode.class.isAssignableFrom(c)) {
            try {
                fixPyBytecode((Class<? extends ContainsPyBytecode>) c);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        BytecodeNotification.notify(name, data, c);
        return c;
    }

    /**
     * Turn the java byte code in data into a java class.
     *
     * @param name
     *            the name of the class
     * @param referents
     *            superclasses and interfaces that the new class will reference.
     * @param data
     *            the java byte code.
     */
    public static Class<?> makeClass(String name, List<Class<?>> referents, byte[] data) {
        if (referents != null) {
            return makeClass(name, data, referents.toArray(new Class[referents.size()]));
        }
        return makeClass(name, data);
    }

    private static PyCode parseSerializedCode(String code_str)
            throws IOException, ClassNotFoundException {
        // From Java 8 use: byte[] b = Base64.getDecoder().decode(code_str);
        byte[] b = base64decode(code_str);
        ByteArrayInputStream bi = new ByteArrayInputStream(b);
        ObjectInputStream si = new ObjectInputStream(bi);
        PyBytecode meth_code = (PyBytecode) si.readObject();
        si.close();
        bi.close();
        return meth_code;
    }

    /**
     * Implement a restricted form of base64 decoding compatible with the encoding in Module. This
     * decoder treats characters outside the set of 64 necessary to encode data as errors, including
     * the pad "=". As a result, the length of the argument exactly determines the size of array
     * returned.
     * 
     * @param src to decode
     * @return a new byte array
     * @throws IllegalArgumentException if src has an invalid character or impossible length.
     */
    private static byte[] base64decode(String src) throws IllegalArgumentException {

        // Length L is a multiple of 4 plus 0, 2 or 3 tail characters (bearing 0, 8, or 16 bits)
        final int L = src.length();
        final int tail = L % 4; // 0 to 3 where 1 (an extra 6 bits) is invalid.
        if (tail == 1) {
            throw new IllegalArgumentException("Input length invalid (4n+1)");
        }

        // src encodes exactly this many bytes:
        final int N = (L / 4) * 3 + (tail > 0 ? tail - 1 : 0);
        byte[] data = new byte[N];

        // Work through src in blocks of 4
        int s = 0, b = 0, quantum;
        while (s <= L - 4) {
            // Process src[s:s+4]
            quantum = (base64CharToBits(src.charAt(s++)) << 18)
                    + (base64CharToBits(src.charAt(s++)) << 12)
                    + (base64CharToBits(src.charAt(s++)) << 6) + base64CharToBits(src.charAt(s++));
            data[b++] = (byte) (quantum >> 16);
            data[b++] = (byte) (quantum >> 8);
            data[b++] = (byte) quantum;
        }

        // Now deal with 2 or 3 tail characters, generating one or two bytes.
        if (tail >= 2) {
            // Repeat the loop body, but everything is 8 bits to the right.
            quantum = (base64CharToBits(src.charAt(s++)) << 10)
                    + (base64CharToBits(src.charAt(s++)) << 4);
            data[b++] = (byte) (quantum >> 8);
            if (tail == 3) {
                quantum += (base64CharToBits(src.charAt(s++)) >> 2);
                data[b++] = (byte) quantum;
            }
        }

        return data;
    }

    /**
     * Helper for {@link #base64decode(String)}, converting one character.
     * @param c to convert
     * @return value 0..63
     * @throws IllegalArgumentException if not a base64 character
     */
    private static int base64CharToBits(char c) throws IllegalArgumentException {
        if (c >= 'a') {
            if (c <= 'z') {
                return c - ('a' - 26);
            }
        } else if (c >= 'A') {
            if (c <= 'Z') {
                return c - 'A';
            }
        } else if (c >= '0') {
            if (c <= '9') {
                return c + (52 - '0');
            }
        } else if (c == '+') {
            return 62;
        } else if (c == '/') {
            return 63;
        }
        throw new IllegalArgumentException("Invalid character " + c);
    }

    /**
     * This method looks for Python-Bytecode stored in String literals.
     * While Java supports rather long strings, constrained only by
     * int-addressing of arrays, it supports only up to 65535 characters
     * in literals (not sure how escape-sequences are counted).
     * To circumvent this limitation, the code is automatically splitted
     * into several literals with the following naming-scheme.
     *
     * - The marker-interface 'ContainsPyBytecode' indicates that a class
     *   contains (static final) literals of the following scheme:
     * - a prefix of '___' indicates a bytecode-containing string literal
     * - a number indicating the number of parts follows
     * - '0_' indicates that no splitting occurred
     * - otherwise another number follows, naming the index of the literal
     * - indexing starts at 0
     *
     * Examples:
     * ___0_method1   contains bytecode for method1
     * ___2_0_method2 contains first part of method2's bytecode
     * ___2_1_method2 contains second part of method2's bytecode
     *
     * Note that this approach is provisional. In future, Jython might contain
     * the bytecode directly as bytecode-objects. The current approach was
     * feasible with much less complicated JVM bytecode-manipulation, but needs
     * special treatment after class-loading.
     */
    public static void fixPyBytecode(Class<? extends ContainsPyBytecode> c)
            throws IllegalAccessException, NoSuchFieldException, java.io.IOException, ClassNotFoundException
    {
        Field[] fields = c.getDeclaredFields();
        for (Field fld: fields) {
            String fldName = fld.getName();
            if (fldName.startsWith("___")) {
                fldName = fldName.substring(3);
                
                String[] splt = fldName.split("_");
                if (splt[0].equals("0")) {
                    fldName = fldName.substring(2);
                    Field codeField = c.getDeclaredField(fldName);
                    if (codeField.get(null) == null) {
                        codeField.set(null, parseSerializedCode((String) fld.get(null)));
                    }
                } else {
                    if (splt[1].equals("0")) {
                        fldName = fldName.substring(splt[0].length()+splt[1].length()+2);
                        Field codeField = c.getDeclaredField(fldName);
                        if (codeField.get(null) == null) {
                            // assemble original code-string:
                            int len = Integer.parseInt(splt[0]);
                            StringBuilder blt = new StringBuilder((String) fld.get(null));
                            int pos = 1, pos0;
                            String partName;
                            while (pos < len) {
                                pos0 = pos;
                                for (Field fldPart: fields) {
                                    partName = fldPart.getName();
                                    if (partName.length() != fldName.length() &&
                                            partName.startsWith("___") &&
                                            partName.endsWith(fldName)) {
                                        String[] splt2 = partName.substring(3).split("_");
                                        if (Integer.parseInt(splt2[1]) == pos) {
                                            blt.append((String) fldPart.get(null));
                                            pos += 1;
                                            if (pos == len) break;
                                        }
                                    }
                                }
                                if (pos0 == pos) {
                                    throw new RuntimeException("Invalid PyBytecode splitting in "+c.getName()+
                                            ":\nSplit-index "+pos+" wasn't found.");
                                }
                            }
                            codeField.set(null, parseSerializedCode(blt.toString()));
                        }
                    }
                }
            }
        }
    }

    /**
     * Turn the java byte code for a compiled python module into a java class.
     *
     * @param name
     *            the name of the class
     * @param data
     *            the java byte code.
     */
    public static PyCode makeCode(String name, byte[] data, String filename) {
        try {
            Class<?> c = makeClass(name, data);
            Object o = c.getConstructor(new Class[] {String.class})
                    .newInstance(new Object[] {filename});

            PyCode result = ((PyRunnable)o).getMain();
            return result;
        } catch (Exception e) {
            throw Py.JavaError(e);
        }
    }

    public static class Loader extends URLClassLoader {

        private List<ClassLoader> parents = Generic.list();

        public Loader() {
            super(new URL[0]);
            parents.add(imp.getSyspathJavaLoader());
        }

        public void addParent(ClassLoader referent) {
            if (!parents.contains(referent)) {
                parents.add(0, referent);
            }
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> c = findLoadedClass(name);
            if (c != null) {
                return c;
            }
            for (ClassLoader loader : parents) {
                try {
                    return loader.loadClass(name);
                } catch (ClassNotFoundException cnfe) {}
            }
            // couldn't find the .class file on sys.path
            throw new ClassNotFoundException(name);
        }

        public Class<?> loadClassFromBytes(String name, byte[] data) {
            if (name.endsWith("$py")) {
                try {
                    // Get the real class name: we might request a 'bar'
                    // Jython module that was compiled as 'foo.bar', or
                    // even 'baz.__init__' which is compiled as just 'baz'
                    ClassReader cr = new ClassReader(data);
                    name = cr.getClassName().replace('/', '.');
                } catch (RuntimeException re) {
                    // Probably an invalid .class, fallback to the
                    // specified name
                }
            }
            Class<?> c = defineClass(name, data, 0, data.length, getClass().getProtectionDomain());
            resolveClass(c);
            return c;
        }
    }
}
