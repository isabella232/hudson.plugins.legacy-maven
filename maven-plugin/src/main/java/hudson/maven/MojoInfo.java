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

import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.codehaus.classworlds.ClassRealm;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.component.configurator.converters.lookup.ConverterLookup;
import org.codehaus.plexus.component.configurator.converters.lookup.DefaultConverterLookup;
import org.codehaus.plexus.component.configurator.converters.ConfigurationConverter;
import org.codehaus.plexus.component.configurator.ComponentConfigurationException;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.lang.reflect.Method;

import hudson.util.InvocationInterceptor;
import hudson.util.ReflectionUtils;

/**
 * Information about Mojo to be executed. This object provides
 * convenient access to various mojo information, so that {@link MavenReporter}
 * implementations are shielded to some extent from Maven internals.
 *
 * <p>
 * For each mojo to be executed, this object is created and passed to
 * {@link MavenReporter}.
 *
 * @author Kohsuke Kawaguchi
 * @see MavenReporter
 * @see MavenReportInfo
 */
public class MojoInfo {
    /**
     * Object from Maven that describes the Mojo to be executed.
     */
    public final MojoExecution mojoExecution;

    /**
     * PluginName of the plugin that contains this mojo.
     */
    public final PluginName pluginName;

    /**
     * Mojo object that carries out the actual execution.
     */
    public final Mojo mojo;

    /**
     * Configuration of the mojo for the current execution.
     * This reflects the default values, as well as values configured from POM,
     * including inherited values.
     */
    public final PlexusConfiguration configuration;

    /**
     * Object that Maven uses to resolve variables like "${project}" to its
     * corresponding object.
     */
    public final ExpressionEvaluator expressionEvaluator;

    /**
     * Used to obtain a value from {@link PlexusConfiguration} as a typed object,
     * instead of String.
     */
    private final ConverterLookup converterLookup = new DefaultConverterLookup();

    public MojoInfo(MojoExecution mojoExecution, Mojo mojo, PlexusConfiguration configuration, ExpressionEvaluator expressionEvaluator) {
        this.mojo = mojo;
        this.mojoExecution = mojoExecution;
        this.configuration = configuration;
        this.expressionEvaluator = expressionEvaluator;
        this.pluginName = new PluginName(mojoExecution.getMojoDescriptor().getPluginDescriptor());
    }

    /**
     * Gets the goal name of the mojo to be executed,
     * such as "javadoc". This is local to the plugin name.
      */
    public String getGoal() {
        return mojoExecution.getMojoDescriptor().getGoal();
    }

    /**
     * Obtains the configuration value of the mojo.
     *
     * @param configName
     *      The name of the child element in the &lt;configuration> of mojo.
     * @param type
     *      The Java class of the configuration value. While every element
     *      can be read as {@link String}, often different types have a different
     *      conversion rules associated with it (for example, {@link File} would
     *      resolve relative path against POM base directory.)
     *
     * @return
     *      The configuration value either specified in POM, or inherited from
     *      parent POM, or default value if one is specified in mojo.
     *
     * @throws ComponentConfigurationException
     *      Not sure when exactly this is thrown, but it's probably when
     *      the configuration in POM is syntactically incorrect. 
     */
    public <T> T getConfigurationValue(String configName, Class<T> type) throws ComponentConfigurationException {
        PlexusConfiguration child = configuration.getChild(configName);
        if(child==null) return null;    // no such config
       
        ClassLoader cl = null;
        PluginDescriptor pd = mojoExecution.getMojoDescriptor().getPluginDescriptor();
        // for maven2 builds ClassRealm doesn't extends ClassLoader !
        // so check stuff with reflection
        Method method = ReflectionUtils.getPublicMethodNamed( pd.getClass(), "getClassRealm" );
       
        if ( ReflectionUtils.invokeMethod( method, pd ) instanceof ClassRealm)
        {
            ClassRealm cr = (ClassRealm) ReflectionUtils.invokeMethod( method, pd );
            cl = cr.getClassLoader();
        } else {
            cl = mojoExecution.getMojoDescriptor().getPluginDescriptor().getClassRealm();
        }
        ConfigurationConverter converter = converterLookup.lookupConverterForType(type);
        return type.cast(converter.fromConfiguration(converterLookup,child,type,
            // the implementation seems to expect the type of the bean for which the configuration is done
            // in this parameter, but we have no such type. So passing in a dummy
            Object.class,
            cl,
            expressionEvaluator));
    }

    /**
     * Returns true if this {@link MojoInfo} wraps the mojo of the given ID tuple.
     */
    public boolean is(String groupId, String artifactId, String mojoName) {
        return pluginName.matches(groupId,artifactId) && getGoal().equals(mojoName);
    }

    /**
     * Injects the specified value (designated by the specified field name) into the mojo,
     * and returns its old value.
     *
     * @throws NoSuchFieldException
     *      if the mojo doesn't have any field of the given name.
     * @since 1.232
     */
    public <T> T inject(String name, T value) throws NoSuchFieldException {
        for(Class c=mojo.getClass(); c!=Object.class; c=c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object oldValue = f.get(mojo);
                f.set(mojo,value);
            } catch (NoSuchFieldException e) {
                continue;
            } catch (IllegalAccessException e) {
                // shouldn't happen because we made it accessible
                IllegalAccessError x = new IllegalAccessError(e.getMessage());
                x.initCause(e);
                throw x;
            }
        }

        throw new NoSuchFieldException(name);
    }

    /**
     * Intercept the invocation from the mojo to its injected component (designated by the given field name.)
     *
     * <p>
     * Often for a {@link MavenReporter} to really figure out what's going on in a build, you'd like
     * to intercept one of the components that Maven is injecting into the mojo, and inspect its parameter
     * and return values.
     *
     * <p>
     * This mehod provides a way to do this. You specify the name of the field in the Mojo class that receives
     * the injected component, then pass in {@link InvocationInterceptor}, which will in turn be invoked
     * for every invocation on that component.
     *
     * @throws NoSuchFieldException
     *      if the specified field is not found on the mojo class, or it is found but the type is not an interface.
     * @since 1.232
     */
    public void intercept(String fieldName, final InvocationInterceptor interceptor) throws NoSuchFieldException {
        for(Class c=mojo.getClass(); c!=Object.class; c=c.getSuperclass()) {
            Field f;
            try {
                f = c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                continue;
            }

            f.setAccessible(true);
            Class<?> type = f.getType();
            if(!type.isInterface())
                throw new NoSuchFieldException(fieldName+" is of type "+type+" and it's not an interface");

            try {
                final Object oldObject = f.get(mojo);

                Object newObject = Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        return interceptor.invoke(proxy,method,args,new InvocationHandler() {
                            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                return method.invoke(oldObject,args);
                            }
                        });
                    }
                });

                f.set(mojo,newObject);
            } catch (IllegalAccessException e) {
                // shouldn't happen because we made it accessible
                IllegalAccessError x = new IllegalAccessError(e.getMessage());
                x.initCause(e);
                throw x;
            }
        }
    }
}
