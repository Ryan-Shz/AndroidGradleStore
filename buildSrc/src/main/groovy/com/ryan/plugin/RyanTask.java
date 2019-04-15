package com.ryan.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

/**
 * a template for how to define a task by java.
 * <p>
 * created by 2019/3/30 9:55 PM
 *
 * @author Ryan
 */
public class RyanTask extends DefaultTask {

    @TaskAction
    public void action() {
        System.out.println(getProject().getName());
        System.out.println("Ryan Task is executed.");
    }

}
