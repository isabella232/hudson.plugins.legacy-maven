/*******************************************************************************
 *
 * Copyright (c) 2004-2009 Oracle Corporation.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors: 
*
*    Kohsuke Kawaguchi
 *     
 *
 *******************************************************************************/ 

package hudson.maven.agent;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.Socket;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.codehaus.classworlds.ClassRealm;
import org.codehaus.classworlds.ClassWorld;
import org.codehaus.classworlds.DefaultClassRealm;
import org.codehaus.classworlds.Launcher;
import org.codehaus.classworlds.NoSuchRealmException;

/**
 * Entry point for launching Maven and Hudson remoting in the same VM,
 * in the classloader layout that Maven expects.
 *
 * <p>
 * The actual Maven execution will be started by the program sent
 * through remoting. 
 *
 * @author Kohsuke Kawaguchi
 */
public class Main {
    /**
     * Used to pass the classworld instance to the code running inside the remoting system.
     */
    private static Launcher launcher;

    public static void main(String[] args) throws Exception {
        main(new File(args[0]),new File(args[1]),new File(args[2]),Integer.parseInt(args[3]),
                args.length==4?null:new File(args[4]));
    }

    /**
     *
     * @param m2Home
     *      Maven2 installation. This is where we find Maven jars that we'll run.
     * @param remotingJar
     *      Hudson's remoting.jar that we'll load.
     * @param interceptorJar
     *      maven-interceptor.jar that we'll load.
     * @param tcpPort
     *      TCP socket that the launching Hudson will be listening to.
     *      This is used for the remoting communication.
     * @param interceptorOverrideJar
     *      Possibly null override jar to be placed in front of maven-interceptor.jar
     */
    public static void main(File m2Home, File remotingJar, File interceptorJar, int tcpPort, File interceptorOverrideJar) throws Exception {
        // Unix master with Windows slave ends up passing path in Unix format,
        // so convert it to Windows format now so that no one chokes with the path format later.
        try {
            m2Home = m2Home.getCanonicalFile();
        } catch (IOException e) {
            // ignore. We'll check the error later if m2Home exists anyway
        }

        if(!m2Home.exists()) {
            System.err.println("No such directory exists: "+m2Home);
            System.exit(1);
        }

        versionCheck();

        // expose variables used in the classworlds configuration
        System.setProperty("maven.home",m2Home.getPath());
        System.setProperty("maven.interceptor",interceptorJar.getPath());
        System.setProperty("maven.interceptor.override",
                // I don't know how classworlds react to undefined variable, so 
                (interceptorOverrideJar!=null?interceptorOverrideJar:interceptorJar).getPath());

        boolean is206OrLater = !new File(m2Home,"core").exists();

        // load the default realms
        launcher = new Launcher();
        launcher.setSystemClassLoader(Main.class.getClassLoader());
        launcher.configure(Main.class.getResourceAsStream(
            is206OrLater?"classworlds-2.0.6.conf":"classworlds.conf"));

        // have it eventually delegate to this class so that this can be visible

        //ClassWorldAdapter classWorldAdapter = ClassWorldAdapter.getInstance( launcher.getWorld() );
        
        // create a realm for loading remoting subsystem.
        // this needs to be able to see maven.
        //ClassRealm remoting = new DefaultClassRealm(classWorldAdapter,"hudson-remoting", launcher.getSystemClassLoader());
        
        ClassRealm remoting = new DefaultClassRealm(launcher.getWorld(),"hudson-remoting", launcher.getSystemClassLoader());
        
        remoting.setParent(launcher.getWorld().getRealm("plexus.core.maven"));
        remoting.addConstituent(remotingJar.toURI().toURL());

        final Socket s = new Socket((String)null,tcpPort);

        Class remotingLauncher = remoting.loadClass("hudson.remoting.Launcher");
        remotingLauncher.getMethod("main",new Class[]{InputStream.class,OutputStream.class}).invoke(null,
                new Object[]{
                        // do partial close, since socket.getInputStream and getOutputStream doesn't do it by
                        new BufferedInputStream(new FilterInputStream(s.getInputStream()) {
                            public void close() throws IOException {
                                s.shutdownInput();
                            }
                        }),
                        new BufferedOutputStream(new RealFilterOutputStream(s.getOutputStream()) {
                            public void close() throws IOException {
                                s.shutdownOutput();
                            }
                        })
                });
        System.exit(0);
    }

    /**
     * Makes sure that this is Java5 or later.
     */
    private static void versionCheck() {
        String v = System.getProperty("java.class.version");
        if(v!=null) {
            try {
                if(Float.parseFloat(v)<49.0) {
                    System.err.println("Native maven support requires Java 1.5 or later, but this Maven is using "+System.getProperty("java.home"));
                    System.err.println("Please use the freestyle project.");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                // couldn't check.
            }
        }
    }

    /**
     * Called by the code in remoting to launch.
     * @throws org.codehaus.plexus.classworlds.realm.NoSuchRealmException 
     */
    public static int launch(String[] args) throws NoSuchMethodException, IllegalAccessException, NoSuchRealmException, InvocationTargetException, ClassNotFoundException {
        //ClassWorld world = ClassWorldAdapter.getInstance( launcher.getWorld() );

        ClassWorld world = launcher.getWorld();
        
        Set builtinRealms = new HashSet(world.getRealms());
        try {
            launcher.launch(args);
        } finally {
            // delete all realms created by Maven
            // this is because Maven creates a child realm for each plugin it loads,
            // and the realm id doesn't include the version.
            // so unless we discard all the realms multiple invocations
            // that use different versions of the same plugin will fail to work correctly.
            Set all = new HashSet(world.getRealms());
            all.removeAll(builtinRealms);
            for (Iterator itr = all.iterator(); itr.hasNext();) {
                ClassRealm cr = (ClassRealm) itr.next();
                world.disposeRealm(cr.getId());
            }
        }
        return launcher.getExitCode();
    }
}
