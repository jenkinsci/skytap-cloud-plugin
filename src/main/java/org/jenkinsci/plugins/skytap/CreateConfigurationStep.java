//
// Copyright (c) 2013, Skytap, Inc
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of this software and associated documentation files (the "Software"),
// to deal in the Software without restriction, including without limitation
// the rights to use, copy, modify, merge, publish, distribute, sublicense,
// and/or sell copies of the Software, and to permit persons to whom the
// Software is furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.
//                        
package org.jenkinsci.plugins.skytap;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.URLEncoder;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
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

public class CreateConfigurationStep extends SkytapAction {

	private final String templateID;
	private final String templateFile;

	private final String configName;
	private final String configFile;

	// number of times it will poll Skytap to see if template is busy
	private static final int NUMBER_OF_RETRIES = 18;
	private static final int RETRY_INTERVAL_SECONDS = 10;

	@XStreamOmitField
	private String runtimeTemplateID;

	@XStreamOmitField
	private String authCredentials;

	// these will be initialized when the step is run
	@XStreamOmitField
	private SkytapGlobalVariables globalVars;

	@DataBoundConstructor
	public CreateConfigurationStep(String templateID, String templateFile,
			String configName, String configFile) {
		super("Create Configuration from Template");
		this.templateID = templateID;
		this.templateFile = templateFile;
		this.configFile = configFile;
		this.configName = configName;
	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger
				.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Creating Environment from Template");
		JenkinsLogger
				.defaultLogMessage("----------------------------------------");

		if (preFlightSanityChecks() == false) {
			return false;
		}

		this.globalVars = globalVars;
		this.authCredentials = SkytapUtils.getAuthCredentials(build);

		String expTemplateFile = SkytapUtils.expandEnvVars(build, templateFile);
		String expConfigFile = SkytapUtils.expandEnvVars(build, configFile);
		String expConfigName = SkytapUtils.expandEnvVars(build, configName);

		// if user has provided just a filename with no path, default to
		// place it in their Jenkins workspace

		if (!expTemplateFile.equals("")) {
			expTemplateFile = SkytapUtils.convertFileNameToFullPath(build,
					expTemplateFile);
		}

		if (!expConfigFile.equals("")) {
			expConfigFile = SkytapUtils.convertFileNameToFullPath(build,
					expConfigFile);
		}

		JenkinsLogger.log("Template File: " + expTemplateFile);
		JenkinsLogger.log("Config File: " + expConfigFile);
		JenkinsLogger.log("Config Name: " + expConfigName);

		try {
			runtimeTemplateID = SkytapUtils.getRuntimeId(templateID,
					expTemplateFile);
		} catch (FileNotFoundException e1) {
			JenkinsLogger.error("Error obtaining template id: "
					+ e1.getMessage());
			return false;
		}

		JenkinsLogger.log("Template ID:  " + runtimeTemplateID);

		// check busy state of template - if template doesn't become
		// available after configured wait time, fail build step
		if (checkIsTemplateAvailable(runtimeTemplateID) == false) {
			JenkinsLogger.error("Template ID: " + runtimeTemplateID
					+ " has not become available yet. Failing build step.");
			return false;
		}

		// build request url
		String requestURL = buildCreateConfigRequestURL(runtimeTemplateID);

		// create request for Skytap API
		HttpPost hp = SkytapUtils.buildHttpPostRequest(requestURL,
				this.authCredentials);

		// execute request
		String httpRespBody = "";

		try {
			httpRespBody = SkytapUtils.executeHttpRequest(hp);
		} catch (SkytapException e1) {
			JenkinsLogger.error("Skytap Exception: " + e1.getMessage());
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

		// TODO: extract into separate method
		// if name was provided, update the created environment's name
		// using an http put request
		if (!expConfigName.equals("")) {

			String configId = jo.get("id").getAsString();

			// build request url
			String updateRequestURL = buildUpdateConfigNameRequestURL(configId,
					expConfigName);

			// create request for Skytap API
			HttpPut hput = SkytapUtils.buildHttpPutRequest(updateRequestURL,
					this.authCredentials);

			// execute request
			httpRespBody = "";

			try {
				httpRespBody = SkytapUtils.executeHttpRequest(hput);
			} catch (SkytapException ex) {
				JenkinsLogger.error("Request returned an error: "
						+ ex.getError());
				JenkinsLogger.error("Failing build step.");
				return false;
			}

			try {
				SkytapUtils.checkResponseForErrors(httpRespBody);
			} catch (SkytapException ex) {
				JenkinsLogger.error("Request returned an error: "
						+ ex.getError());
				JenkinsLogger.error("Failing build step.");
				return false;
			}

			// if the update succeeded, update the json object also
			je = parser.parse(httpRespBody);
			jo = je.getAsJsonObject();

		}

		// save json object to the environment file path
		// if user has provided just a filename with no path, default to
		// place it in their Jenkins workspace
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
					.error("Skytap Plugin failed to save environment to file: "
							+ expConfigFile);
			return false;
		}

		// Sleep for a a few seconds to make sure the Config is stable, then we
		// can exit
		try {
			Thread.sleep(10000);
		} catch (InterruptedException e) {
			JenkinsLogger.error("Error: " + e.getMessage());
		}

		JenkinsLogger
				.defaultLogMessage("Environment successfully created and saved to file: "
						+ expConfigFile);
		JenkinsLogger
				.defaultLogMessage("----------------------------------------");
		return true;
	}

