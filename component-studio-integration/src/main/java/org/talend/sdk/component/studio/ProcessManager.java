/**
 * Copyright (C) 2006-2018 Talend Inc. - www.talend.com
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
package org.talend.sdk.component.studio;

import static java.lang.ClassLoader.getSystemClassLoader;
import static java.lang.Thread.sleep;
import static java.util.Collections.emptyEnumeration;
import static java.util.Collections.singleton;
import static java.util.Optional.ofNullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import org.eclipse.m2e.core.MavenPlugin;
import org.talend.core.runtime.maven.MavenConstants;
import org.talend.osgi.hook.maven.MavenResolver;
import org.talend.sdk.component.studio.lang.LocalLock;
import org.talend.sdk.component.studio.lang.StringPropertiesTokenizer;
import org.talend.sdk.component.studio.logging.JULToOsgiHandler;

import lombok.Getter;

public class ProcessManager implements AutoCloseable {

    private final String groupId;

    private MavenResolver mavenResolver;

    @Getter
    private int port;

    private Thread hook;

    private volatile AutoCloseable instance;

    private volatile CountDownLatch ready;

    private URLClassLoader loader;

    private Thread serverThread;

    public ProcessManager(final String groupId, final MavenResolver resolver) {
        this.groupId = groupId;
        this.mavenResolver = resolver;
    }

    public void waitForServer(final Runnable healthcheck) { // useful for the client, to ensure we are ready
        final int steps = 250;
        for (int i = 0; i < TimeUnit.MINUTES.toMillis(10) / steps; i++) {
            try {
                if (ready.await(steps, TimeUnit.MILLISECONDS)) {
                    healthcheck.run();
                    return;
                }
                if (i > 0 && i % 12 == 0) {
                    final Thread starterThread = serverThread;
                    if (starterThread == null || !starterThread.isAlive()) {
                        try {
                            System.err.println("Component server didn't start properly, please check the log before");
                            Lookups.configuration().disable();
                            break;
                        } catch (final IllegalThreadStateException itse) {
                            // expected
                        }
                    }
                    System.out.println("Component server not yet ready, will wait again"); // no logger!
                }
                sleep(steps);
            } catch (final InterruptedException e) {
                Thread.interrupted();
                break;
            } catch (final RuntimeException re) {
                try {
                    sleep(500); // wait and retry, the healthcheck failed
                } catch (final InterruptedException e) {
                    Thread.interrupted();
                    break;
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        if (hook != null) {
            Runtime.getRuntime().removeShutdownHook(hook);
            hook = null;
        }
        ofNullable(instance).ifPresent(i -> {
            try {
                i.close();
            } catch (final Exception e) {
                // no-op
            } finally {
                instance = null;
            }
        });
        try {
            if (serverThread != null) {
                if (serverThread.isAlive()) {
                    try {
                        serverThread.join(TimeUnit.SECONDS.toMillis(10));
                    } catch (final InterruptedException e) {
                        Thread.interrupted();
                    }
                    serverThread.interrupt();
                    try {
                        serverThread.join(TimeUnit.MINUTES.toMillis(1));
                    } catch (final InterruptedException e) {
                        Thread.interrupted();
                    }
                }
                serverThread = null;
            }
        } finally {
            if (loader != null) {
                try {
                    loader.close();
                } catch (final IOException e) {
                    // no-op: not important at that time
                } finally {
                    loader = null;
                }
            }
        }
    }

    public synchronized void start() {
        final Collection<URL> paths;
        try {
            paths = createClasspath();
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }

        final List<String> arguments =
                new StringPropertiesTokenizer(System.getProperty("component.java.arguments", "")).tokens();

        String m2Repo = System.getProperty("component.java.m2");
        if (m2Repo == null) {
            m2Repo = MavenPlugin.getMaven().getLocalRepositoryPath();
        }

        final String components = System.getProperty("component.java.coordinates");
        final String registry = System.getProperty("component.java.registry");

        if (m2Repo != null) {
            System.setProperty("talend.component.server.maven.repository", m2Repo);
        }
        if (components != null) {
            System.setProperty("talend.component.server.component.coordinates", components);
        }
        if (registry != null) {
            System.setProperty("talend.component.server.component.registry", registry);
        }

        // local instance, no need of any security
        System.setProperty("talend.component.server.security.connection.handler", "securityNoopHandler");
        System.setProperty("talend.component.server.security.command.handler", "securityNoopHandler");

        loader = new URLClassLoader(paths.toArray(new URL[paths.size()]), rootLoader()) {

            @Override
            public Enumeration<URL> getResources(final String name) throws IOException {
                if ("META-INF/services/org.apache.juli.logging.Log".equals(name)) {
                    return emptyEnumeration();
                }
                if ("META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat".equals(name)) {
                    return emptyEnumeration();
                }
                return super.getResources(name);
            }
        };
        final Lock lock = new LocalLock(
                ofNullable(System.getProperty("component.lock.location")).map(File::new).orElseGet(
                        () -> new File(System.getProperty("user.home"), ".talend/locks/" + GAV.ARTIFACT_ID + ".lock")),
                null);
        lock.lock();
        port = newPort();

        if (!arguments.contains("--http")) {
            arguments.add(0, Integer.toString(port));
            arguments.add(0, "--http");
        }
        if (!arguments.contains("--scanning-exclude")) {
            arguments.add(0, "org.talend,org.apache,"
                    + "component-api,component-spi,component-runtime-manager,component-runtime-impl,component-runtime-design,"
                    + "zipkin,workbench,tomcat-,system-rules,registry,preference,org.jacoco,lz4,jobs,jface,help,draw2d,contentytpe,");
            arguments.add(0, "--scanning-exclude");
        }
        // being embedded and not in app loader we can't use that
        arguments.add(0, "false");
        arguments.add(0, "--log4j2-jul-bridge");
        arguments.add(0, "false");
        arguments.add(0, "--logging-global-setup");
        arguments.add(0, "false");
        arguments.add(0, "--use-shutdown-hook");

        ready = new CountDownLatch(1);

        serverThread = new Thread() { // server thread

            {
                setName(ProcessManager.class.getName() + "-server");
                setContextClassLoader(loader);
            }

            @Override
            public void run() {
                System.setProperty("org.apache.tomcat.Logger", "jul");
                configureJUL(); // do it in the right classloader context

                try {
                    final Class<?> cliClass = loader.loadClass("org.talend.sdk.component.server.cli.EnhancedCli");
                    instance = AutoCloseable.class.cast(cliClass.getConstructor(String[].class).newInstance(
                            new Object[] { arguments.toArray(new String[arguments.size()]) }));
                    Runnable.class.cast(instance).run();
                } catch (final InvocationTargetException ie) {
                    if (!InterruptedException.class.isInstance(ie.getTargetException())) {
                        throw new IllegalStateException(ie.getTargetException());
                    }
                } catch (final Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        hook = new Thread() { // in case of a ctrl+C/kill+X on the studio

            {
                setName(getClass().getName() + "-shutdown-hook");
            }

            @Override
            public void run() {
                lock.unlock();
                close();
            }
        };
        Runtime.getRuntime().addShutdownHook(hook);
        serverThread.start();
        new Thread() { // just a healthcheck to be able to ensure the server is up when starting to use
            // it (ou client)

            {
                setName(getClass().getName() + "-readiness-checker");
            }

            @Override
            public void run() {
                final long end = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15);
                while (end - System.currentTimeMillis() >= 0) {
                    final Thread serverThread = ProcessManager.this.serverThread;
                    if (serverThread == null || !serverThread.isAlive()) {
                        lock.unlock();
                        throw new IllegalStateException("Component server startup failed");
                    }

                    try (final Socket ignored = new Socket("localhost", port)) {
                        new URL("http://localhost:" + port + "/api/v1/environment").openStream().close();
                        lock.unlock();
                        ready.countDown();
                        return; // opened :)
                    } catch (final IOException e) {
                        try { // try again
                            Thread.sleep(500);
                        } catch (final InterruptedException e1) {
                            Thread.interrupted();
                            break;
                        }
                    }

                    lock.unlock();
                }
                throw new IllegalStateException("Component server didn't start in 15mn!");
            }
        }.start();
    }

    private void configureJUL() {
        if (!Boolean.getBoolean("component.server.jul.skip")) {
            final Logger global = Logger.getLogger("");
            Stream.of(global.getHandlers()).forEach(global::removeHandler);
            global.addHandler(new JULToOsgiHandler());
        } else if (Boolean.getBoolean("component.server.jul.appendOSGiHandler")) {
            Logger.getLogger("").addHandler(new JULToOsgiHandler());
        }
    }

    private ClassLoader rootLoader() {
        return new ClassLoader(getSystemClassLoader()) {

            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                if (name == null) {
                    throw new ClassNotFoundException();
                }
                if (name.startsWith("org.")) {
                    final String sub = name.substring("org.".length());
                    if (sub.startsWith("apache.") || sub.startsWith("talend.")) {
                        throw new ClassNotFoundException(name);
                    }
                    if (sub.startsWith("slf4j")) {
                        throw new ClassNotFoundException(name);
                    }
                }
                if (name.startsWith("brave.")) {
                    throw new ClassNotFoundException(name);
                }
                if (name.startsWith("javax.")) {
                    final String sub = name.substring("javax.".length());
                    if (sub.startsWith("annotation.")) {
                        throw new ClassNotFoundException(name);
                    }
                    if (sub.startsWith("inject.")) {
                        throw new ClassNotFoundException(name);
                    }
                    if (sub.startsWith("interceptor.")) {
                        throw new ClassNotFoundException(name);
                    }
                    if (sub.startsWith("ws.")) {
                        throw new ClassNotFoundException(name);
                    }
                    if (sub.startsWith("enterprise.")) {
                        throw new ClassNotFoundException(name);
                    }
                    if (sub.startsWith("decorator.")) {
                        throw new ClassNotFoundException(name);
                    }
                    if (sub.startsWith("json.")) {
                        throw new ClassNotFoundException(name);
                    }
                    if (sub.startsWith("servlet.")) {
                        throw new ClassNotFoundException(name);
                    }
                    if (sub.startsWith("websocket.")) {
                        throw new ClassNotFoundException(name);
                    }
                    if (sub.startsWith("security.auth.message.")) {
                        throw new ClassNotFoundException(name);
                    }
                }
                if (name.startsWith("net.jpountz.")) {
                    throw new ClassNotFoundException(name);
                }
                if (name.startsWith("zipkin2.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }

            @Override
            public Enumeration<URL> getResources(final String name) throws IOException {
                if (name.startsWith("log4j2.")) {
                    return emptyEnumeration();
                }
                if (name.startsWith("META-INF/services/org.apache.")) {
                    return emptyEnumeration();
                }
                if (name.startsWith("META-INF/services/javax.servlet.")) {
                    return emptyEnumeration();
                }
                if (name.equals("META-INF/log4j-provider.properties")) {
                    return emptyEnumeration();
                }
                if (name.equals("META-INF/org/apache/")) {
                    return emptyEnumeration();
                }
                if (name.equals("org/slf4j/impl/StaticLoggerBinder.class")) {
                    return emptyEnumeration();
                }
                return super.getResources(name);
            }
        };
    }

    private Collection<URL> createClasspath() throws IOException {
        final File serverJar = resolve(groupId + ":component-server:jar:" + GAV.VERSION);
        if (!serverJar.exists()) {
            throw new IllegalArgumentException(serverJar + " doesn't exist");
        }

        final Collection<URL> paths = new HashSet<>(singleton(serverJar.toURI().toURL()));
        if (serverJar.isDirectory()) {
            try (final InputStream deps = new FileInputStream(new File(serverJar, "TALEND-INF/dependencies.txt"))) {
                addDependencies(paths, deps);
            }
        } else {
            try (final JarFile jar = new JarFile(serverJar)) {
                final ZipEntry entry = jar.getEntry("TALEND-INF/dependencies.txt");
                try (final InputStream deps = jar.getInputStream(entry)) {
                    addDependencies(paths, deps);
                }
            }
        }
        // we use the Cli as main so we need it
        paths.add(resolve("commons-cli:commons-cli:jar:" + GAV.CLI_VERSION).toURI().toURL());
        paths.add(resolve("org.slf4j:slf4j-jdk14:jar:" + GAV.SLF4J_VERSION).toURI().toURL());
        return paths;
    }

    private void addDependencies(final Collection<URL> paths, final InputStream deps) {
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(deps))) {
            String line;

            do {
                line = reader.readLine();
                if (line == null) {
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if (line.split(":").length < 4) {
                    continue;
                }
                if (line.endsWith(":test") || line.endsWith(":provided")) {
                    continue;
                }
                if (line.contains("log4j")) {
                    continue;
                }

                final String[] segments = line.split(":");
                if (segments.length < 4) {
                    throw new IllegalArgumentException("Invalid coordinate: " + line);
                }

                paths.add(resolve(line).toURI().toURL());
            } while (true);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private File resolve(final String gav) {
        try { // convert to pax-url syntax
            final String[] split = gav.split("\\:"); // assuming we dont use classifiers for now
            final String paxUrl =
                    "mvn:" + MavenConstants.LOCAL_RESOLUTION_URL + '!' + split[0] + '/' + split[1] + '/' + split[3];
            return mavenResolver.resolve(paxUrl);
        } catch (final IOException e) {
            throw new IllegalArgumentException("can't resolve '" + gav + "', "
                    + "in development ensure you are using maven.repository=global in configuration/config.ini, "
                    + "in a standalone installation, ensure the studio maven repository contains this dependency", e);
        }
    }

    private Integer newPort() {
        final Integer port = Integer.getInteger("component.java.port", -1);
        if (port <= 0) {
            try (final ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return port;
    }
}