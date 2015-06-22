/*
 * Copyright 2014 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zhwb.study.compiler;

import com.google.common.io.Closeables;
import com.sun.tools.javac.api.JavacTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Map;

/**
 * This class support loading and debugging Java Classes dynamically.
 */
public enum CompilerUtils {
    ;
    private static final Logger LOGGER = LoggerFactory.getLogger(CompilerUtils.class);
    public static final boolean DEBUGGING = ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-Xdebug");
    public static final CachedCompiler CACHED_COMPILER = new CachedCompiler(null, null);
    private static final Method DEFINE_CLASS_METHOD;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String JAVA_CLASS_PATH = "java.class.path";

    static {
        try {
            DEFINE_CLASS_METHOD = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
            DEFINE_CLASS_METHOD.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    static JavaCompiler s_compiler;
    static StandardJavaFileManager s_standardJavaFileManager;
    static MyJavaFileManager s_fileManager;

    private static void reset() {
        s_compiler = ToolProvider.getSystemJavaCompiler();
        if (s_compiler == null)
            s_compiler = JavacTool.create();

        s_standardJavaFileManager = s_compiler.getStandardFileManager(null, null, null);
        s_fileManager = new MyJavaFileManager(s_standardJavaFileManager);
    }

    static {
        reset();
    }

    /**
     * Load a java class file from the classpath or local file system.
     *
     * @param className    expected class name of the outer class.
     * @param resourceName as the full file name with extension.
     * @return the outer class loaded.
     * @throws IOException            the resource could not be loaded.
     * @throws ClassNotFoundException the class name didn't match or failed to initialise.
     */
    public static Class loadFromResource(String className, String resourceName) throws IOException, ClassNotFoundException {
        return loadFromJava(className, readText(resourceName));
    }

    /**
     * Load a java class from text.
     *
     * @param className expected class name of the outer class.
     * @param javaCode  to compile and load.
     * @return the outer class loaded.
     * @throws ClassNotFoundException the class name didn't match or failed to initialise.
     */
    private static Class loadFromJava(String className, String javaCode) throws ClassNotFoundException {
        return CACHED_COMPILER.loadFromJava(Thread.currentThread().getContextClassLoader(), className, javaCode);
    }

    /**
     * Add a directory to the class path for compiling.  This can be required with custom
     *
     * @param dir to add.
     * @return whether the directory was found, if not it is not added either.
     */
    public static boolean addClassPath(String dir) {
        File file = new File(dir);
        if (file.exists()) {
            String path;
            try {
                path = file.getCanonicalPath();
            } catch (IOException ignored) {
                path = file.getAbsolutePath();
            }
            if (!Arrays.asList(System.getProperty(JAVA_CLASS_PATH).split(File.pathSeparator)).contains(path))
                System.setProperty(JAVA_CLASS_PATH, System.getProperty(JAVA_CLASS_PATH) + File.pathSeparator + path);

        } else {
            return false;
        }
        reset();
        return true;
    }

    /**
     * Define a class for byte code.
     *
     * @param className expected to load.
     * @param bytes     of the byte code.
     */
    public static void defineClass(String className, byte[] bytes) {
        defineClass(Thread.currentThread().getContextClassLoader(), className, bytes);
    }

    /**
     * Define a class for byte code.
     *
     * @param classLoader to load the class into.
     * @param className   expected to load.
     * @param bytes       of the byte code.
     */
    public static Class defineClass(ClassLoader classLoader, String className, byte[] bytes) {
        try {
            return (Class) DEFINE_CLASS_METHOD.invoke(classLoader, className, bytes, 0, bytes.length);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            //noinspection ThrowInsideCatchBlockWhichIgnoresCaughtException
            throw new AssertionError(e.getCause());
        }
    }

    private static String readText(String resourceName) throws IOException {
        if (resourceName.startsWith("="))
            return resourceName.substring(1);
        StringWriter sw = new StringWriter();
        Reader isr = new InputStreamReader(getInputStream(resourceName), UTF_8);
        try {
            char[] chars = new char[8 * 1024];
            int len;
            while ((len = isr.read(chars)) > 0)
                sw.write(chars, 0, len);
        } finally {
            close(isr);
        }
        return sw.toString();
    }

    private static String decodeUTF8(byte[] bytes) {
        try {
            return new String(bytes, UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }


    @SuppressWarnings("ReturnOfNull")
    private static byte[] readBytes(File file) {
        if (!file.exists()) return null;
        long len = file.length();
        if (len > Runtime.getRuntime().totalMemory() / 10)
            throw new IllegalStateException("Attempted to read large file " + file + " was " + len + " bytes.");
        byte[] bytes = new byte[(int) len];
        DataInputStream dis = null;
        try {
            dis = new DataInputStream(new FileInputStream(file));
            dis.readFully(bytes);
        } catch (IOException e) {
            LOGGER.warn("Unable to read {}", file, e);
            throw new IllegalStateException("Unable to read file " + file, e);
        }finally {
            close(dis);
        }

        return bytes;
    }

    private static void close(Closeable closeable) {
        if (closeable != null)
            try {
                closeable.close();
            } catch (IOException e) {
                LOGGER.trace("Failed to close {}", closeable, e);
            }
    }

    public static boolean writeText(File file, String text) {
        return writeBytes(file, encodeUTF8(text));
    }

    private static byte[] encodeUTF8(String text) {
        try {
            return text.getBytes(UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    public static byte[] getClassBytes(String className) {
        return s_fileManager.getAllBuffers().get(className);
    }

    public static boolean writeBytes(File file, byte[] bytes) {
        File parentDir = file.getParentFile();
        if (!parentDir.isDirectory() && !parentDir.mkdirs())
            throw new IllegalStateException("Unable to create directory " + parentDir);
        // only write to disk if it has changed.
        File bak = null;
        if (file.exists()) {
            byte[] bytes2 = readBytes(file);
            if (Arrays.equals(bytes, bytes2))
                return false;
            bak = new File(parentDir, file.getName() + ".bak");
            file.renameTo(bak);
        }

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(bytes);
        } catch (IOException e) {
            LOGGER.warn("Unable to write {} as {}", file, decodeUTF8(bytes), e);
            file.delete();
            if (bak != null)
                bak.renameTo(file);
            throw new IllegalStateException("Unable to write " + file, e);
        }finally {
            close(fos);
        }
        return true;
    }

    public static void writeSource(String fullName, String code, URL resourceRoot) throws URISyntaxException, IOException {
        if (resourceRoot == null) {
            //get current class path
            resourceRoot = Thread.currentThread().getContextClassLoader().getResource("");
        }
        if (resourceRoot != null) {
            File sourceRoot = new File(resourceRoot.toURI());
            writeCode(sourceRoot, fullName, code);
            addClassPath(sourceRoot.getCanonicalPath());
        }
    }

    public static void writeSource(Map<String, String> sourceMap, URL resourceRoot) throws URISyntaxException, IOException {
        if (resourceRoot == null) {
            //get current class path
            resourceRoot = Thread.currentThread().getContextClassLoader().getResource("");
        }
        if (resourceRoot != null) {
            File sourceRoot = new File(resourceRoot.toURI());
            if (sourceRoot.exists() && sourceRoot.isDirectory() && sourceRoot.canWrite()) {
                for (Map.Entry<String, String> classSource : sourceMap.entrySet()) {
                    writeCode(sourceRoot, classSource.getKey(), classSource.getValue());
                }
                addClassPath(sourceRoot.getCanonicalPath());
            } else {
                throw new IOException(String.format("Source root have problem, exist=%s, isDirectory=%s, canWrite=%s",
                        sourceRoot.exists(), sourceRoot.isDirectory(), sourceRoot.canWrite()));
            }
        }
    }

    private static void writeCode(File sourceRoot, String fullName, String source) throws IOException {
        int index = fullName.lastIndexOf(".") + 1;
        String className = fullName.substring(index);
        String classPackage = fullName.substring(0, index).replace(".", File.separator);
        File classPackageFile = new File(sourceRoot, classPackage);
        if (!classPackageFile.exists()) {
            classPackageFile.mkdirs();
        }
        File sourceFile = new File(classPackageFile.getCanonicalPath(), className + ".java");
        if (!sourceFile.exists()) {
            BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(sourceFile), "UTF-8"));
            try {
                bufferedWriter.write(source);
            } finally {
                Closeables.close(bufferedWriter, true);
            }
        }
    }

    public static void writeClass(String fullName, byte[] bytes, URL resourceRoot) throws IOException, URISyntaxException {
        if (resourceRoot == null) {
            //get current class path
            resourceRoot = Thread.currentThread().getContextClassLoader().getResource("");
        }
        if (resourceRoot != null) {
            File sourceRoot = new File(resourceRoot.toURI());
            writeClass(fullName, bytes, sourceRoot);
            addClassPath(sourceRoot.getCanonicalPath());
        }

    }

    public static void writeClass(Map<String, byte[]> classMap, URL resourceRoot) throws URISyntaxException, IOException {
        if (resourceRoot == null) {
            //get current class path
            resourceRoot = Thread.currentThread().getContextClassLoader().getResource("");
        }
        if (resourceRoot != null) {
            File sourceRoot = new File(resourceRoot.toURI());
            if (sourceRoot.exists() && sourceRoot.isDirectory() && sourceRoot.canWrite()) {
                for (Map.Entry<String, byte[]> classSource : classMap.entrySet()) {
                    writeClass(classSource.getKey(), classSource.getValue(), sourceRoot);
                }
                addClassPath(sourceRoot.getCanonicalPath());
            } else {
                throw new IOException(String.format("Class root have problem, exist=%s, isDirectory=%s, canWrite=%s",
                        sourceRoot.exists(), sourceRoot.isDirectory(), sourceRoot.canWrite()));
            }
        }
    }

    private static void writeClass(String fullName, byte[] bytes, File sourceRoot) throws IOException {
        int index = fullName.lastIndexOf(".") + 1;
        String className = fullName.substring(index);
        String classPackage = fullName.substring(0, index).replace(".", File.separator);
        File classPackageFile = new File(sourceRoot, classPackage);
        if (!classPackageFile.exists()) {
            classPackageFile.mkdirs();
        }
        File classFile = new File(classPackageFile.getCanonicalPath(), className + ".class");
        if (!classFile.exists()) {
            OutputStream stream = new BufferedOutputStream(new FileOutputStream(classFile));
            try {
                stream.write(bytes);
            } finally {
                Closeables.close(stream, true);
            }
        }
    }

    private static InputStream getInputStream(String filename) throws FileNotFoundException {
        if (filename.isEmpty()) throw new IllegalArgumentException("The file name cannot be empty.");
        if (filename.charAt(0) == '=') return new ByteArrayInputStream(encodeUTF8(filename.substring(1)));
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        InputStream is = contextClassLoader.getResourceAsStream(filename);
        if (is != null) return is;
        InputStream is2 = contextClassLoader.getResourceAsStream('/' + filename);
        if (is2 != null) return is2;
        return new FileInputStream(filename);
    }
}
