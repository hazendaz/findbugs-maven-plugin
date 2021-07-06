package org.codehaus.mojo.spotbugs

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import groovy.xml.XmlSlurper
import groovy.xml.StreamingMarkupBuilder

import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.repository.ArtifactRepository

import org.apache.maven.doxia.siterenderer.Renderer
import org.apache.maven.doxia.tools.SiteTool

import org.apache.maven.execution.MavenSession

import org.apache.maven.plugin.MojoExecutionException

import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope

import org.apache.maven.project.MavenProject

import org.apache.maven.reporting.AbstractMavenReport

import org.apache.maven.repository.RepositorySystem

import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver
import org.codehaus.plexus.resource.ResourceManager
import org.codehaus.plexus.resource.loader.FileResourceCreationException
import org.codehaus.plexus.resource.loader.FileResourceLoader

/**
 * Generates a SpotBugs Report when the site plugin is run.
 * The HTML report is generated for site commands only.
 */
@Mojo(name = "spotbugs", requiresDependencyResolution = ResolutionScope.TEST, requiresProject = true, threadSafe = true)
class SpotBugsMojo extends AbstractMavenReport implements SpotBugsPluginsTrait {

    /**
     * Location where generated html will be created.
     *
     */
    @Parameter(defaultValue = '${project.reporting.outputDirectory}', required = true)
    File outputDirectory

    /**
     * Turn on and off xml output of the Spotbugs report.
     *
     * @since 1.0.0
     */
    @Parameter(defaultValue = "false", property = "spotbugs.xmlOutput", required = true)
    boolean xmlOutput

    /**
     * Turn on and off HTML output of the Spotbugs report.
     *
     * @since 1.0.0
     */
    @Parameter(defaultValue = "false", property = "spotbugs.htmlOutput", required = true)
    boolean htmlOutput

    /**
     * Specifies the directory where the xml output will be generated.
     *
     * @since 1.0.0
     */
    @Parameter(defaultValue = '${project.build.directory}', required = true)
    File xmlOutputDirectory

    /**
     * This has been deprecated and is on by default.
     *
     * @since 1.2.0
     *
     */
    @Deprecated
    @Parameter(defaultValue = "true")
    boolean spotbugsXmlOutput

    /**
     * Specifies the directory where the Spotbugs native xml output will be generated.
     *
     * @since 1.2.0
     */
    @Parameter(defaultValue = '${project.build.directory}', required = true)
    File spotbugsXmlOutputDirectory

    /**
     * Set the name of the output XML file produced
     *
     * @since 3.1.12.2
     */
    @Parameter(property = "spotbugs.outputXmlFilename", defaultValue = "spotbugsXml.xml")
    String spotbugsXmlOutputFilename

    /**
     * Doxia Site Renderer.
     */
    @Component(role = Renderer.class)
    Renderer siteRenderer

    /**
     * Directory containing the class files for Spotbugs to analyze.
     */
    @Parameter(defaultValue = '${project.build.outputDirectory}', required = true)
    File classFilesDirectory

    /**
     * Directory containing the test class files for Spotbugs to analyze.
     *
     */
    @Parameter(defaultValue = '${project.build.testOutputDirectory}', required = true)
    File testClassFilesDirectory

    /**
     * Location of the Xrefs to link to.
     *
     */
    @Parameter(defaultValue = '${project.reporting.outputDirectory}/xref')
    File xrefLocation

    /**
     * Location of the Test Xrefs to link to.
     *
     */
    @Parameter(defaultValue = '${project.reporting.outputDirectory}/xref-test')
    File xrefTestLocation

    /**
     * The directories containing the sources to be compiled.
     *
     */
    @Parameter(defaultValue = '${project.compileSourceRoots}', required = true, readonly = true)
    List compileSourceRoots

    /**
     * The directories containing the test-sources to be compiled.
     *
     * @since 2.0
     */
    @Parameter(defaultValue = '${project.testCompileSourceRoots}', required = true, readonly = true)
    List testSourceRoots

    /**
     * Run Spotbugs on the tests.
     *
     * @since 2.0
     */
    @Parameter(defaultValue = "false", property = "spotbugs.includeTests")
    boolean includeTests

    /**
     * Run Spotbugs with -sourcepath parameter populated with the known source roots.
     *
     */
    @Parameter(defaultValue = "false", property = "spotbugs.addSourceDirs")
    boolean addSourceDirs

