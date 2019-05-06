package org.jenkinsci.plugins.skytap;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Writer;
import java.util.Iterator;

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.FilePath;

import org.apache.http.client.methods.HttpGet;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapAction;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapActionDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

public class ListPublishedURLForConfigurationStep extends SkytapAction {

	private final String configurationID;
	private final String configurationFile;
	private final String urlName;
	private final String urlFile;

	// these vars will be initialized when the step is run
	@XStreamOmitField
	private SkytapGlobalVariables globalVars;

	// the runtime environment id will be set one of two ways:
	// either the user has provided just a environment id, so we use it,
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
	public ListPublishedURLForConfigurationStep(String configurationID,
			String configurationFile, String urlName, String urlFile) {

		super("List Published URL For Configuration");

		this.configurationID = configurationID;
		this.configurationFile = configurationFile;
		this.urlFile = urlFile;
		this.urlName = urlName;

	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger
				.defaultLogMessage("----------------------------------------");
		JenkinsLogger
				.defaultLogMessage("List Sharing Portal for Environment Step");
		JenkinsLogger
				.defaultLogMessage("----------------------------------------");

		if (preFlightSanityChecks() == false) {
			return false;
		}

		this.globalVars = globalVars;
		authCredentials = SkytapUtils.getAuthCredentials(build);

		// reset step parameters with env vars resolved at runtime
		String expConfigurationFile = SkytapUtils.expandEnvVars(build,
				configurationFile);

		String expUrlFile = SkytapUtils.expandEnvVars(build, urlFile);
		expUrlFile = SkytapUtils.convertFileNameToFullPath(build, expUrlFile);

		// if user has provided just a filename with no path, default to
		// place it in their Jenkins workspace
		if (!expConfigurationFile.equals("")) {
			expConfigurationFile = SkytapUtils.convertFileNameToFullPath(build,
					expConfigurationFile);
		}

		// get runtime environment id
		try {
			runtimeConfigurationID = SkytapUtils.getRuntimeId(configurationID,
					expConfigurationFile);
		} catch (FileNotFoundException e) {
			JenkinsLogger.error("Error obtaining runtime environment id: "
					+ e.getMessage());
		}

		JenkinsLogger.log("Environment ID: " + runtimeConfigurationID);
		JenkinsLogger.log("Environment File: " + expConfigurationFile);
		JenkinsLogger.log("URL Name: " + urlName);
		JenkinsLogger.log("URL Save Filename: " + expUrlFile);

		// build request url
		String requestUrl = buildListRequestURL(runtimeConfigurationID);

		// build http get request
		HttpGet hg = SkytapUtils.buildHttpGetRequest(requestUrl,
				authCredentials);

		// execute request
		String httpRespBody = "";

		try {

			httpRespBody = SkytapUtils.executeHttpRequest(hg);
			SkytapUtils.checkResponseForErrors(httpRespBody);

		} catch (SkytapException e) {

			JenkinsLogger.error(e.getMessage());
			return false;

		}

		// extract publish_sets from json response
		JsonParser parser = new JsonParser();
		JsonElement je = parser.parse(httpRespBody);
		JsonObject jo = je.getAsJsonObject();
		JsonArray ja = jo.getAsJsonArray("publish_sets");

		String publishedUrl = "";
		try {
			publishedUrl = getPublishedUrl(ja, urlName);
		} catch (SkytapException e1) {
			JenkinsLogger.error(e1.getMessage());
			return false;
		}

		if (publishedUrl.equals("")) {

			JenkinsLogger.error("URL Name: " + urlName
					+ " could not be found in publish_sets for environment "
					+ runtimeConfigurationID);
			JenkinsLogger.error("Failing build step.");
			return false;

		} else {

			JenkinsLogger.log("Outputting url to file: " + expUrlFile);

			try {

				// output to the file system
				FilePath fp = new FilePath(build.getWorkspace(), expUrlFile);
				fp.write(publishedUrl);

			} catch (IOException e) {
				JenkinsLogger.error("Error: " + e.getMessage());

				JenkinsLogger
						.error("Skytap Plugin failed to save url to file: "
								+ expUrlFile);
				return false;
			} catch (InterruptedException e) {
				JenkinsLogger.error("Error: " + e.getMessage());
				return false;
			}

		}

		return true;
	}

