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
*    Kohsuke Kawaguchi,   Olivier Lamy
 *     
 *
 *******************************************************************************/ 

package hudson.maven;

import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.remoting.Callable;
import hudson.remoting.Channel;
import hudson.remoting.DelegatingCallable;
import hudson.remoting.Future;
import hudson.util.IOException2;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.apache.maven.BuildFailureException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ReactorManager;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.lifecycle.LifecycleExecutorListener;
import org.apache.maven.monitor.event.EventDispatcher;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.MavenReport;
import org.codehaus.classworlds.NoSuchRealmException;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import hudson.maven.agent.Main;
import hudson.maven.agent.AbortException;
import hudson.maven.agent.PluginManagerListener;
import hudson.maven.reporters.SurefireArchiver;

/**
 * {@link Callable} that invokes Maven CLI (in process) and drives a build.
 *
 * <p>
 * As a callable, this function returns the build result.
 *
 * <p>
 * This class defines a series of event callbacks, which are invoked during the build.
 * This allows subclass to monitor the progress of a build.
 *
 * @author Kohsuke Kawaguchi
 * @since 1.133
 */
public abstract class MavenBuilder extends AbstractMavenBuilder implements DelegatingCallable<Result,IOException> {


    /**
     * Flag needs to be set at the constructor, so that this reflects
     * the setting at master.
     */
    private final boolean profile = MavenProcessFactory.profile;

    /**
     * Record all asynchronous executions as they are scheduled,
     * to make sure they are all completed before we finish.
     */
    protected transient /*final*/ List<Future<?>> futures;

    protected MavenBuilder(BuildListener listener, List<String> goals, Map<String, String> systemProps) {
        super( listener, goals, systemProps );
    }

    /**
     * Called before the whole build.
     */
    abstract void preBuild(MavenSession session, ReactorManager rm, EventDispatcher dispatcher) throws BuildFailureException, LifecycleExecutionException, IOException, InterruptedException;

    /**
     * Called after the build has completed fully.
     */
    abstract void postBuild(MavenSession session, ReactorManager rm, EventDispatcher dispatcher) throws BuildFailureException, LifecycleExecutionException, IOException, InterruptedException;

    /**
     * Called when a build enter another module.
     */
    abstract void preModule(MavenProject project) throws InterruptedException, IOException, AbortException;

    /**
     * Called when a build leaves a module.
     */
    abstract void postModule(MavenProject project) throws InterruptedException, IOException, AbortException;

    /**
     * Called before a mojo is executed
     */
    abstract void preExecute(MavenProject project, MojoInfo mojoInfo) throws IOException, InterruptedException, AbortException;

    /**
     * Called after a mojo has finished executing.
     */
    abstract void postExecute(MavenProject project, MojoInfo mojoInfo, Exception exception) throws IOException, InterruptedException, AbortException;

    /**
     * Called after a {@link MavenReport} is successfully generated.
     */
    abstract void onReportGenerated(MavenProject project, MavenReportInfo report) throws IOException, InterruptedException, AbortException;

    private Class<?> pluginManagerInterceptorClazz;
    
    private Class<?> lifecycleInterceptorClazz;
    