    /**
     * List of artifacts this plugin depends on. Used for resolving the Spotbugs core plugin.
     *
     */
    @Parameter(property = "plugin.artifacts", required = true, readonly = true)
    List pluginArtifacts

    /**
     * List of Remote Repositories used by the resolver.
     *
     */
    @Parameter(property = "project.remoteArtifactRepositories", required = true, readonly = true)
    List remoteRepositories

    /**
     * The local repository, needed to download the coreplugin jar.
     *
     */
    @Parameter(property = "localRepository", required = true, readonly = true)
    ArtifactRepository localRepository

    /**
     * Remote repositories which will be searched for the coreplugin jar.
     *
     */
    @Parameter(property = "project.remoteArtifactRepositories", required = true, readonly = true)
    List remoteArtifactRepositories

    /**
     * Maven Session.
     */
    @Parameter (defaultValue = '${session}', required = true, readonly = true)
    MavenSession session;

    /**
     * Maven Project.
     *
     */
    @Parameter(property = "project", required = true, readonly = true)
    MavenProject project

    /**
     * Encoding used for xml files. Default value is UTF-8.
     *
     */
    @Parameter(defaultValue = "UTF-8", readonly = true)
    String xmlEncoding

    /**
     * The file encoding to use when reading the source files. If the property <code>project.build.sourceEncoding</code>
     * is not set, the platform default encoding is used.
     *
     * @since 2.2
     */
    @Parameter(defaultValue = '${project.build.sourceEncoding}', property = "encoding")
    String sourceEncoding

    /**
     * The file encoding to use when creating the HTML reports. If the property <code>project.reporting.outputEncoding</code>
     * is not set, the platform default encoding is used.
     *
     * @since 2.2
     */
    @Parameter(defaultValue = '${project.reporting.outputEncoding}', property = "outputEncoding")
    String outputEncoding

    /**
     * Threshold of minimum bug severity to report. Valid values are High, Default, Low, Ignore, and Exp (for experimental).
     *
     */
    @Parameter(defaultValue = "Default", property = "spotbugs.threshold")
    String threshold

    /**
     * Artifact resolver, needed to download the coreplugin jar.
     */
    @Component(role = ArtifactResolver.class)
    ArtifactResolver artifactResolver

    /**
     * Used to look up Artifacts in the remote repository.
     *
     */
    @Component(role = RepositorySystem.class)
    RepositorySystem factory

    /**
     * <p>
     * File name of the include filter. Only bugs in matching the filters are reported.
     * </p>
     *
     * <p>
     * Potential values are a filesystem path, a URL, or a classpath resource.
     * </p>
     *
     * <p>
     * This parameter is resolved as resource, URL, then file. If successfully
     * resolved, the contents of the configuration is copied into the
     * <code>${project.build.directory}</code>
     * directory before being passed to Spotbugs as a filter file.
     * It supports multiple files separated by a comma
     * </p>
     *
     * @since 1.0-beta-1
     */
    @Parameter(property = "spotbugs.includeFilterFile")
    String includeFilterFile

    /**
     * <p>
     * File name of the exclude filter. Bugs matching the filters are not reported.
     * </p>
     *
     * <p>
     * Potential values are a filesystem path, a URL, or a classpath resource.
     * </p>
     *
     * <p>
     * This parameter is resolved as resource, URL, then file. If successfully
     * resolved, the contents of the configuration is copied into the
     * <code>${project.build.directory}</code>
     * directory before being passed to Spotbugs as a filter file.
     * It supports multiple files separated by a comma
     * </p>
     *
     * @since 1.0-beta-1
     */
    @Parameter(property = "spotbugs.excludeFilterFile")
    String excludeFilterFile

    /**
     * <p>
     * File names of the baseline files. Bugs found in the baseline files won't be reported.
     * </p>
     *
     * <p>
     * Potential values are a filesystem path, a URL, or a classpath resource.
     * </p>
     *
     * <p>
     * This parameter is resolved as resource, URL, then file. If successfully
     * resolved, the contents of the configuration is copied into the
     * <code>${project.build.directory}</code>
     * directory before being passed to Spotbugs as a filter file.
     * </p>
     *
     * This is a comma-delimited list.
     *
     * @since 2.4.1
     */
    @Parameter(property = "spotbugs.excludeBugsFile")
    String excludeBugsFile

    /**
     * Effort of the bug finders. Valid values are Min, Default and Max.
     *
     * @since 1.0-beta-1
     */
    @Parameter(defaultValue = "Default", property = "spotbugs.effort")
    String effort

