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

/**
 * A part of {@link MavenBuildProxy} that's used internally
 * for aggregated build. Fired and consumed internally and
 * not exposed to plugins.
 *
 * @author Kohsuke Kawaguchi
 */
public interface MavenBuildProxy2 extends MavenBuildProxy {
    /**
     * Notifies that the build has entered a module.
     */
    void start();

    /**
     * Notifies that the build has left a module.
     */
    void end();

    /**
     * Maven produces additional error message after the module build is done.
     * So to catch those messages, invoke this method on the last module that was built
     * after all the Maven processing is done, to append last messages to the console
     * output of the module.
     */
    void appendLastLog();

    /**
     * Filter for {@link MavenBuildProxy2}.
     *
     * Meant to be useful as the base class for other filters.
     */
    /*package*/ static abstract class Filter<CORE extends MavenBuildProxy2> extends MavenBuildProxy.Filter<CORE> implements MavenBuildProxy2 {
        protected Filter(CORE core) {
            super(core);
        }

        public void start() {
            core.start();
        }

        public void end() {
            core.end();
        }

        public void appendLastLog() {
            core.appendLastLog();
        }
    }
}
