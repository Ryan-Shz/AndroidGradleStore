package com.ryan.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

class PluginImpl implements Plugin<Project> {

    def APPLICATION_PLUGIN = 'com.android.application'
    def mExtension = new RyanExtension()
    def EXTENSION_NAME = 'Ryan'
    def TASK_GROUP = 'ryan'
    def TASK_DESCRIPTION = 'ryan plugin task.'

    @Override
    void apply(Project project) {
        mExtension = project.extensions.create(EXTENSION_NAME, RyanExtension)
        project.afterEvaluate {
            println "the extensions name is ${mExtension.name}"
            if (project.plugins.hasPlugin(APPLICATION_PLUGIN)) {
                project.android.sourceSets.main {
                    println 'start scan res dir.'
                    def mainPath = 'src/main'
                    def mainDir = project.file(mainPath)
                    println "mainDir is ${mainDir}"
                    def resDirs = []
                    if (mainDir.exists() && mainDir.isDirectory()) {
                        def fileList = mainDir.list()
                        fileList.each {
                            if (it.startsWith('res')) {
                                def resDir = "${mainPath}/${it}"
                                println "find resDir: ${resDir}"
                                resDirs << resDir
                            }
                        }
                    }
                    res.srcDirs = resDirs
                }
            }
        }
        def ryanTask = project.tasks.create('task1', RyanTask)
        ryanTask.setGroup(TASK_GROUP)
        ryanTask.setDescription(TASK_DESCRIPTION)
        def task = project.tasks.create('task2').doLast {
            println 'task2 is executed.'
        }
        task.setGroup(TASK_GROUP)
        task.setDescription(TASK_DESCRIPTION)
        project.android.applicationVariants.all { variant ->
            variant.checkManifest.dependsOn(ryanTask)
            ryanTask.dependsOn(task)
        }

    }

    static class RyanExtension {
        def name = 'ryan'
    }
}