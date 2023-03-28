package com.qlangtech.tis.maven.plugin;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * 负责将客户打好的tgz assemble包放到阿里云OSS仓库中去
 *
 * @author: baisui 百岁
 * @create: 2020-09-16 12:40
 **/
@Mojo(name = "put")
public class TIsAliyunOssMojo extends AbstractMojo implements IReleaseComponent {
    public static final String TPI_PACKAGING = "tpi";
    public static final String TAR_PACKAGING = "tar.gz";
    public static final String TIS_PLUGIN_PACKAGE_EXTENSION = "." + TPI_PACKAGING;
    public static final String ASSEMBLE_FILE_EXTENSION = "." + TAR_PACKAGING;
    private static final String TIS_LOCAL_RELEASE_DIR = "release_dir";
    private static final String KEY_MD5 = "md5";

    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private File outputDirectory;
    @Parameter(defaultValue = "${project.build.finalName}", required = true)
    private String finalName;
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(required = false)
    private String appendDeplpyFileName;
    @Parameter(required = false, defaultValue = ASSEMBLE_FILE_EXTENSION)
    private String assembleFileExtension;

    @Parameter(required = false, defaultValue = "tis")
    private String subDir;

//    /**
//     * 为了在TIS中支持多版本的plugin，例如：需要在仓库中同时支持多个版本的HDFS插件，需要设置对应的plugin的classifier属性
//     * This is the artifact classifier to be used for the resultant assembly artifact. Normally, you would use the
//     * assembly-id instead of specifying this here.
//     */
//    @Parameter(property = "classifier", required = false)
//    private String classifier;

    @Parameter(required = false)
    private boolean skip = false;

