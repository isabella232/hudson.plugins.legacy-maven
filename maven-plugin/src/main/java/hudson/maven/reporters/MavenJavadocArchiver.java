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

package hudson.maven.reporters;

import hudson.FilePath;
import hudson.Util;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.JavadocArchiver.JavadocAction;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.ComponentConfigurationException;
import hudson.maven.MavenBuildProxy;
import hudson.maven.MavenModule;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenReportInfo;
import hudson.maven.MavenReporter;
import hudson.maven.MavenReporterDescriptor;
import hudson.maven.MojoInfo;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

/**
 * Records the javadoc and archives it.
 * 
 * @author Kohsuke Kawaguchi
 */
public class MavenJavadocArchiver extends MavenReporter {
    public boolean postExecute(MavenBuildProxy build, MavenProject pom, MojoInfo mojo, BuildListener listener, Throwable error) throws InterruptedException, IOException {
        if(!mojo.is("org.apache.maven.plugins","maven-javadoc-plugin","javadoc")
        && !mojo.is("org.apache.maven.plugins","maven-javadoc-plugin","aggregate"))
            return true;

        File destDir;
        boolean aggregated;
        try {
            aggregated = mojo.getConfigurationValue("aggregate",Boolean.class) || mojo.getGoal().equals("aggregate");
            if(aggregated && !pom.isExecutionRoot())
                return true;    // in the aggregated mode, the generation will only happen for the root module

            destDir = mojo.getConfigurationValue("reportOutputDirectory", File.class);
            if(destDir==null)
                destDir = mojo.getConfigurationValue("outputDirectory", File.class);
        } catch (ComponentConfigurationException e) {
            e.printStackTrace(listener.fatalError(Messages.MavenJavadocArchiver_NoDestDir()));
            build.setResult(Result.FAILURE);
            return true;
        }

        if(destDir.exists()) {
            // javadoc:javadoc just skips itself when the current project is not a java project 
            FilePath target;
            if(aggregated) {
                // store at MavenModuleSet level. 
                listener.getLogger().println("[HUDSON] Archiving aggregated javadoc");
                target = build.getModuleSetRootDir();
            } else {
                listener.getLogger().println("[HUDSON] Archiving javadoc");
                target = build.getProjectRootDir();
            }

            target = target.child("javadoc");

            try {
                new FilePath(destDir).copyRecursiveTo("**/*",target);
            } catch (IOException e) {
                Util.displayIOException(e,listener);
                e.printStackTrace(listener.fatalError(Messages.MavenJavadocArchiver_FailedToCopy(destDir,target)));
                build.setResult(Result.FAILURE);
            }

            if(aggregated)
                build.registerAsAggregatedProjectAction(this);
            else
                build.registerAsProjectAction(this);

        }

        return true;
    }

    @Override
    public boolean reportGenerated(MavenBuildProxy build, MavenProject pom, MavenReportInfo report, BuildListener listener) throws InterruptedException, IOException {
        return postExecute(build,pom,report,listener,null);
    }

    public Collection<? extends Action> getProjectActions(MavenModule project) {
        return Collections.singleton(new JavadocAction(project));
    }

    public Action getAggregatedProjectAction(MavenModuleSet project) {
        return new JavadocAction(project);
    }

    @Extension
    public static final class DescriptorImpl extends MavenReporterDescriptor {
        public String getDisplayName() {
            return Messages.MavenJavadocArchiver_DisplayName();
        }

        public MavenJavadocArchiver newAutoInstance(MavenModule module) {
            return new MavenJavadocArchiver();
        }
    }

    private static final long serialVersionUID = 1L;
}
