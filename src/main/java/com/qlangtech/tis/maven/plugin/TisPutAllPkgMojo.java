package com.qlangtech.tis.maven.plugin;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 批量向OSS中导入包
 *
 * @author: 百岁（baisui@qlangtech.com） 2021-07-26 17:30
 **/
@Mojo(name = "putall")
public class TisPutAllPkgMojo extends AbstractMojo implements IReleaseComponent {

    private static final String KEY_DEFAULT_SUB_DIR = "tis";

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

    @Parameter(required = false, defaultValue = KEY_DEFAULT_SUB_DIR)
    private String subDir;

    @Parameter(property = "append.deploy.file.name", required = false)
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
        final File basedir = project.getBasedir();
        if ("pom".equals(this.project.getPackaging())
                // 避免进入plugin的工程
                && StringUtils.indexOfAny(this.project.getArtifactId(), new String[]{"plugin"}) < 0) {


            Collection<File> uploads = FileUtils.listFiles(basedir, new String[]{TIsAliyunOssMojo.TAR_PACKAGING}, false);
            this.getLog().info("base dir:" + basedir.getAbsolutePath()
                    + ",finds:" + uploads.stream().map((u) -> u.getName()).collect(Collectors.joining(",")));
            for (File pkg : uploads) {
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


        } else if ("jar".equals(this.project.getPackaging())) {
            // plugins 工程中为了将类似tis-datax-executor 这样的项目打出来的assemble包送入中央仓库中
            List<Plugin> plugins = this.project.getBuild().getPlugins();
            for (Plugin plugin : plugins) {
                if ("tisasm-maven-plugin".equals(plugin.getArtifactId())) {
                    Xpp3Dom conf = (Xpp3Dom) plugin.getConfiguration();
                    Xpp3Dom outputDirElement = conf.getChild("outputDirectory");
                    if (outputDirElement == null) {
                        throw new MojoExecutionException("property of outputDirectory in plugin configuration can not be null");
                    }
                    Xpp3Dom fname = conf.getChild("finalName");
                    //  System.out.println();
                    File outputDir = new File(basedir, outputDirElement.getValue());
                    File targetTar = new File(outputDir, (fname != null ? fname.getValue() : this.finalName) + ".tar.gz");
                    if (targetTar.exists()) {
                        TIsAliyunOssMojo.OSSRuntime tisRelease
                                = TIsAliyunOssMojo.createOssRuntime(this.getLog(), createDefaultSubDirReleaseComponent());
                        tisRelease.putFile2Oss(targetTar);
                        this.getLog().info("targetTar deploy to TIS repository:" + targetTar.getAbsolutePath());
                    } else {
                        this.getLog().warn("targetTar is not present,skip deploy:" + targetTar.getAbsolutePath());
                    }
                }
            }

        }
    }

    private IReleaseComponent createDefaultSubDirReleaseComponent() {
        return new IReleaseComponent() {
            @Override
            public MavenProject getProject() {
                return TisPutAllPkgMojo.this.project;
            }

            @Override
            public String getSubDir() {
                return KEY_DEFAULT_SUB_DIR;
            }

            @Override
            public String getVersion() {
                return TisPutAllPkgMojo.this.getVersion();
            }
        };
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
