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
*    Tom Huybrechts
 *     
 *
 *******************************************************************************/ 

package hudson.maven;

import hudson.Plugin;
import hudson.XmlFile;
import hudson.model.Items;
import hudson.model.Run;
import hudson.maven.MavenModuleSet.DescriptorImpl;
import hudson.maven.reporters.MavenAggregatedArtifactRecord;
import hudson.maven.reporters.MavenArtifact;
import hudson.maven.reporters.MavenArtifactRecord;
import hudson.maven.reporters.MavenMailer;
import hudson.maven.reporters.SurefireAggregatedReport;
import hudson.maven.reporters.SurefireArchiver;
import hudson.maven.reporters.SurefireReport;

/**
 * @author huybrechts
 */
public class PluginImpl extends Plugin {
    @Override
    public void start() throws Exception {
        setXtreamAliasForBackwardCompatibility();
    }
    
    /**
     * Register XStream aliases for backward compatibility - should be removed eventually
     */
    public static void setXtreamAliasForBackwardCompatibility(){
        XmlFile.DEFAULT_XSTREAM.alias("hudson.maven.MavenModuleSet$DescriptorImpl", DescriptorImpl.class);
        
        Items.XSTREAM.alias("maven2", MavenModule.class);
        Items.XSTREAM.alias("dependency", ModuleDependency.class);
        Items.XSTREAM.alias("maven2-module-set", MavenModule.class);  // this was a bug, but now we need to keep it for compatibility
        Items.XSTREAM.alias("maven2-moduleset", MavenModuleSet.class);
        Items.XSTREAM.alias("hudson.maven.reporters.MavenMailer", MavenMailer.class);
        Items.XSTREAM.alias("hudson.maven.RedeployPublisher", RedeployPublisher.class);

        Run.XSTREAM.alias("hudson.maven.MavenModuleSetBuild", MavenModuleSetBuild.class);
        Run.XSTREAM.alias("hudson.maven.reporters.MavenAggregatedArtifactRecord", MavenAggregatedArtifactRecord.class);
        Run.XSTREAM.alias("hudson.maven.reporters.SurefireAggregatedReport", SurefireAggregatedReport.class);
        Run.XSTREAM.alias("hudson.maven.reporters.SurefireReport", SurefireReport.class);
        Run.XSTREAM.alias("hudson.maven.reporters.MavenArtifactRecord", MavenArtifactRecord.class);
        Run.XSTREAM.alias("hudson.maven.reporters.SurefireArchiver$FactoryImpl", SurefireArchiver.FactoryImpl.class);
        Run.XSTREAM.alias("hudson.maven.reporters.MavenArtifact", MavenArtifact.class);
    }

}
