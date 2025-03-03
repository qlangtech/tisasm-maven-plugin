package com.qlangtech.tis.maven.plugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.assembly.mojos.SingleAssemblyMojo;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;



/**
 * 根据VM 變量，判斷是否要執行該plugin
 * @author 百岁（baisui@2dfire.com） 2019年1月24日
 */
@Mojo(name = "tisasm")
public class TisSingleAssemblyMojo extends SingleAssemblyMojo {

	@Parameter(defaultValue = "${project.build.finalName}", required = true)
	private String appnamePattern;

	// http://maven.apache.org/guides/plugin/guide-java-plugin-development.html
	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {

		final String appname = System.getProperty("appname");

		if (!shallExecute(this.getLog(), appnamePattern, appname)) {
			return;
		}

		super.execute();
	}

	public static boolean shallExecute(Log log, String appnamePattern, final String appname) {

		if ("all".equals(appname)) {
			// 所有的工程都需要打包
			log.info("appname:" + appname + " will assemble all module");
			return true;
		}

		if (StringUtils.isEmpty(appname)) {
			return false;
		}
		Pattern pattern = Pattern.compile(appnamePattern);
		log.info("appname pattern:" + appnamePattern);
		Matcher m = pattern.matcher(appname);
		if (!m.matches()) {
			log.info("appname:" + appname + " is not match pattern:" + pattern.toString()
					+ " ignore this assemble process");
			return false;
		}

		return true;
	}
}