	private String buildCreateConfigRequestURL(String templateId) {

		JenkinsLogger.log("Building request url ...");

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append("?template_id=");
		sb.append(templateId);

		JenkinsLogger.log("Request URL: " + sb.toString());
		return sb.toString();

	}

	private String buildUpdateConfigNameRequestURL(String newConfigurationId,
			String newConfigurationName) {

		JenkinsLogger.log("Building request url ...");

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append(newConfigurationId);
		sb.append("?name=");
		sb.append(URLEncoder.encode(newConfigurationName));

		JenkinsLogger.log("Request URL: " + sb.toString());
		return sb.toString();

		// https://cloud.skytap.com/configurations/1156812?name=updatedconfigname
	}

	/**
	 * This method verifies the busy status of the template to be used. In the
	 * event of a busy status, it enters a wait/retry loop.
	 * 
	 * @param tempId
	 */
	private Boolean checkIsTemplateAvailable(String tempId) {

		JenkinsLogger.log("Checking availability of template with id: "
				+ tempId);

		// build busy check request
		String requestURL = buildCheckTemplateURL(tempId);
		HttpGet hg = SkytapUtils.buildHttpGetRequest(requestURL,
				this.authCredentials);

		// repeatedly execute request until template is not busy
		String httpRespBody = "";
		Boolean templateIsAvailable = false;

		try {

			int numPollingAttempts = 0;

			while (!templateIsAvailable
					&& (numPollingAttempts < this.NUMBER_OF_RETRIES)) {

				httpRespBody = SkytapUtils.executeHttpRequest(hg);

				// get json object from the response
				JsonParser parser = new JsonParser();
				JsonElement je = parser.parse(httpRespBody);
				JsonObject jo = je.getAsJsonObject();

				// get busy status - if busy in returned
				// JSON array is 'null', means template is not busy

				if (jo.get("busy").isJsonNull()) {
					templateIsAvailable = true;
					JenkinsLogger.log("Template is available.");
				} else {
					templateIsAvailable = false;
					JenkinsLogger.log("Template is busy.");

					// wait before trying again
					int sleepTime = this.RETRY_INTERVAL_SECONDS;
					JenkinsLogger
							.log("Sleeping for " + sleepTime + " seconds.");
					Thread.sleep(sleepTime * 1000);
				}

				numPollingAttempts++;
			}

			return templateIsAvailable;

		} catch (SkytapException ex) {
			JenkinsLogger.error("Request returned an error: " + ex.getError());
			JenkinsLogger.error("Failing build step.");
			return false;
		} catch (InterruptedException e1) {
			JenkinsLogger.error(e1.getMessage());
			return false;
		}

	}

	private String buildCheckTemplateURL(String id) {

		JenkinsLogger.log("Building request url ...");

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("templates/");
		sb.append(id);

		JenkinsLogger.log("Request URL: " + sb.toString());
		return sb.toString();

		// https://cloud.skytap.com/templates/322249

	}

	/**
	 * This method is a final check to ensure that user inputs are legitimate.
	 * Any situation where the user has entered both inputs in an either/or
	 * scenario will fail the build. If the user has left both blank where we
	 * need one, it will also fail.
	 * 
	 * @return Boolean sanityCheckPassed
	 */
	private Boolean preFlightSanityChecks() {

		// check whether user entered both values for template id/template file
		if (!this.templateID.equals("") && !this.templateFile.equals("")) {
			JenkinsLogger
					.error("Values were provided for both template ID and file. Please provide just one or the other.");
			return false;
		}

		// check whether we have neither template id nor file
		if (this.templateID.equals("") && this.templateFile.equals("")) {
			JenkinsLogger
					.error("No value was provided for template ID or file. Please provide either a valid Skytap template ID, or a valid template file.");
			return false;
		}

		// check whether no environment file value was provided
		if (this.configFile.equals("")) {
			JenkinsLogger
					.error("No value was provided for the environment file. Please provide a valid environment file value.");
			return false;
		}

		return true;
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

	public String getConfigName() {
		return configName;
	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			CreateConfigurationStep.class, "Create Environment from Template");

}