    /**
     * Turn on Spotbugs debugging.
     *
     */
    @Parameter(defaultValue = "false", property = "spotbugs.debug")
    Boolean debug

    /**
     * Relaxed reporting mode. For many detectors, this option suppresses the heuristics used to avoid reporting false
     * positives.
     *
     * @since 1.1
     */
    @Parameter(defaultValue = "false", property = "spotbugs.relaxed")
    Boolean relaxed

    /**
     * The visitor list to run. This is a comma-delimited list.
     *
     * @since 1.0-beta-1
     */
    @Parameter(property = "spotbugs.visitors")
    String visitors

    /**
     * The visitor list to omit. This is a comma-delimited list.
     *
     * @since 1.0-beta-1
     */
    @Parameter(property = "spotbugs.omitVisitors")
    String omitVisitors

    /**
     * <p>
     * The plugin list to include in the report. This is a comma-delimited list.
     * </p>
     *
     * <p>
     * Potential values are a filesystem path, a URL, or a classpath resource.
     * </p>
     *
     * <p>
     * This parameter is resolved as resource, URL, then file. If successfully
     * resolved, the contents of the configuration is copied into the
     * <code>${project.build.directory}</code>
     * directory before being passed to Spotbugs as a plugin file.
     * </p>
     *
     * @since 1.0-beta-1
     */
    @Parameter(property = "spotbugs.pluginList")
    String pluginList

    /**
     * <p>
     * Collection of PluginArtifact to work on. (PluginArtifact contains groupId, artifactId, version, type.)
     * See <a href="./usage.html#Using Detectors from a Repository">Usage</a> for details.
     * </p>
     *
     *
     * @since 2.4.1
     */
    @Parameter
    PluginArtifact[] plugins;

    /**
     * Restrict analysis to the given comma-separated list of classes and packages.
     *
     * @since 1.1
     */
    @Parameter(property = "spotbugs.onlyAnalyze")
    String onlyAnalyze

    /**
     * This option enables or disables scanning of nested jar and zip files found
     * in the list of files and directories to be analyzed.
     *
     * @since 2.3.2
     */
    @Parameter(property = "spotbugs.nested", defaultValue = "false")
    Boolean nested

    /**
     * Prints a trace of detectors run and classes analyzed to standard output.
     * Useful for troubleshooting unexpected analysis failures.
     *
     * @since 2.3.2
     */
    @Parameter(property = "spotbugs.trace", defaultValue = "false")
    Boolean trace

    /**
     * Maximum bug ranking to record.
     *
     * @since 2.4.1
     */
    @Parameter(property = "spotbugs.maxRank")
    int maxRank

    /**
     * Skip entire check.
     *
     * @since 1.1
     */
    @Parameter(property = "spotbugs.skip", defaultValue = "false")
    boolean skip

    /**
     * Resource Manager.
     *
     * @since 2.0
     */
    @Component(role = ResourceManager.class)
    ResourceManager resourceManager

    /**
     * SiteTool.
     *
     * @since 2.1-SNAPSHOT
     */
    @Component(role = SiteTool.class)
    SiteTool siteTool

    /**
     * Fail the build on an error.
     *
     * @since 2.0
     */
    @Parameter(property = "spotbugs.failOnError", defaultValue = "true")
    boolean failOnError

    /**
     * Fork a VM for Spotbugs analysis.  This will allow you to set timeouts and heap size.
     *
     * @since 2.3.2
     */
    @Parameter(property = "spotbugs.fork", defaultValue = "true")
    boolean fork

    /**
     * Maximum Java heap size in megabytes  (default=512).
     * This only works if the <b>fork</b> parameter is set <b>true</b>.
     *
     * @since 2.2
     */
    @Parameter(property = "spotbugs.maxHeap", defaultValue = "512")
    int maxHeap

    /**
     * Specifies the amount of time, in milliseconds, that Spotbugs may run before
     * it is assumed to be hung and is terminated.
     * The default is 600,000 milliseconds, which is ten minutes.
     * This only works if the <b>fork</b> parameter is set <b>true</b>.
     *
     * @since 2.2
     */
    @Parameter(property = "spotbugs.timeout", defaultValue = "600000")
    int timeout

    /**
     * <p>
     * The arguments to pass to the forked VM (ignored if fork is disabled).
     * </p>
     *
     * @since 2.4.1
     */
    @Parameter(property = "spotbugs.jvmArgs")
    String jvmArgs

