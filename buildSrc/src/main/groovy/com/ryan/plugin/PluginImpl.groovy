package com.ryan.plugin

import groovy.xml.Namespace
import groovy.xml.XmlUtil
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task

class PluginImpl implements Plugin<Project> {

    private static final String SPECIAL_MODULE = 'buildSrc'
    private static final String PLUGIN_APPLICATION = 'android'
    private static final String PLUGIN_LIBRARY = 'android-library'
    private static final String EXTENSIONS_NAME = 'Ryan'
    private static final String CHECK_TASK = 'ruleCheck'
    private static final String CHECK_TASK_GROUP = 'rule'
    private static final String CHECK_TASK_DESCRIPTION = 'check project internal specifications.'
    private static final String manifestRelatePath = 'src/main/AndroidManifest.xml'
    private static final String APPLICATION_PLUGIN = 'com.android.application'
    private static final String GRADLE_CONFIG_FILE = 'gradle.properties'
    private RyanExtension mExtension = new RyanExtension()

    @Override
    void apply(Project project) {
        if (project.name == SPECIAL_MODULE) {
            return
        }
        mExtension = project.extensions.create(EXTENSIONS_NAME, RyanExtension)
        Task checkTask = project.tasks.create(CHECK_TASK)
        checkTask.doLast {
            long startTime = System.currentTimeMillis()
            checkDependencies(project)
            checkLibs(project)
            checkPackage(project)
            if (mExtension.checkResource.toBoolean()) {
                checkResource(project)
            }
            println "check total time: ${System.currentTimeMillis() - startTime} ms."
        }
        checkTask.group = CHECK_TASK_GROUP
        checkTask.description = CHECK_TASK_DESCRIPTION
        project.plugins.withId(PLUGIN_APPLICATION) {
            project.android.applicationVariants.all { variant ->
                variant.preBuild.dependsOn(checkTask)
            }
        }
        project.plugins.withId(PLUGIN_LIBRARY) {
            project.android.libraryVariants.all { variant ->
                variant.preBuild.dependsOn(checkTask)
            }
        }
        processRunAlone(project)
    }

