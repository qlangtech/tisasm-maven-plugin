package com.qlangtech.tis.maven.plugin;

import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;

/**
 * 批量向OSS中导入包
 *
 * @author: 百岁（baisui@qlangtech.com）
 * @create: 2021-07-26 17:30
 **/
@Mojo(name = "putall")
public class TisPutAllPkgMojo extends AbstractMojo implements IReleaseComponent {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private File outputDirectory;
    @Parameter(defaultValue = "${project.build.finalName}", required = true)
    private String finalName;

    @Parameter(defaultValue = "${basedir}/tpis")
    protected File tpisDirectory;

    @Parameter(property = "tis.release.version", defaultValue = "${project.version}")
    protected String tisVersion;

    @Parameter(required = false, defaultValue = "tis")
    private String subDir;

    @Parameter(property = "append.deploy.file.name",required = false)
    private String appendDeplpyFileName;

//    /**
//     * 为了在TIS中支持多版本的plugin，例如：需要在仓库中同时支持多个版本的HDFS插件，需要设置对应的plugin的classifier属性
//     * This is the artifact classifier to be used for the resultant assembly artifact. Normally, you would use the
//     * assembly-id instead of specifying this here.
//     */
//    @Parameter(property = "classifier", required = false)
//    private String classifier;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        TIsAliyunOssMojo.OSSRuntime oss
                = TIsAliyunOssMojo.createOssRuntime(this.getLog(), this);

        if ("pom".equals(this.project.getPackaging())
                // 避免进入plugin的工程
                && StringUtils.indexOfAny(this.project.getArtifactId(), new String[]{"plugin"}) < 0) {

            File basedir = project.getBasedir();
            for (File pkg : FileUtils.listFiles(basedir, new String[]{TIsAliyunOssMojo.TAR_PACKAGING}, false)) {
                oss.putFile2Oss(pkg);
            }
            // 给ng-tis用，因为ng-tis是npm工程没有直接在其中运行maven插件，所以上传需要依附在其他应用里面
            TIsAliyunOssMojo.putAppendDeployFile(oss, this.appendDeplpyFileName);
        } else if (TIsAliyunOssMojo.TPI_PACKAGING.equals(this.project.getPackaging())) {

            File assembleFile = new File(tpisDirectory
                    , TIsAliyunOssMojo.getTpiFileName(oss, this.finalName, TIsAliyunOssMojo.TIS_PLUGIN_PACKAGE_EXTENSION));
            if (!assembleFile.exists()) {
                throw new IllegalStateException("target file shall be exist :" + assembleFile.getAbsolutePath());
            }
            oss.putFile2Oss(assembleFile);
        }
    }

    @Override
    public String getVersion() {
        if (StringUtils.isEmpty(this.tisVersion)) {
            throw new IllegalStateException("tisVersion can not be empty");
        }
        return this.tisVersion;
    }

    @Override
    public MavenProject getProject() {
        return this.project;
    }

    @Override
    public String getSubDir() {
        return this.subDir;
    }
}
