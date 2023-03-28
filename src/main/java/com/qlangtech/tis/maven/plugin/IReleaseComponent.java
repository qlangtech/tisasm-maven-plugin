package com.qlangtech.tis.maven.plugin;

import org.apache.maven.project.MavenProject;

/**
 * @author: 百岁（baisui@qlangtech.com）
 * @create: 2023-03-28 18:35
 **/
public interface IReleaseComponent {
    MavenProject getProject();

    String getSubDir();

    String getVersion();
}