    /**
     * Skip the Spotbugs HTML report generation if there are no violations found. Defaults to
     * <code>false</code>.
     *
     * @since 3.0.1
     */
    @Parameter(property = "spotbugs.skipEmptyReport", defaultValue = "false")
    boolean skipEmptyReport

    /**
     * Set the path of the user preferences file to use.
     * Will try to read the path as a resource before treating it as a local path.
     *
     * This will read in a configuration file to set up Spotbugs.
     *
     * The parameters in the POM file will override anything in the config file
     *
     * @since 3.0.2
     */
    @Parameter(property = "spotbugs.userPrefs")
    String userPrefs

    /**
     * <p>
     * System properties to set in the VM (or the forked VM if fork is enabled).
     * <p>
     *
     * @since 4.3.0
     */
    @Parameter(property = "spotbugs.systemPropertyVariables")
    Map<String, String> systemPropertyVariables

    int bugCount

    int errorCount

    ResourceBundle bundle

    File outputSpotbugsFile

    /**
     * Checks whether prerequisites for generating this report are given.
     *
     * @return true if report can be generated, otherwise false
     * @see org.apache.maven.reporting.MavenReport#canGenerateReport()
     */
    @Override
    boolean canGenerateReport() {

        def canGenerate = false
        log.debug("****** SpotBugsMojo canGenerateReport *******")

        if (!skip && classFilesDirectory.exists()) {

            classFilesDirectory.eachFileRecurse {
                if (it.name.contains(SpotBugsInfo.CLASS_SUFFIX)) {
                    canGenerate = true
                }
            }
            log.debug("canGenerate Src is ${canGenerate}")
        }

        if (!skip && testClassFilesDirectory.exists() && includeTests) {

            testClassFilesDirectory.eachFileRecurse {
                if (it.name.contains(SpotBugsInfo.CLASS_SUFFIX)) {
                    canGenerate = true
                }
            }
            log.debug("canGenerate Test Src is ${canGenerate}")
        }

        if (canGenerate && outputSpotbugsFile == null) {
            outputSpotbugsFile = new File("${spotbugsXmlOutputDirectory}/${spotbugsXmlOutputFilename}")

            executeSpotbugs(outputSpotbugsFile)

            if (skipEmptyReport && bugCount == 0) {
                canGenerate = false
            }
        }

        log.debug("canGenerate is ${canGenerate}")

        return canGenerate
    }

    /**
     * Returns the plugins description for the "generated reports" overview page.
     *
     * @param locale
     *            the locale the report should be generated for
     *
     * @return description of the report
     * @see org.apache.maven.reporting.MavenReport#getDescription(java.util.Locale)
     */
    @Override
    String getDescription(Locale locale) {
        return getBundle(locale).getString(SpotBugsInfo.DESCRIPTION_KEY)
    }

    /**
     * Returns the plugins name for the "generated reports" overview page and the menu.
     *
     * @param locale
     *            the locale the report should be generated for
     *
     * @return name of the report
     * @see org.apache.maven.reporting.MavenReport#getName(java.util.Locale)
     */
    @Override
    String getName(Locale locale) {
        return getBundle(locale).getString(SpotBugsInfo.NAME_KEY)
    }

    /**
     * Returns report output file name, without the extension.
     *
     * Called by AbstractMavenReport.execute() for creating the sink.
     *
     * @return name of the generated page
     * @see org.apache.maven.reporting.MavenReport#getOutputName()
     */
    @Override
    String getOutputName() {
        return SpotBugsInfo.PLUGIN_NAME
    }

