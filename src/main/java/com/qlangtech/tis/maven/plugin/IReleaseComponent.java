package com.qlangtech.tis.maven.plugin;

import org.apache.maven.project.MavenProject;

/**
 * @author: 百岁（baisui@qlangtech.com）
 **/
public interface IReleaseComponent {
    MavenProject getProject();

    String getSubDir();

    String getVersion();
}
