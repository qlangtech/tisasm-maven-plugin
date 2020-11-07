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
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 负责将客户打好的tgz assemble包放到阿里云OSS仓库中去
 *
 * @author: baisui 百岁
 * @create: 2020-09-16 12:40
 **/
@Mojo(name = "put")
public class TIsAliyunOssMojo extends AbstractMojo {

    public static final String ASSEMBLE_FILE_EXTENSION = ".tar.gz";
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

    @Parameter(required = false)
    private boolean skip = false;


    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        if (skip || "pom".equals(this.project.getPackaging())) {
            this.getLog().warn("artifact:" + this.project.getArtifactId() + " package type is pom skip");
            return;
        }

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

        File assembleFile = new File(outputDirectory, this.finalName + assembleFileExtension);


        putFile2Oss(bucketName, client, assembleFile);

        // 给ng-tis用，因为ng-tis是npm工程没有直接在其中运行maven插件，所以上传需要依附在其他应用里面
        if (StringUtils.isNotBlank(this.appendDeplpyFileName)) {
            String localReleaseDir = System.getProperty(TIS_LOCAL_RELEASE_DIR);
            File appendFile = null;
            if (StringUtils.isEmpty(localReleaseDir)) {
                //throw new MojoExecutionException("system param " + TIS_LOCAL_RELEASE_DIR + "can not be null ");
                this.getLog().warn("system param " + TIS_LOCAL_RELEASE_DIR + " is empty ,now shall skip deploy '" + appendDeplpyFileName + "'");
                return;
            }
            if (!(appendFile = new File(localReleaseDir, this.appendDeplpyFileName)).exists()) {
                throw new MojoExecutionException("appendFile" + appendFile.getAbsolutePath() + " is not exist ");
            }
            putFile2Oss(bucketName, client, appendFile);
        }

    }

    private void putFile2Oss(String bucketName, OSS client, File assembleFile) throws MojoFailureException {
        if (!assembleFile.exists()) {
            throw new MojoFailureException("target assemble file is not exist:" + assembleFile.getAbsolutePath());
        }
        final String ossKey = project.getVersion() + "/" + this.subDir + "/" + assembleFile.getName();
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
            meta = client.getObjectMetadata(bucketName, ossKey);
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

        PutObjectRequest putObj = new PutObjectRequest(bucketName, ossKey, assembleFile);
        meta = new ObjectMetadata();
        Map<String, String> userMeta = new HashMap<>();
        userMeta.put(KEY_MD5, md5);
        meta.setUserMetadata(userMeta);
        putObj.setMetadata(meta);
        this.getLog().info("assemble file:" + assembleFile.getAbsolutePath()
                + " start put in aliyun OSS repository successful,key:" + ossKey);
        client.putObject(putObj);
        this.getLog().info("key:" + ossKey + " put success");


    }

    private String getConfigTemplateContent() {
        try {
            return IOUtils.toString(TIsAliyunOssMojo.class.getResourceAsStream("config.tpl"));
        } catch (IOException e) {
            this.getLog().warn(e);
            return null;
        }
    }

    private String getProp(Properties props, String key) throws MojoExecutionException {
        String value = props.getProperty(key);
        if (StringUtils.isEmpty(value)) {
            throw new MojoExecutionException("key:" + key + " relevant value can not be null");
        }
        return value;
    }
}