    /**
     * Executes the generation of the report.
     *
     * Callback from Maven Site Plugin.
     *
     * @param locale he wanted locale to generate the report, could be null.
     *
     * @see org.apache.maven.reporting.MavenReport #executeReport(java.util.Locale)
     */
    @Override
    void executeReport(Locale locale) {

        log.debug("****** SpotBugsMojo executeReport *******")
        executeCheck(locale)

        if (!skip && canGenerateReport()) {

            if (log.isDebugEnabled()) {
                log.debug("Locale is ${locale.getLanguage()}")
                log.debug("****** SpotBugsMojo executeReport *******")
                log.debug("report Output Directory is " + getReportOutputDirectory())
                log.debug("Output Directory is " + outputDirectory)
                log.debug("Classes Directory is " + classFilesDirectory)
                log.debug("  Plugin Artifacts to be added ->" + pluginArtifacts.toString())
            }

            generateXDoc(locale)

            if (!outputDirectory.exists()) {
                if (!outputDirectory.mkdirs()) {
                    throw new MojoExecutionException("Cannot create html output directory")
                }
            }

            if (outputSpotbugsFile != null && outputSpotbugsFile.exists()) {

                if (skipEmptyReport && bugCount == 0) {
                    log.info("Skipping Generation Spotbugs HTML since there are not any bugs")
                } else {
                    log.debug("Generating Spotbugs HTML")

                    SpotbugsReportGenerator generator = new SpotbugsReportGenerator(getSink(), getBundle(locale), this.project.getBasedir(), siteTool)

                    boolean isJxrPluginEnabled = isJxrPluginEnabled()

                    generator.setIsJXRReportEnabled(isJxrPluginEnabled)

                    if (isJxrPluginEnabled) {
                        generator.setCompileSourceRoots(this.compileSourceRoots)
                        generator.setTestSourceRoots(this.testSourceRoots)
                        generator.setXrefLocation(this.xrefLocation)
                        generator.setXrefTestLocation(this.xrefTestLocation)
                        generator.setIncludeTests(this.includeTests)
                    }

                    generator.setLog(log)

                    generator.threshold = threshold

                    generator.effort = effort

                    generator.setSpotbugsResults(new XmlSlurper().parse(outputSpotbugsFile))

                    generator.setOutputDirectory(new File(outputDirectory.getAbsolutePath()))

                    generator.generateReport()

                    log.debug("xmlOutput is ${xmlOutput}")

                }
            }
        } else {
            log.info("cannot generate report");
        }
    }

    @Override
    public void execute() {
        log.debug("****** SpotBugsMojo execute *******")

        Locale locale = Locale.getDefault()
        if (!skip) {
            executeCheck(locale)
            if (canGenerateReport()) {
                generateXDoc(locale)
            }
        }
    }

    private void executeCheck(Locale locale) {
        log.debug("****** SpotBugsMojo executeCheck *******")

        log.debug("Generating Spotbugs XML")

        if (!spotbugsXmlOutputDirectory.exists()) {
            if (!spotbugsXmlOutputDirectory.mkdirs()) {
                throw new MojoExecutionException("Cannot create xml output directory")
            }
        }
    }

    private void generateXDoc(Locale locale) {
        log.debug("****** SpotBugsMojo generateXDoc *******")

        if (outputSpotbugsFile != null && outputSpotbugsFile.exists()) {

            log.debug("xmlOutput is ${xmlOutput}")

            if (xmlOutput) {
                log.debug("  Using the xdoc format")

                if (!xmlOutputDirectory.exists()) {
                    if (!xmlOutputDirectory.mkdirs()) {
                        throw new MojoExecutionException("Cannot create xdoc output directory")
                    }
                }

                XDocsReporter xDocsReporter = new XDocsReporter(getBundle(locale), log, threshold, effort, outputEncoding)
                xDocsReporter.setOutputWriter(new OutputStreamWriter(new FileOutputStream(new File("${xmlOutputDirectory}/spotbugs.xml")), outputEncoding))
                xDocsReporter.setSpotbugsResults(new XmlSlurper().parse(outputSpotbugsFile))
                xDocsReporter.setCompileSourceRoots(this.compileSourceRoots)
                xDocsReporter.setTestSourceRoots(this.testSourceRoots)

                xDocsReporter.generateReport()
            }
        }
    }

    /**
     * Returns the report output directory.
     *
     * Called by AbstractMavenReport.execute() for creating the sink.
     *
     * @return full path to the directory where the files in the site get copied to
     * @see org.apache.maven.reporting.AbstractMavenReport#getOutputDirectory()
     */
    @Override
    protected String getOutputDirectory() {
        return outputDirectory.getAbsolutePath()
    }

    /**
     * Return the project.
     *
     * @return the project.
     * @see org.apache.maven.reporting.AbstractMavenReport#getProject()
     */
    @Override
    protected MavenProject getProject() {
        return this.project
    }

    /**
     * Return the Site Renderer.
     *
     */
    @Override
    protected Renderer getSiteRenderer() {
        return this.siteRenderer
    }

