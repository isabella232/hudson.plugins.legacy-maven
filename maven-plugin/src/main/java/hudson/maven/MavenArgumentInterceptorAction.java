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
 *    Dominik Bartholdi (imod)
 *     
 *******************************************************************************/

package hudson.maven;

import hudson.model.Action;
import hudson.util.ArgumentListBuilder;

/**
 * Provides a hook to change the arguments passed to the maven execution. This
 * enables plugins to transiently change the arguments of a maven build (e.g.
 * change the arguments for a release build).
 * 
 * @author Dominik Bartholdi (imod)
 * 
 */
public interface MavenArgumentInterceptorAction extends Action {

	/**
	 * Provides maven goals and options to start the build with. This is the
	 * preferred way to provide other goals then the default ones to a build.
	 * The goals and options returned by this method will not be persist and do
	 * not affect the default configuration.
	 * <p>
	 * This method will be called on one and only one action during a build. If
	 * there are two actions present in the build, the second will be ignored.
	 * 
	 * @param build
	 *            reference to the current build, might be used for some
	 *            calculations for the correct arguments
	 * @return the maven goals and options to start maven with. Result is
	 *         ignored if <code>null</code> or empty. Variables will be expanded
	 *         by the caller.
	 */
	public String getGoalsAndOptions(MavenModuleSetBuild build);

	/**
	 * Change/add arguments to any needs, but special care has to be taken, as
	 * the list contains every argument needed for the default execution (e.g.
	 * <code>-f /path/to/pom.xml</code> or <code>-B</code>). <br />
	 * An easy example would be to add "<code>-DskipTests</code>" to skip the
	 * test execution on request.
	 * 
	 * <p>
	 * This method is called on all present MavenArgumentInterceptorAction
	 * during a build (kind of chaining, each action can add the arguments it
	 * thinks are missing).
	 * 
	 * @param mavenargs
	 *            the calculated default maven arguments (never
	 *            <code>null</code>).
	 * @param build
	 *            reference to the current build, might be used for some
	 *            calculations for the correct arguments
	 * @return the new arguments to be used.
	 */
	public ArgumentListBuilder intercept(ArgumentListBuilder mavenargs, MavenModuleSetBuild build);

}
