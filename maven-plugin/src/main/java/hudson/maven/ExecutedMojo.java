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

package hudson.maven;

import static hudson.Util.intern;
import hudson.Util;
import hudson.model.Hudson;
import hudson.remoting.Which;
import hudson.util.ReflectionUtils;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.kohsuke.stapler.Stapler;

/**
 * Persisted record of mojo execution.
 *
 * <p>
 * This information is first recorded in the maven process, then sent over
 * the remoting layer to the master, then persisted via XStream.
 *
 * @author Kohsuke Kawaguchi
 */
public final class ExecutedMojo implements Serializable {
    /**
     * Plugin group ID.
     */
    public final String groupId;
    /**
     * Plugin artifact ID.
     */
    public final String artifactId;
    /**
     * Plugin version.
     */
    public final String version;
    /**
     * Mojo name.
     */
    public final String goal;
    /**
     * Optional execution ID, when the same mojo gets executed multiple times.
     */
    public final String executionId;
    /**
     * How long did it take to execute this goal? in milli-seconds.
     * (precision might not be 1ms)
     */
    public final long duration;
    /**
     * MD5 hash of the plugin jar.
     */
    public final String digest;

    public ExecutedMojo(MojoInfo mojo, long duration) throws IOException, InterruptedException {
        this.groupId = mojo.pluginName.groupId;
        this.artifactId = mojo.pluginName.artifactId;
        this.version = mojo.pluginName.version;
        this.goal = mojo.getGoal();
        this.executionId = mojo.mojoExecution.getExecutionId();
        this.duration = duration;

        String digest = null;
        MojoDescriptor md = mojo.mojoExecution.getMojoDescriptor();
        PluginDescriptor pd = md.getPluginDescriptor();
        try {
            Class clazz = getMojoClass( md, pd );// pd.getClassRealm().loadClass(md.getImplementation());
            digest = Util.getDigestOf(new FileInputStream(Which.jarFile(clazz)));
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Failed to locate jar for "+md.getImplementation(),e);
        } catch (ClassNotFoundException e) {
            // perhaps the plugin has failed to load.
        }
        this.digest = digest;
    }
    
    private Class<?> getMojoClass(MojoDescriptor md, PluginDescriptor pd) throws ClassNotFoundException {
        try {
            return pd.getClassRealm().loadClass( md.getImplementation() );
        } catch (NoSuchMethodError e) {
            // maybe we are in maven2 build ClassRealm package has changed
            return getMojoClassForMaven2( md, pd );
        }
    }
    
    private Class<?> getMojoClassForMaven2(MojoDescriptor md, PluginDescriptor pd) throws ClassNotFoundException {
        
        Method method = ReflectionUtils.getPublicMethodNamed( pd.getClass(), "getClassRealm" );
        
        org.codehaus.classworlds.ClassRealm cl = 
            (org.codehaus.classworlds.ClassRealm) ReflectionUtils.invokeMethod( method, pd );
        
        Class<?> clazz = cl.loadClass( md.getImplementation() );
        return clazz;
       
    }
    

    /**
     * Copy constructor used for interning.
     */
    private ExecutedMojo(String groupId, String artifactId, String version, String goal, String executionId, long duration, String digest) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.goal = goal;
        this.executionId = executionId;
        this.duration = duration;
        this.digest = digest;
    }

    /**
     * Lots of {@link ExecutedMojo}s tend to have the same groupId, artifactId, etc., so interning them help
     * with memory consumption.
     *
     * TODO: better if XStream has a declarative way of marking fields as "target for intern".
     */
    ExecutedMojo readResolve() {
        return new ExecutedMojo(intern(groupId),intern(artifactId),intern(version),intern(goal),intern(executionId),duration,intern(digest));
    }

    /**
     * Returns duration in a human readable text.
     */
    public String getDurationString() {
        return Util.getTimeSpanString(duration);
    }

    public String getReadableExecutionId() {
        if(executionId==null)   return "-";
        else                    return executionId;
    }

    /**
     * Returns a hyperlink for the plugin name if there's one.
     * Otherwise null.
     */
    public String getPluginLink(Cache c) {
        MavenModule m = c.get(this);
        if(m!=null)
            return Stapler.getCurrentRequest().getContextPath()+m.getUrl();
        if(groupId.equals("org.apache.maven.plugins"))
            return "http://maven.apache.org/plugins/"+artifactId+'/';
        return null;
    }

    public String getGoalLink(Cache c) {
        if(groupId.equals("org.apache.maven.plugins"))
            return "http://maven.apache.org/plugins/"+artifactId+'/'+goal+"-mojo.html";
        return null;
    }

    /**
     * Used during the HTML rendering to cache the index.
     */
    public static final class Cache {
        /**
         * All maven modules in this Hudson by their names.
         */
        public final Map<ModuleName,MavenModule> modules = new HashMap<ModuleName,MavenModule>();

        public Cache() {
            for( MavenModule m : Hudson.getInstance().getAllItems(MavenModule.class))
                modules.put(m.getModuleName(),m);
        }

        public MavenModule get(ExecutedMojo mojo) {
            return modules.get(new ModuleName(mojo.groupId,mojo.artifactId));
        }
    }

    private static final Logger LOGGER = Logger.getLogger(ExecutedMojo.class.getName());
}