    /**
     * Determines if the JXR-Plugin is included in the report section of the POM.
     *
     * @param bundle
     *            The bundle to load the artifactIf of the jxr plugin.
     * @return True if the JXR-Plugin is included in the POM, false otherwise.
     *
     */
    protected boolean isJxrPluginEnabled() {
        boolean isEnabled = false

        if (xrefLocation.exists()) {
            isEnabled = true
            return isEnabled
        }

        List reportPlugins = getProject().getReportPlugins()

        reportPlugins.each() { reportPlugin ->

            log.debug("report plugin -> ${reportPlugin.getArtifactId()}")
            if ("maven-jxr-plugin".equals(reportPlugin.getArtifactId()) || "jxr-maven-plugin".equals(reportPlugin.getArtifactId())) {
                isEnabled = true
            }
        }

        log.debug("jxr report links are ${isEnabled ? "enabled" : "disabled"}")
        return isEnabled
    }

    ResourceBundle getBundle(locale) {

        this.bundle = ResourceBundle.getBundle(SpotBugsInfo.BUNDLE_NAME, locale, SpotBugsMojo.class.getClassLoader())

        log.debug("Mojo Locale is " + this.bundle.getLocale().getLanguage())

        return bundle
    }

    /**
     * Get the Spotbugs command line arguments.
     *
     * @param Spotbugs temp output file
     *
     * @return Spotbugs command line arguments.
     *
     */
    private ArrayList<String> getSpotbugsArgs(File tempFile) {
        ResourceHelper resourceHelper = new ResourceHelper(log, spotbugsXmlOutputDirectory, resourceManager)
        def args = new ArrayList<String>()

        if(userPrefs) {
            log.debug(" Adding User Preferences File -> ${userPrefs}" )

            args << "-userPrefs"
            args << resourceHelper.getResourceFile(userPrefs.trim())
        }

        args << "-xml:withMessages"

        args << "-auxclasspathFromInput"

        args << "-projectName"
        args << "${project.name}"

        args << getEffortParameter()
        args << getThresholdParameter()

        if (debug) {
            log.debug("progress on")
            args << "-progress"
        }

        if (htmlOutput) {
            log.debug("HTML output ")
            args << "-html"
        }

        if (pluginList || plugins) {
            args << "-pluginList"
            args << getSpotbugsPlugins()
        }


        if (visitors) {
            args << "-visitors"
            args << visitors
        }

        if (omitVisitors) {
            args << "-omitVisitors"
            args << omitVisitors
        }

        if (relaxed) {
            args << "-relaxed"
        }

        if (nested) {
            args << "-nested:true"
        } else {
            args << "-nested:false"
        }

        if (onlyAnalyze) {
            args << "-onlyAnalyze"
            args << onlyAnalyze
        }

        if (includeFilterFile) {
            log.debug("  Adding Include Filter File ")

            String[] includefilters = includeFilterFile.split(SpotBugsInfo.COMMA)

            includefilters.each { includefilter ->
                args << "-include"
                args << resourceHelper.getResourceFile(includefilter.trim())
            }

        }

        if (excludeFilterFile) {
            log.debug("  Adding Exclude Filter File ")
            String[] excludefilters = excludeFilterFile.split(SpotBugsInfo.COMMA)

            excludefilters.each { excludeFilter ->
                args << "-exclude"
                args << resourceHelper.getResourceFile(excludeFilter.trim())
            }

        }

        if (excludeBugsFile) {
            log.debug("  Adding Exclude Bug Files (Baselines)")
            String[] excludeFiles = excludeBugsFile.split(SpotBugsInfo.COMMA)

            excludeFiles.each() { excludeFile ->
                args << "-excludeBugs"
                args << resourceHelper.getResourceFile(excludeFile.trim())
            }
        }

        if (addSourceDirs) {
            log.debug("  Adding Source directories (To process source exclusions)")
            args << "-sourcepath"
            String sourceRoots = "";
            compileSourceRoots.each() { sourceRoots += it + File.pathSeparator }
            if (includeTests) {
                testSourceRoots.each() { sourceRoots + it + File.pathSeparator }
            }
            args << sourceRoots.substring(0, sourceRoots.length() -1);
        }

        if (maxRank) {
            args << "-maxRank"
            args << maxRank
        }

        args << "-output"
        args << tempFile.getAbsolutePath()


        if (classFilesDirectory.exists() && classFilesDirectory.isDirectory()) {
            log.debug("  Adding to Source Directory ->" + classFilesDirectory.absolutePath)
            args << classFilesDirectory.absolutePath
        }

        if (testClassFilesDirectory.exists() && testClassFilesDirectory.isDirectory() && includeTests) {
            log.debug("  Adding to Source Directory ->" + testClassFilesDirectory.absolutePath)
            args << testClassFilesDirectory.absolutePath
        }

        return args
    }