    static void processRunAlone(Project project) {
        project.afterEvaluate {
            if (!project.plugins.hasPlugin(APPLICATION_PLUGIN)) {
                return
            }
            project.android.applicationVariants.all { variant ->
                variant.outputs.each {
                    it.processManifest.doLast {
                        long startTime = System.currentTimeMillis()
                        println '-----------------------start run alone task processing----------------------------'
                        String runAloneTag = 'RUN_ALONE_MODULE'
                        Properties properties = new Properties()
                        properties.load(project.rootProject.file('local.properties').newDataInputStream())
                        String runAlone = properties.getProperty(runAloneTag)
                        if (checkNull(runAlone)) {
                            println "Root project's local.properties is not define ${runAloneTag}, run alone skip."
                            return
                        }
                        Project findProject = project.getRootProject().getSubprojects().find {
                            File file = new File(it.projectDir, GRADLE_CONFIG_FILE)
                            file.exists() && it.name.equalsIgnoreCase(runAlone)
                        }
                        if (findProject == null) {
                            println 'no project need run alone, skip.'
                            return
                        }
                        println "${findProject.name} need run alone, processing..."
                        String appName = findProject.properties.'RUN_ALONE_APP_NAME'
                        String replaceClass = findProject.properties.'MAIN_CLASS'
                        println "app name: ${appName}, replace class: ${replaceClass}"
                        File mainManifestDir = outputs.files.find {
                            File file = new File(it, 'AndroidManifest.xml')
                            file.exists()
                        }
                        println "main manifest dir: ${mainManifestDir}"
                        if (mainManifestDir == null || !mainManifestDir.exists()) {
                            throw new GradleException('can not find main project AndroidManifest.xml file.')
                        }
                        if (checkNull(replaceClass)) {
                            throw new GradleException("Unable to locate ${findProject.name} module main activity class, you need to define it in gradle.properties")
                        }
                        println "find replace class: ${replaceClass}, app name: ${appName}"
                        File mainManifest = new File(mainManifestDir, 'AndroidManifest.xml')
                        println "find main manifest file path: ${mainManifest.path}"
                        def android = new Namespace('http://schemas.android.com/apk/res/android')
                        Node mainManifestNode = new XmlParser().parse(mainManifest)
                        Node mainActivityNode = mainManifestNode.application[0].activity.find {
                            NodeList nodeList = it.get('intent-filter')
                            if (nodeList != null && !nodeList.isEmpty()) {
                                nodeList.find { node ->
                                    node.get('category').find { category ->
                                        String name = category.attribute(android.name)
                                        name.equalsIgnoreCase('android.intent.category.LAUNCHER')
                                    }
                                } != null
                            }
                        }
                        String mainClass = mainActivityNode.attribute(android.name)
                        println "find main activity class: ${mainClass}"
                        if (!mainClass.equalsIgnoreCase(replaceClass)) {
                            File moduleManifestFile = new File(findProject.projectDir, manifestRelatePath)
                            Node moduleManifestNode = new XmlParser().parse(moduleManifestFile)
                            String modulePackage = moduleManifestNode.@package
                            def mainApp = mainManifestNode.application[0]
                            Node rmNode = mainApp.activity.find { activity ->
                                String name = activity.attribute(android.name)
                                if (!name.startsWith(modulePackage)) {
                                    name = modulePackage + name
                                }
                                name.equalsIgnoreCase(replaceClass)
                            }
                            if (rmNode != null) {
                                def remove = mainApp.remove(rmNode)
                                println "remove node result: ${remove}"
                            }
                            mainActivityNode.attributes().put(android.name, replaceClass)
                            println "replace main activity class from ${mainClass} to ${replaceClass}"
                            if (!checkNull(appName)) {
                                mainApp.attributes().put(android.label, appName)
                                println "replace app name to: ${appName}"
                            } else {
                                println "RUN_ALONE_APP_NAME properties is not defined, using the original app name."
                            }
                            PrintWriter mainWriter = new PrintWriter(mainManifest, 'UTF-8')
                            XmlUtil.serialize(mainManifestNode, mainWriter)
                            mainWriter.close()
                            println '-----------------------end run alone task process--------------------------------'
                            println "processing total time: ${System.currentTimeMillis() - startTime} ms"
                        }
                    }
                }
            }
        }
    }

    static void checkDependencies(Project project) {
        def buildFile = project.getBuildFile()
        if (!buildFile.exists()) {
            return
        }
        println "start check ${project.name} dependencies..."
        def dependenciesRuleFile = 'dependencies.gradle'
        def dependenciesTag = 'dependencies'
        def regular1 = /([\da-z-\.]+:){2}\d(\.\d){0,2}/
        def regular2 = /group:.*,.*name:.*,.*version:.*\d+(\.\d)*/
        def startTag = '{'
        def endTag = '}'
        def stack = new Stack<>()
        def start = false
        def lineNo = 0
        buildFile.withReader {
            def lines = it.readLines()
            for (line in lines) {
                lineNo++
                line = line.trim()
                if (line.isEmpty()) {
                    continue
                }
                if (line.startsWith(dependenciesTag)) {
                    start = true
                    stack.push(startTag)
                    continue
                }
                if (start) {
                    if (line.contains(startTag)) {
                        stack.push(startTag)
                    }
                    if (line.contains(endTag)) {
                        stack.pop()
                    }
                    if (stack.isEmpty()) {
                        break
                    }
                    def matcher1 = line =~ regular1
                    def matcher2 = line =~ regular2
                    if (matcher1.find() || matcher2.find()) {
                        throw new GradleException("build.gradle dependencies check error, You must define it in ${dependenciesRuleFile} file. \r\n at line ${lineNo}: ${line} \r\n File path: ${buildFile.absolutePath}")
                    }
                }
            }
        }
    }