    /**
     * This code is executed inside the maven jail process.
     */
    public Result call() throws IOException {
        
        // hold a ref on correct classloader for finally call as something is changing tccl 
        // and not restore it !
        ClassLoader mavenJailProcessClassLoader = Thread.currentThread().getContextClassLoader();        
        
        try {

            futures = new ArrayList<Future<?>>();
            Adapter a = new Adapter(this);
            callSetListenerWithReflectOnInterceptors( a, mavenJailProcessClassLoader );
            
            /*
            PluginManagerInterceptor.setListener(a);
            LifecycleExecutorInterceptor.setListener(a);
            */
            
            markAsSuccess = false;

            // working around NPE when someone puts a null value into systemProps.
            for (Map.Entry<String,String> e : systemProps.entrySet()) {
                if (e.getValue()==null)
                    throw new IllegalArgumentException("System property "+e.getKey()+" has a null value");
                System.getProperties().put(e.getKey(), e.getValue());
            }

            listener.getLogger().println(formatArgs(goals));
            int r = Main.launch(goals.toArray(new String[goals.size()]));

            // now check the completion status of async ops
            boolean messageReported = false;
            long startTime = System.nanoTime();
            for (Future<?> f : futures) {
                try {
                    if(!f.isDone() && !messageReported) {
                        messageReported = true;
                        listener.getLogger().println(Messages.MavenBuilder_Waiting());
                    }
                    f.get();
                } catch (InterruptedException e) {
                    // attempt to cancel all asynchronous tasks
                    for (Future<?> g : futures)
                        g.cancel(true);
                    listener.getLogger().println(Messages.MavenBuilder_Aborted());
                    return Result.ABORTED;
                } catch (ExecutionException e) {
                    e.printStackTrace(listener.error(Messages.MavenBuilder_AsyncFailed()));
                }
            }
            a.overheadTime += System.nanoTime()-startTime;
            futures.clear();

            if(profile) {
                NumberFormat n = NumberFormat.getInstance();
                PrintStream logger = listener.getLogger();
                logger.println("Total overhead was "+format(n,a.overheadTime)+"ms");
                Channel ch = Channel.current();
                logger.println("Class loading "   +format(n,ch.classLoadingTime.get())   +"ms, "+ch.classLoadingCount+" classes");
                logger.println("Resource loading "+format(n,ch.resourceLoadingTime.get())+"ms, "+ch.resourceLoadingCount+" times");                
            }

            if(r==0)    return Result.SUCCESS;

            if(markAsSuccess) {
                listener.getLogger().println(Messages.MavenBuilder_Failed());
                return Result.SUCCESS;
            }
            return Result.FAILURE;
        } catch (NoSuchMethodException e) {
            throw new IOException2(e);
        } catch (IllegalAccessException e) {
            throw new IOException2(e);
        } catch (RuntimeException e) {
            throw new IOException2(e);
        } catch (InvocationTargetException e) {
            throw new IOException2(e);
        } catch (ClassNotFoundException e) {
            throw new IOException2(e);
        }
        catch ( NoSuchRealmException e ) {
            throw new IOException2(e);
        } finally {
            //PluginManagerInterceptor.setListener(null);
            //LifecycleExecutorInterceptor.setListener(null);
            callSetListenerWithReflectOnInterceptorsQuietly( null, mavenJailProcessClassLoader );
        }
    }

    private void callSetListenerWithReflectOnInterceptors( PluginManagerListener pluginManagerListener, ClassLoader cl )
        throws ClassNotFoundException, SecurityException, NoSuchMethodException, IllegalArgumentException,
        IllegalAccessException, InvocationTargetException
    {
        if (pluginManagerInterceptorClazz == null)
        {
            pluginManagerInterceptorClazz = cl.loadClass( "hudson.maven.agent.PluginManagerInterceptor" );
        }
        Method setListenerMethod =
            pluginManagerInterceptorClazz.getMethod( "setListener",
                                                     new Class[] { cl.loadClass( "hudson.maven.agent.PluginManagerListener" ) } );
        setListenerMethod.invoke( null, new Object[] { pluginManagerListener } );

        if (lifecycleInterceptorClazz == null)
        {
            lifecycleInterceptorClazz = cl.loadClass( "org.apache.maven.lifecycle.LifecycleExecutorInterceptor" );
        }
        setListenerMethod =
            lifecycleInterceptorClazz.getMethod( "setListener",
                                                 new Class[] { cl.loadClass( "org.apache.maven.lifecycle.LifecycleExecutorListener" ) } );

        setListenerMethod.invoke( null, new Object[] { pluginManagerListener } );
    }
    
    private void callSetListenerWithReflectOnInterceptorsQuietly( PluginManagerListener pluginManagerListener, ClassLoader cl )
    {
        try
        {
            callSetListenerWithReflectOnInterceptors(pluginManagerListener, cl);
        }
        catch ( SecurityException e )
        {
            throw new RuntimeException( e.getMessage(), e );
        }
        catch ( IllegalArgumentException e )
        {
            throw new RuntimeException( e.getMessage(), e );
        }
        catch ( ClassNotFoundException e )
        {
            throw new RuntimeException( e.getMessage(), e );
        }
        catch ( NoSuchMethodException e )
        {
            throw new RuntimeException( e.getMessage(), e );
        }
        catch ( IllegalAccessException e )
        {
            throw new RuntimeException( e.getMessage(), e );
        }
        catch ( InvocationTargetException e )
        {
            throw new RuntimeException( e.getMessage(), e );
        }
    }  


