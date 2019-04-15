package com.ryan.plugin

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

class PluginRepoImpl implements Plugin<Settings> {

    private static def CMD_PROPERTIES = 'repo'
    private static def CONFIG_XML_FILE = 'repo.xml'
    private static def XML_TAG_REPO = 'repo'
    private static def XML_TAG_NAME = 'name'
    private static def XML_TAG_BRANCH = 'branch'
    private static def XML_TAG_INCLUDE = 'include'
    private static def MODULE_PREFIX = 'ryan_'
    private static def SSH_USERNAME_FILE = 'local.properties'
    private static def SSH_USERNAME_KEY = 'SSH_USER_NAME'
    private static def SSH_USERNAME_PLACE_HOLDER = '{user-name}'

    @Override
    void apply(Settings settings) {
        if (!settings.hasProperty(CMD_PROPERTIES)) {
            return
        }
        def startShell = settings[CMD_PROPERTIES]
        println startShell
        if (startShell == null || !startShell.toBoolean()) {
            return
        }
        def rootDir = settings.getRootDir()
        File repoFile = new File(rootDir, CONFIG_XML_FILE)
        Node repoNode = new XmlParser().parse(repoFile)
        println '-----------------------begin processing repo-----------------------------'
        def repoNameSet = []
        def repoSet = []
        def branchSet = []
        def includeSet = []

        def nodeList = repoNode.children()
        nodeList.each { module ->
            module.each {
                String name = it.name()
                if (name == null || name == "") {
                    return
                }
                def value = it.value()[0]
                switch (name) {
                    case XML_TAG_REPO:
                        println "repo: ${value}"
                        repoSet << value
                        break
                    case XML_TAG_BRANCH:
                        println "branch: ${value}"
                        branchSet << value
                        break
                    case XML_TAG_NAME:
                        println "parsing module: ${value}"
                        repoNameSet << value
                        break
                    case XML_TAG_INCLUDE:
                        println "include: ${value}"
                        includeSet << value
                        break
                    default:
                        throw new GradleException("${CONFIG_XML_FILE} defines a wrong type: ${name}, check and fix it. \r\npath: ${repoFile.absolutePath}")
                }
            }
        }

        if (repoSet.isEmpty() || branchSet.isEmpty() || repoNameSet.isEmpty() || includeSet.isEmpty()) {
            return
        }

        if (!(repoSet.size() == branchSet.size() && branchSet.size() == repoNameSet.size() && repoNameSet.size() == includeSet.size())) {
            throw new GradleException("${CONFIG_XML_FILE} configuration error, you need fully configure the module name, repo, branch and include. \r\npath: ${repoFile.absolutePath}")
        }

        def sshUserName

        for (i in 0..repoSet.size() - 1) {
            String name = repoNameSet[i]
            String repo = repoSet[i]
            String branch = branchSet[i]
            if (useSSH(repo)) {
                if (sshUserName == null) {
                    Properties properties = new Properties()
                    properties.load(new File(rootDir, SSH_USERNAME_FILE).newDataInputStream())
                    sshUserName = properties.get(SSH_USERNAME_KEY)
                    if (sshUserName == null || sshUserName == '') {
                        throw new GradleException('Unable to find ssh username, please define SSH_USER_NAME correctly in local.properties file, for example: SSH_USER_NAME=ryan')
                    }
                    println "find ssh user name: ${sshUserName}"
                }
                repo = repo.replace(SSH_USERNAME_PLACE_HOLDER, sshUserName)
            }
            boolean include = Boolean.valueOf(includeSet[i])
            File repoDir = new File(rootDir, name)
            def cmd
            def workDir
            if (repoDir.exists() && repoDir.isDirectory()) {
                workDir = repoDir
                String branchCmd = 'git branch'
                Process branchProcess = branchCmd.execute(null, workDir)
                def branchOutput = new StringBuffer()
                branchProcess.consumeProcessOutput(branchOutput, null)
                branchProcess.waitFor()
                if (branchOutput.size() <= 0) {
                    throw new GradleException("Unable to get git branch, make sure you install and use git correctly.")
                }
                String branchName = branchOutput.toString().replace('*', '').trim()
                println "current git branch ${branchName}."
                if (branchName.equalsIgnoreCase(branch)) {
                    cmd = "git pull --rebase"
                    println "start pull ${repo}:${branch}..."
                } else {
                    cmd = "git checkout ${branch}"
                    println "start checkout ${repo}:${branch}..."
                }
            } else {
                cmd = "git clone -b ${branch} ${repo} ${name}"
                workDir = rootDir
                println "start check out ${repo}:${branch}..."
            }
            println "git cmd: ${cmd}"
            println("workDir: ${workDir}")
            Process process = cmd.execute(null, workDir)
            def out = new StringBuffer()
            def err = new StringBuffer()
            process.consumeProcessOutput(out, err)
            process.waitFor()
            if (out.size() > 0) println out
            if (err.size() > 0) println err
            println "process include, ${name} include: ${include}"
            if (include) {
                File includeRootDir = new File(rootDir, name)
                includeRootDir.listFiles().findAll {
                    it.isDirectory() && it.name.startsWith(MODULE_PREFIX)
                }.each { subModule ->
                    persistenceInclude(settings, ":${name}:${subModule.name}")
                }
            } else {
                persistenceInclude(settings, ":${name}")
            }
        }
        println '-----------------------end processing repo-----------------------------'
    }

    static void persistenceInclude(Settings settings, String includeModule) {
        settings.include(includeModule)
        def settingsFile = new File(settings.getSettingsDir(), 'settings.gradle')
        if (!settingsFile.text.contains(includeModule)) {
            settingsFile.append("\r\ninclude '${includeModule}'")
        }
    }

    static boolean useSSH(String url) {
        url.startsWith('ssh://')
    }

}