    static void checkLibs(Project project) {
        if (project.name == 'libs') {
            return
        }
        println "start check ${project.name} libs..."
        project.android.sourceSets.main.jniLibs.srcDirs.each { dirFile ->
            if (dirFile != null && dirFile.exists() && dirFile.list().size() > 0) {
                throw new GradleException("The external lib file must be defined under the ${libModule} module.\r\n please check the folder path: ${dirFile.absolutePath} and move all of them to ${libModule} module.")
            }
        }
    }

    static void checkPackage(Project project) {
        def skipLib = ['libs']
        def name = project.name
        if (name in skipLib) {
            return
        }
        println "start check ${name} package..."
        def srcDirs = project.android.sourceSets.main.java.srcDirs
        def srcFile = new File(srcDirs[0].path)
        if (!srcFile.exists() || srcFile.list().size() == 0) {
            return
        }
        def moduleRule = 'com.ryan.gradle'
        def com = 'com'
        def ryan = 'ryan'
        def gradle = 'gradle'
        def comFile = new File(srcFile.absolutePath + File.separator + com)
        def ryanFile = new File(comFile.absolutePath + File.separator + ryan)
        def errorMsg = "The module package name must be named ${moduleRule}. \r\n see path: ${srcFile.absolutePath}"
        if (comFile.exists()) {
            def comList = comFile.list()
            if (comList.size() == 0) {
                return
            }
            if (comList[0] != ryan || comList.size() >= 2) {
                throw new GradleException(errorMsg)
            }
        }
        if (ryanFile.exists()) {
            def ryanList = ryanFile.list()
            if (ryanList.size() == 0) {
                return
            }
            if (ryanList[0] != gradle || ryanList.size() >= 2) {
                throw new GradleException(errorMsg)
            }
        }
        def gradleDir = ryanFile.path + File.separator + gradle
        def gradleFile = new File(gradleDir)
        if (!gradleFile.exists()) {
            throw new GradleException(errorMsg)
        }
        File manifestFile = project.android.sourceSets.main.manifest.srcFile
        if (!manifestFile.exists()) {
            return
        }
        XmlParser parser = new XmlParser()
        def node = parser.parse(manifestFile)
        String packageName = node.attribute('package')
        if (!packageName.startsWith(moduleRule)) {
            throw new GradleException("The package name in AndroidManifest must start with ${moduleRule}. \r\n see file path: ${manifestFile.absolutePath}")
        }
    }

    static void checkResource(Project project) {
        def projectPrefix = 'ryan_'
        def projectName = project.name
        if (!projectName.startsWith(projectPrefix)) {
            return
        }
        println "start check ${projectName} resource..."
        project.android.resourcePrefix = projectName
        def resourceSets = project.android.sourceSets
        if (resourceSets != null) {
            resourceSets.main.res.srcDirs.each {
                def file = file(it)
                if (file.exists() && file.isDirectory()) {
                    scanFiles(project, file)
                }
            }
        }
    }

    static void scanFiles(Project project, File file) {
        if (!file.exists()) {
            return
        }
        if (file.isDirectory()) {
            file.list().each {
                def dirPath = file.path + File.separator + it
                def dirFile = new File(dirPath)
                if (dirFile.exists() && dirFile.isDirectory()) {
                    dirFile.list().each { resName ->
                        def projectName = project.name
                        if (!resName.startsWith(projectName)) {
                            throw new GradleException("The resource name: ${resName} is not standardized, please follow the naming convention. \r\n at file path: ${dirPath + File.separator + resName}")
                        }
                    }
                }
            }
        }
    }

    static void checkNull(String s) {
        s == null || s == ""
    }

    static class RyanExtension {

        def checkResource = false

    }
}