    /**
     * Get the Spotbugs AuxClasspath.
     *
     */
    private String getSpotbugsAuxClasspath() {
        def auxClasspathElements

        if (classFilesDirectory.exists() && classFilesDirectory.isDirectory()) {
            auxClasspathElements = project.compileClasspathElements
        }

        if (testClassFilesDirectory.exists() && testClassFilesDirectory.isDirectory() && includeTests) {
            auxClasspathElements = project.testClasspathElements
        }

        def auxClasspath = ""

        if (auxClasspathElements) {

            log.debug("  AuxClasspath Elements ->" + auxClasspathElements)

            def auxClasspathList = auxClasspathElements.findAll { project.build.outputDirectory != it.toString() }

            if (auxClasspathList.size() > 0) {

                auxClasspath += File.pathSeparator

                log.debug("  Last AuxClasspath is ->" + auxClasspathList[auxClasspathList.size() - 1])

                auxClasspathList.each() { auxClasspathElement ->

                    log.debug("  Adding to AuxClasspath ->" + auxClasspathElement.toString())

                    auxClasspath += auxClasspathElement.toString() +  File.pathSeparator
                }
            }
        }

        log.debug("  AuxClasspath is ->" + auxClasspath)

        return auxClasspath
    }

    /**
     * Set up and run the Spotbugs engine.
     *
     * @param locale
     *            the locale the report should be generated for
     *
     */
    private void executeSpotbugs(File outputFile) {

        log.debug("****** SpotBugsMojo executeSpotbugs *******")
        long startTime, duration

        def fileName
        if (!htmlOutput) {
            fileName = "spotbugsTemp.xml"
        } else {
            fileName = "spotbugsTemp.htm"
        }

        File tempFile = new File("${project.build.directory}/${fileName}")

        if (tempFile.exists()) {
            tempFile.delete()
        }

        tempFile.getParentFile().mkdirs()
        tempFile.createNewFile()

        outputEncoding = outputEncoding ?: 'UTF-8'

        log.debug("****** Executing SpotBugsMojo *******")

        resourceManager.addSearchPath(FileResourceLoader.ID, project.getFile().getParentFile().getAbsolutePath())
        resourceManager.addSearchPath(SpotBugsInfo.URL, "")

        resourceManager.setOutputDirectory(new File(project.getBuild().getDirectory()))

        if (log.isDebugEnabled()) {
            log.debug("resourceManager outputDirectory is ${resourceManager.outputDirectory}")
            log.debug("  Plugin Artifacts to be added -> ${pluginArtifacts.toString()}")
            log.debug("outputFile is " + outputFile.getCanonicalPath())
            log.debug("output Directory is " + spotbugsXmlOutputDirectory.getAbsolutePath())
            log.debug("Temp File is " + tempFile.getCanonicalPath())
        }

        def ant = new AntBuilder()

        log.info("Fork Value is ${fork}")

        if (log.isDebugEnabled()) {
            startTime = System.nanoTime()
        }

        def spotbugsArgs = getSpotbugsArgs(tempFile)

        def effectiveEncoding = System.getProperty("file.encoding", "UTF-8")

        if (sourceEncoding) {
            effectiveEncoding = sourceEncoding
        }

        ant.java(classname: "edu.umd.cs.findbugs.FindBugs2", inputstring: getSpotbugsAuxClasspath(), fork: "${fork}", failonerror: "true", clonevm: "false", timeout: "${timeout}", maxmemory: "${maxHeap}m") {

            log.debug("File Encoding is " + effectiveEncoding)

            sysproperty(key: "file.encoding", value: effectiveEncoding)

            if (jvmArgs && fork) {
                log.debug("Adding JVM Args => ${jvmArgs}")

                String[] args = jvmArgs.split(SpotBugsInfo.BLANK)

                args.each() { jvmArg ->
                    log.debug("Adding JVM Arg => ${jvmArg}")
                    jvmarg(value: jvmArg)
                }
            }

            if (debug || trace) {
                sysproperty(key: "findbugs.debug", value: true)
            }

            classpath() {

                pluginArtifacts.each() { pluginArtifact ->
                    log.debug("  Adding to pluginArtifact ->" + pluginArtifact.file.toString())

                    pathelement(location: pluginArtifact.file)
                }
            }

            spotbugsArgs.each { spotbugsArg ->
                log.debug("Spotbugs arg is ${spotbugsArg}")
                arg(value: spotbugsArg)
            }

            systemPropertyVariables.each { sysProp ->
                log.debug("System property ${sysProp.key} is ${sysProp.value}")
                sysproperty(key: sysProp.key, value: sysProp.value)
            }

        }

        if (log.isDebugEnabled()) {
            duration = (System.nanoTime() - startTime) / 1000000000.00
            log.debug("SpotBugs duration is ${duration}")
        }

        log.info("Done SpotBugs Analysis....")

        if (tempFile.exists() && !htmlOutput) {

            if (tempFile.size() > 0) {
                def path = new XmlSlurper().parse(tempFile)

                def allNodes = path.depthFirst().collect { it }

                bugCount = allNodes.findAll { it.name() == 'BugInstance' }.size()
                log.debug("BugInstance size is ${bugCount}")

                errorCount = allNodes.findAll { it.name() == 'Error' }.size()
                log.debug("Error size is ${errorCount}")

                def xmlProject = path.Project

                compileSourceRoots.each() { compileSourceRoot ->
                    xmlProject.appendNode { SrcDir(compileSourceRoot) }
                }

                if (testClassFilesDirectory.exists() && testClassFilesDirectory.isDirectory() && includeTests) {
                    testSourceRoots.each() { testSourceRoot ->
                        xmlProject.appendNode { SrcDir(testSourceRoot) }
                    }
                }

                path.SpotbugsResults.FindBugsSummary.'total_bugs' = bugCount   // Fixes visitor problem

                xmlProject.appendNode {
                    WrkDir(project.build.directory)
                }

                def xmlBuilder = new StreamingMarkupBuilder()

                if (outputFile.exists()) {
                    outputFile.delete()
                }

                outputFile.getParentFile().mkdirs()
                outputFile.createNewFile()

                def writer = outputFile.newWriter(effectiveEncoding)
                if (effectiveEncoding.equalsIgnoreCase("Cp1252")) {
                    writer.write "<?xml version=\"1.0\" encoding=\"windows-1252\"?>"
                } else {
                    writer.write "<?xml version=\"1.0\" encoding=\"" + effectiveEncoding.toLowerCase(Locale.ENGLISH) + "\"?>"
                }
                writer.write "\n"

                writer << xmlBuilder.bind { mkp.yield path }
            } else {
                log.info("No bugs found")
            }

            if (!log.isDebugEnabled()) {
                tempFile.delete()
            }

        }

    }

