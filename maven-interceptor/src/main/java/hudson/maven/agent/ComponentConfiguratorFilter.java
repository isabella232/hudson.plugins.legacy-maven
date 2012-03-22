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

import org.codehaus.plexus.component.configurator.ComponentConfigurator;
import org.codehaus.plexus.component.configurator.ComponentConfigurationException;
import org.codehaus.plexus.component.configurator.ConfigurationListener;
import org.codehaus.plexus.component.configurator.AbstractComponentConfigurator;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.classworlds.ClassRealm;

/**
 * {@link ComponentConfigurator} filter.
 * The only real method that needs to be implemented is the 5 arg version.
 * 
 * @author Kohsuke Kawaguchi
 */
class ComponentConfiguratorFilter extends AbstractComponentConfigurator {
    ComponentConfigurator core;

    public ComponentConfiguratorFilter(ComponentConfigurator core) {
        this.core = core;
    }

    @Override
    public void configureComponent(Object component, PlexusConfiguration configuration, ExpressionEvaluator expressionEvaluator, ClassRealm containerRealm, ConfigurationListener listener) throws ComponentConfigurationException {
        core.configureComponent(component, configuration, expressionEvaluator, containerRealm, listener);
    }
}