    /**
     * Receives {@link PluginManagerListener} and {@link LifecycleExecutorListener} events
     * and converts them to {@link MavenBuilder} events.
     */
    private static final class Adapter implements PluginManagerListener, LifecycleExecutorListener {
        /**
         * Used to detect when to fire {@link MavenReporter#enterModule}
         */
        private MavenProject lastModule;

        private final MavenBuilder listener;

        /**
         * Number of total nanoseconds {@link MavenBuilder} spent.
         */
        long overheadTime;

        public Adapter(MavenBuilder listener) {
            this.listener = listener;
        }

        public void preBuild(MavenSession session, ReactorManager rm, EventDispatcher dispatcher) throws BuildFailureException, LifecycleExecutionException, IOException, InterruptedException {
            long startTime = System.nanoTime();
            listener.preBuild(session, rm, dispatcher);
            overheadTime += System.nanoTime()-startTime;
        }

        public void postBuild(MavenSession session, ReactorManager rm, EventDispatcher dispatcher) throws BuildFailureException, LifecycleExecutionException, IOException, InterruptedException {
            long startTime = System.nanoTime();
            fireLeaveModule();
            listener.postBuild(session, rm, dispatcher);
            overheadTime += System.nanoTime()-startTime;
        }

        public void endModule() throws InterruptedException, IOException {
            long startTime = System.nanoTime();
            fireLeaveModule();
            overheadTime += System.nanoTime()-startTime;
        }

        public void preExecute(MavenProject project, MojoExecution exec, Mojo mojo, PlexusConfiguration mergedConfig, ExpressionEvaluator eval) throws IOException, InterruptedException {
            long startTime = System.nanoTime();
            if(lastModule!=project) {
                // module change
                fireLeaveModule();
                fireEnterModule(project);
            }

            listener.preExecute(project, new MojoInfo(exec, mojo, mergedConfig, eval));
            overheadTime += System.nanoTime()-startTime;
        }

        public void postExecute(MavenProject project, MojoExecution exec, Mojo mojo, PlexusConfiguration mergedConfig, ExpressionEvaluator eval, Exception exception) throws IOException, InterruptedException {
            long startTime = System.nanoTime();
            listener.postExecute(project, new MojoInfo(exec, mojo, mergedConfig, eval),exception);
            overheadTime += System.nanoTime()-startTime;
        }

        public void onReportGenerated(MavenReport report, MojoExecution mojoExecution, PlexusConfiguration mergedConfig, ExpressionEvaluator eval) throws IOException, InterruptedException {
            long startTime = System.nanoTime();
            listener.onReportGenerated(lastModule,new MavenReportInfo(mojoExecution,report,mergedConfig,eval));
            overheadTime += System.nanoTime()-startTime;
        }

        private void fireEnterModule(MavenProject project) throws InterruptedException, IOException {
            lastModule = project;
            listener.preModule(project);
        }

        private void fireLeaveModule() throws InterruptedException, IOException {
            if(lastModule!=null) {
                listener.postModule(lastModule);
                lastModule = null;
            }
        }
    }

    /**
     * Used by selected {@link MavenReporter}s to notify the maven build agent
     * that even though Maven is going to fail, we should report the build as
     * success.
     *
     * <p>
     * This rather ugly hook is necessary to mark builds as unstable, since
     * maven considers a test failure to be a build failure, which will otherwise
     * mark the build as FAILED.
     *
     * <p>
     * It's OK for this field to be static, because the JVM where this is actually
     * used is in the Maven JVM, so only one build is going on for the whole JVM.
     *
     * <p>
     * Even though this field is public, please consider this field reserved
     * for {@link SurefireArchiver}. Subject to change without notice.
     */
    public static boolean markAsSuccess;

    private static final long serialVersionUID = 1L;
}