    public static OSSRuntime createOSS(Log log, IReleaseComponent releaseComponent, Optional<String> classifier) throws MojoExecutionException, MojoFailureException {
        File cfgFile = new File(System.getProperty("user.home"), "aliyun-oss/config.properties");
        if (!cfgFile.exists()) {
            throw new MojoFailureException("oss config file is not exist:" + cfgFile.getAbsoluteFile() + "\n config.properties template is \n"
                    + getConfigTemplateContent());
        }
        Properties props = new Properties();
        try {
            try (InputStream reader = FileUtils.openInputStream(cfgFile)) {
                props.load(reader);
            }
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        String endpoint = getProp(props, "endpoint");
        String accessKeyId = getProp(props, "accessKey");
        String secretKey = getProp(props, "secretKey");
        String bucketName = getProp(props, "bucketName");

        OSS client = new OSSClientBuilder().build(endpoint, accessKeyId, secretKey);
        return new OSSRuntime(client, log, releaseComponent, bucketName, classifier);
    }

    public static class OSSRuntime {
        final OSS ossClient;
        final String bucket;
        final Log log;
        final IReleaseComponent releaseComponent;
        final MavenProject project;

        final Optional<String> classifier;

        private Log getLog() {
            return this.log;
        }

        public OSSRuntime(OSS ossClient, Log log, IReleaseComponent project, String bucket) {
            this(ossClient, log, project, bucket, Optional.empty());
        }

        public OSSRuntime(OSS ossClient, Log log, IReleaseComponent releaseComponent, String bucket, Optional<String> classifier) {
            this.ossClient = ossClient;
            this.log = log;
            this.project = releaseComponent.getProject();
            this.releaseComponent = releaseComponent;
            this.bucket = bucket;
            this.classifier = classifier;
        }

        public void putFile2Oss(File assembleFile) throws MojoFailureException {
            if (!assembleFile.exists()) {
                throw new MojoFailureException("target file is not exist:" + assembleFile.getAbsolutePath());
            }

            //  project.getArtifactId();
            final StringBuffer ossKey = new StringBuffer(releaseComponent.getVersion() + "/" + this.releaseComponent.getSubDir() + "/");
            if (classifier.isPresent()) {
                ossKey.append(project.getArtifactId()).append("/");//.append(classifier.get()).append("/");
            }
            ossKey.append(assembleFile.getName());

            String md5 = null;
            try {
                try (InputStream appendFileStream = FileUtils.openInputStream(assembleFile)) {
                    md5 = DigestUtils.md5Hex(appendFileStream);
                }
            } catch (IOException e) {
                throw new MojoFailureException(assembleFile.getAbsolutePath() + " md5 get faild");
            }

            ObjectMetadata meta = null;
            try {
                meta = ossClient.getObjectMetadata(this.bucket, ossKey.toString());
            } catch (OSSException e) {
                if (!StringUtils.equals(e.getErrorCode(), "NoSuchKey")) {
                    throw e;
                }
                //  this.getLog().warn("ossKey:" + ossKey + "\n" + e.toString());
            }
            String remoteMd5 = null;
            if (meta != null) {
                Map<String, String> userMeta = meta.getUserMetadata();
                remoteMd5 = userMeta.get(KEY_MD5);
                this.getLog().debug("file:" + assembleFile.getAbsolutePath() + "osskey:"
                        + ossKey + " localMd5:" + md5 + ",remoteMd5:" + remoteMd5);
            }
            if (meta != null) {
                if (StringUtils.equals(remoteMd5, md5)) {
                    this.getLog().info("file:" + assembleFile.getAbsolutePath() + " osskey:"
                            + ossKey + " relevant file in repository has stored skip this");
                    return;
                }
            }

            PutObjectRequest putObj = new PutObjectRequest(this.bucket, ossKey.toString(), assembleFile);
            meta = new ObjectMetadata();
            Map<String, String> userMeta = new HashMap<>();
            userMeta.put(KEY_MD5, md5);
            if (TPI_PACKAGING.equals(project.getPackaging())) {
                userMeta.put("groupId", project.getGroupId());
                userMeta.put("artifactId", project.getArtifactId());
                userMeta.put("version", project.getVersion());
                userMeta.put("packaging", project.getPackaging());

                if (classifier.isPresent()) {
                    userMeta.put("classifier", classifier.get());
                }

                this.getLog().info("append extra meta info with type " + TPI_PACKAGING);
            }
            meta.setUserMetadata(userMeta);
            putObj.setMetadata(meta);
            this.getLog().info("assemble file:" + assembleFile.getAbsolutePath()
                    + " start put in aliyun OSS repository successful,key:" + ossKey);
            ossClient.putObject(putObj);
            this.getLog().info("key:" + ossKey + " put success");


        }
    }

    @Override
    public String getVersion() {
        return this.project.getVersion();
    }

    @Override
    public MavenProject getProject() {
        return this.project;
    }

    @Override
    public String getSubDir() {
        return this.subDir;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        if (skip || "pom".equals(this.project.getPackaging())) {
            this.getLog().warn("artifact:" + this.project.getArtifactId()
                    + " package type is pom skip,skip:" + skip + ",packaging:" + this.project.getPackaging());
            return;
        }

        OSSRuntime oss = createOssRuntime(this.getLog(), this);
        String outputFile = getTpiFileName(oss, finalName, assembleFileExtension);
        File assembleFile = new File(outputDirectory, outputFile);

        oss.putFile2Oss(assembleFile);

        // 给ng-tis用，因为ng-tis是npm工程没有直接在其中运行maven插件，所以上传需要依附在其他应用里面
        putAppendDeployFile(oss, this.appendDeplpyFileName);

    }

    public static String getTpiFileName(OSSRuntime oss, String finalName, String assembleFileExtension) {
        String outputFile = finalName + assembleFileExtension;
        // TODO : the code is copy from com.qlangtech.tis.maven.plugins.tpi.TpiMojo
        if (oss.classifier.isPresent()) {
            outputFile = finalName + "_" + StringUtils.replace(oss.classifier.get(), ";", "_") + assembleFileExtension;
        }
        return outputFile;
    }

    public static OSSRuntime createOssRuntime(
            org.apache.maven.plugin.logging.Log log, IReleaseComponent releaseComponent)
            throws MojoExecutionException, MojoFailureException {
        Properties props = releaseComponent.getProject().getProperties();
        return createOSS(log
                , releaseComponent, Optional.ofNullable(props.getProperty("classifier")));
    }

    public static void putAppendDeployFile(OSSRuntime oss, String appendDeplpyFileName) throws MojoExecutionException, MojoFailureException {
        if (StringUtils.isNotBlank(appendDeplpyFileName)) {
            String localReleaseDir = System.getProperty(TIS_LOCAL_RELEASE_DIR);
            File appendFile = null;
            if (StringUtils.isEmpty(localReleaseDir)) {
                //throw new MojoExecutionException("system param " + TIS_LOCAL_RELEASE_DIR + "can not be null ");
                oss.getLog().warn("system param " + TIS_LOCAL_RELEASE_DIR + " is empty ,now shall skip deploy '" + appendDeplpyFileName + "'");
                return;
            }
            if (!(appendFile = new File(localReleaseDir, appendDeplpyFileName)).exists()) {
                throw new MojoExecutionException("appendFile:" + appendFile.getAbsolutePath() + " is not exist ");
            }
            oss.putFile2Oss(appendFile);
        }
    }


    private static String getConfigTemplateContent() {
        try {
            return IOUtils.toString(TIsAliyunOssMojo.class.getResourceAsStream("config.tpl"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getProp(Properties props, String key) throws MojoExecutionException {
        String value = props.getProperty(key);
        if (StringUtils.isEmpty(value)) {
            throw new MojoExecutionException("key:" + key + " relevant value can not be null");
        }
        return value;
    }
}