    /**
     * Returns the threshold parameter to use.
     *
     * @return A valid threshold parameter.
     *
     */
    protected String getThresholdParameter() {

        log.debug("threshold is ${threshold}")

        String thresholdParameter

        switch (threshold) {
            case "High":
                thresholdParameter = "-high"; break

            case "Exp":
                thresholdParameter = "-experimental"; break

            case "Low":
                thresholdParameter = "-low"; break

            case "high":
                thresholdParameter = "-high"; break

            default:
                thresholdParameter = "-medium"; break
        }
        log.debug("thresholdParameter is ${thresholdParameter}")

        return thresholdParameter

    }

    /**
     * @see org.apache.maven.reporting.AbstractMavenReport#setReportOutputDirectory(java.io.File)
     */
    @Override
    public void setReportOutputDirectory(File reportOutputDirectory) {
        super.setReportOutputDirectory(reportOutputDirectory)
        this.outputDirectory = reportOutputDirectory
    }

    /**
     * Collects the java sources from the source roots.
     *
     * @return A list containing the java sources or an empty list if no java sources are found.
     *
     */
    protected List getJavaSources(Locale locale) {
        List sourceFiles = new ArrayList()

        if (classFilesDirectory.exists() && classFilesDirectory.isDirectory()) {
            List files = FileUtils.getFiles(classFilesDirectory, SpotBugsInfo.JAVA_REGEX_PATTERN, null)
            sourceFiles.addAll(files)
        }

        if (testClassFilesDirectory.exists() && testClassFilesDirectory.isDirectory() && includeTests) {
            List files = FileUtils.getFiles(testClassFilesDirectory, SpotBugsInfo.JAVA_REGEX_PATTERN, null)
            sourceFiles.addAll(files)
        }

        return sourceFiles
    }

}
