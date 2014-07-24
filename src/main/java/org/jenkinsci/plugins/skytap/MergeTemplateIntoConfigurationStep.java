package org.jenkinsci.plugins.skytap;


import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.URLEncoder;

import hudson.Extension;
import hudson.model.AbstractBuild;

import org.apache.http.client.methods.HttpPut;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapAction;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapActionDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

public class MergeTemplateIntoConfigurationStep extends SkytapAction {

	private final String configurationID;
	private final String configurationFile;

	private final String templateID;
	private final String templateFile;
	private final String configFile;


	// these vars will be initialized when the step is run
	@XStreamOmitField
	private SkytapGlobalVariables globalVars;

	// the runtime config id will be set one of two ways:
	// either the user has provided just a config id, so we use it,
	// or the user provided a file, in which case we read the file and extract
	// the
	// id from the json element
	@XStreamOmitField
	private String runtimeConfigurationID;

	@XStreamOmitField
	private String runtimeTemplateID;

	@XStreamOmitField
	private String authCredentials;

	@DataBoundConstructor
	public MergeTemplateIntoConfigurationStep(String configurationID,
			String configurationFile, String templateID, String templateFile, String configFile) {

		super("Merge Template into Configuration");

		this.configurationID = configurationID;
		this.configurationFile = configurationFile;
		this.templateID = templateID;
		this.templateFile = templateFile;
		this.configFile = configFile;
	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger
				.defaultLogMessage("----------------------------------------");
		JenkinsLogger
				.defaultLogMessage("Merge Template into Configuration Step");
		JenkinsLogger
				.defaultLogMessage("----------------------------------------");

		if (preFlightSanityChecks() == false) {
			return false;
		}

		this.globalVars = globalVars;
		authCredentials = SkytapUtils.getAuthCredentials(build);

		// reset step parameters with env vars resolved at runtime
		String expTemplateFile = SkytapUtils.expandEnvVars(build, templateFile);

		if (!expTemplateFile.equals("")) {
			expTemplateFile = SkytapUtils.convertFileNameToFullPath(build,
					expTemplateFile);
		}

		String expConfigurationFile = SkytapUtils.expandEnvVars(build,
				configurationFile);

		// if user has provided just a filename with no path, default to
		// place it in their Jenkins workspace
		if (!expConfigurationFile.equals("")) {
			expConfigurationFile = SkytapUtils.convertFileNameToFullPath(build,
					expConfigurationFile);
		}
		String expConfigFile = SkytapUtils.expandEnvVars(build, configFile);
		if (!expConfigFile.equals("")) {
			expConfigFile = SkytapUtils.convertFileNameToFullPath(build,
					expConfigFile);
		}

		// get runtime config id
		try {
			runtimeConfigurationID = SkytapUtils.getRuntimeId(configurationID,
					expConfigurationFile);
		} catch (FileNotFoundException e) {
			JenkinsLogger.error("Error obtaining runtime configuration id: "
					+ e.getMessage());
		}

		// get runtime template id
		try {
			runtimeTemplateID = SkytapUtils.getRuntimeId(templateID,
					expTemplateFile);
		} catch (FileNotFoundException ex) {
			JenkinsLogger.error("Error obtaining runtime template id: "
					+ ex.getMessage());
			return false;
		}

		JenkinsLogger.log("Template ID: " + runtimeTemplateID);
		JenkinsLogger.log("Template File: " + expTemplateFile);
		JenkinsLogger.log("Configuration ID: " + runtimeConfigurationID);
		JenkinsLogger.log("Configuration File: " + expConfigurationFile);

		// build request url
		String requestUrl = buildMergeRequestURL(runtimeTemplateID,
				runtimeConfigurationID);

		// create request
		HttpPut hp = SkytapUtils.buildHttpPutRequest(requestUrl,
				authCredentials);

		// execute request
		String httpRespBody = "";

		try {
			httpRespBody = SkytapUtils.executeHttpRequest(hp);
		} catch (SkytapException e) {
			JenkinsLogger.error("Skytap Exception: " + e.getMessage());
			return false;
		}

		try {
			SkytapUtils.checkResponseForErrors(httpRespBody);
		} catch (SkytapException ex) {
			JenkinsLogger.error("Request returned an error: " + ex.getError());
			JenkinsLogger.error("Failing build step.");
			return false;
		}
		// get json object from the response
		JsonParser parser = new JsonParser();
		JsonElement je = parser.parse(httpRespBody);
		JsonObject jo = je.getAsJsonObject();

		// save json object to the config file path
		// if user has provided just a filename with no path, default to
		// place it in their Jenkins workspace
		if (!expConfigFile.equals("")) {
			expConfigFile = SkytapUtils.convertFileNameToFullPath(build,
				expConfigFile);

			Writer output = null;
			File file = new File(expConfigFile);
			try {

				output = new BufferedWriter(new FileWriter(file));
				output.write(httpRespBody);
				output.close();
			} catch (IOException e) {

				JenkinsLogger
						.error("Skytap Plugin failed to save configuration to file: "
							+ expConfigFile);
				return false;
			}
		}


		JenkinsLogger.log("Template " + runtimeTemplateID
				+ " was successfully merged to configuration "
				+ runtimeConfigurationID);

		return true;

	}

	private String buildMergeRequestURL(String tempId, String confId) {

		JenkinsLogger.log("Building request url ...");

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com");

		sb.append("/configurations/");
		sb.append(confId);
		sb.append("?template_id=");
		sb.append(tempId);

		JenkinsLogger.log("Request URL: " + sb.toString());
		return sb.toString();
		// https://cloud.skytap.com/configurations/1453524?template_id=176485
	}

	private Boolean preFlightSanityChecks() {

		// check whether user entered both values for template id/template file
		if (!this.templateID.equals("") && !this.templateFile.equals("")) {
			JenkinsLogger
					.error("Values were provided for both template id and file. Please provide just one or the other.");
			return false;
		}

		// check whether user provided neither template id nor file
		if (!this.templateID.equals("") && !this.templateFile.equals("")) {
			JenkinsLogger
					.error("No value was provided for template ID or file. Please provide either a valid Skytap template ID, or a valid template file.");
			return false;
		}

		// check whether user entered both values for conf id/conf file
		if (!this.configurationID.equals("")
				&& !this.configurationFile.equals("")) {
			JenkinsLogger
					.error("Values were provided for both configuration ID and file. Please provide just one or the other.");
			return false;
		}

		// check whether we have neither conf id or file
		if (this.configurationFile.equals("")
				&& this.configurationID.equals("")) {
			JenkinsLogger
					.error("No value was provided for configuration ID or file. Please provide either a valid Skytap configuration ID, or a valid configuration file.");
			return false;
		}

		// check whether no config file value was provided
		if (this.configFile.equals("")) {
			JenkinsLogger
					.log("No optional value was provided for a new configuration data save file. Continuing...");
			return true;
		}

		return true;

	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			MergeTemplateIntoConfigurationStep.class,
			"Merge Template into Configuration");

	public String getConfigurationID() {
		return configurationID;
	}

	public String getConfigurationFile() {
		return configurationFile;
	}

	public String getTemplateID() {
		return templateID;
	}

	public String getTemplateFile() {
		return templateFile;
	}

	public String getConfigFile() {
		return configFile;
	}

}