	private String getPublishedUrl(JsonArray ja, String name) throws SkytapException {

		JenkinsLogger.log("Scanning publish_sets ...");

		Iterator iter = ja.iterator();

		// iterate through each publish set and check name
		while (iter.hasNext()) {

			JsonElement publishSetElement = (JsonElement) iter.next();

			// check for name match
			String pubSetName = publishSetElement.getAsJsonObject().get("name").getAsString();
			JenkinsLogger.log("Sharing Portal Name: " + pubSetName);

			if(pubSetName.equals(name)){

			JenkinsLogger.log("Sharing Portal Name matched: " + pubSetName);

			String publishSetType = publishSetElement.getAsJsonObject().get("publish_set_type").getAsString();

			// if publish set type is multiple url, throw an error
			if ( publishSetType.equals("multiple_url") ){
				throw new SkytapException("URLs for individual VMs are not supported.");
			}

			// if publish set type is single url
			if ( publishSetType.equals("single_url") ){
				String desktopsUrl = publishSetElement.getAsJsonObject().get("desktops_url").getAsString();
				return desktopsUrl;
			}

			}

		}

		// no match?
		JenkinsLogger
				.log("No publish_sets matched user provided name: " + name);

		return "";

	}

	private String buildListRequestURL(String confId) {

		JenkinsLogger.log("Building request url ...");

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com");

		sb.append("/configurations/");
		sb.append(confId);

		JenkinsLogger.log("Request URL: " + sb.toString());
		return sb.toString();

	}

	/**
	 * "publish_sets": [ { "desktops_url":
	 * "https://cloud.skytap.com/vms/9119366dd7ead4cbb61e3e528215ef3c/desktops",
	 * "end_time": null, "id": "511590", "name": "testpublish", "password":
	 * null, "publish_set_type": "single_url", "start_time": null, "time_zone":
	 * null, "url":
	 * "https://cloud.skytap.com/configurations/1453536/publish_sets/511590",
	 * "use_smart_client": true, "vms": [ { "access": "use", "id":
	 * "e24f463d22a64e99d2690da5b0131603", "name":
	 * "Jenkins Test Server - CentOS 6.4 64 bit", "run_and_use": false,
	 * "vm_ref": "https://cloud.skytap.com/vms/2554726" } ] } ],
	 */

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			ListPublishedURLForConfigurationStep.class,
			"List Sharing Portal for Environment");

	public String getConfigurationID() {
		return configurationID;
	}

	public String getConfigurationFile() {
		return configurationFile;
	}

	public String getUrlName() {
		return urlName;
	}

	public String getUrlFile() {
		return urlFile;
	}

	private Boolean preFlightSanityChecks() {

		// check whether user failed to provide a url file
		if (this.urlFile.equals("")) {
			JenkinsLogger
					.error("No value was provided for the URL save filename. Please provide a filename.");
			return false;
		}

		// check whether user failed to provide a url name
		if (this.urlName.equals("")) {
			JenkinsLogger
					.error("No value was provided for URL name. Please provide a valid url name.");
			return false;
		}

		// check whether user entered both values for conf id/conf file
		if (!this.configurationID.equals("")
				&& !this.configurationFile.equals("")) {
			JenkinsLogger
					.error("Values were provided for both environment ID and file. Please provide just one or the other.");
			return false;
		}

		// check whether we have neither conf id or file
		if (this.configurationFile.equals("")
				&& this.configurationID.equals("")) {
			JenkinsLogger
					.error("No value was provided for environment ID or file. Please provide either a valid Skytap environment ID, or a valid environment file.");
			return false;
		}

		return true;

	}

}
