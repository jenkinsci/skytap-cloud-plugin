package org.jenkinsci.plugins.skytap;

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.FilePath;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Writer;
import java.net.URLEncoder;
import java.util.Iterator;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapAction;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapActionDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

public class GetContainerMetaDataStep extends SkytapAction {

	private final String configurationID;
	private final String configurationFile;

	private final String vmID;
	private final String vmName;

	private final String containerName;

	private final String containerDataFile;

	// these will be initialized when the step is run
	@XStreamOmitField
	private SkytapGlobalVariables globalVars;

	@XStreamOmitField
	private String runtimeConfigurationID;

	@XStreamOmitField
	private String runtimeVMID;

	@XStreamOmitField
	private String authCredentials;

	@DataBoundConstructor
	public GetContainerMetaDataStep(String configurationID,
			String configurationFile, String vmID, String vmName,
			String containerName, String containerDataFile) {

		super("Get Container Metadata Step");

		this.configurationID = configurationID;
		this.configurationFile = configurationFile;
		this.vmID = vmID;
		this.vmName = vmName;
		this.containerName = containerName;
		this.containerDataFile = containerDataFile;

	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger
				.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Get Container Metadata");
		JenkinsLogger
				.defaultLogMessage("----------------------------------------");

		if (preFlightSanityChecks() == false) {
			return false;
		}

		this.globalVars = globalVars;
		this.authCredentials = SkytapUtils.getAuthCredentials(build);
		// get runtime environment id
		String expConfigFile = SkytapUtils.expandEnvVars(build, configurationFile);
		if (!expConfigFile.equals("")) {
			expConfigFile = SkytapUtils.convertFileNameToFullPath(build,
					expConfigFile);
		}
		try {
			runtimeConfigurationID = SkytapUtils.getRuntimeId(configurationID,
					expConfigFile);
		} catch (FileNotFoundException e) {
			JenkinsLogger.error("Error retrieving environment id: "
					+ e.getMessage());
			return false;
		}

		// if the user provided a vm name,
		// get the vm id.. queries that use the vm name return different types of responses

		if(!vmName.isEmpty()){

			try {
				this.runtimeVMID = SkytapUtils.getVMIDFromName(runtimeConfigurationID, vmName, authCredentials);
			} catch (SkytapException e) {
				JenkinsLogger.error(e.getMessage());
				return false;
			}

		} else { runtimeVMID = vmID; }

		// get container ID from name

		String containerId = "";
		try {
			containerId = SkytapUtils.getVMContainerIdFromName(runtimeConfigurationID, runtimeVMID, containerName, authCredentials);
		} catch (SkytapException e) {
			JenkinsLogger.error(e.getMessage());
			return false;
		}

		// build get request url
		String getContainerMetadataURL = buildGetContainerDataURL(containerId);

		// build request
		HttpGet hget = SkytapUtils.buildHttpGetRequest(getContainerMetadataURL,
				authCredentials);

		String containerMetadataResponse = "";

		// execute request
		try {
			containerMetadataResponse = SkytapUtils.executeHttpRequest(hget);
		} catch (SkytapException e) {
			JenkinsLogger.error(e.getMessage());
			return false;
		}

		try {
			SkytapUtils.checkResponseForErrors(containerMetadataResponse);
		} catch (SkytapException ex) {
			JenkinsLogger
					.error("Request returned an error: " + ex.getMessage());
			JenkinsLogger.error("Failing build step.");
			return false;
		}

		JenkinsLogger.log(containerMetadataResponse);

		String expContainerDataFile = SkytapUtils.expandEnvVars(
				build, containerDataFile);
		expContainerDataFile = SkytapUtils.convertFileNameToFullPath(
				build, expContainerDataFile);
		JenkinsLogger.log("Outputting container metadata to file: " + expContainerDataFile);

		try {

			// output to the file system
			FilePath fp = new FilePath(build.getWorkspace(), expContainerDataFile);
			fp.write(containerMetadataResponse);

		} catch (IOException e) {
			JenkinsLogger.error("Error: " + e.getMessage());

			JenkinsLogger.error("Skytap Plugin failed to save container metadata to file: "
					+ expContainerDataFile);
			return false;
		} catch (InterruptedException e) {
			JenkinsLogger.error("Error: " + e.getMessage());
			return false;
		}

		return true;
	}

	private String buildGetContainerDataURL(String containerId) {

		JenkinsLogger.log("Building request url ...");

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("v2/containers/");
		sb.append(containerId);
		sb.append(".json");

		return sb.toString();

	}

	private Boolean preFlightSanityChecks() {

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

		// check whether user omitted vmID or name
		if (this.vmID.isEmpty() && this.vmName.isEmpty()) {
			JenkinsLogger
					.error("No value was provided for VM ID or name. Please provide either a valid Skytap VM ID or name.");
			return false;
		}

		// check whether user provided both vm id and name
		if (!this.vmID.equals("") && !this.vmName.equals("")) {
			JenkinsLogger
					.error("Values were provided for both VM ID and name. Please provide just one or the other.");
			return false;
		}

		// make sure container name and published service file were
		// provided
		if (this.containerName.equals("") || this.containerDataFile.equals("")) {
			JenkinsLogger
					.error("One or more arguments were omitted. Please provide all of the following: container name and container data save file.");
			return false;
		}

		return true;

	}

	public String getConfigurationID() {
		return configurationID;
	}

	public String getConfigurationFile() {
		return configurationFile;
	}

	public String getVmID() {
		return vmID;
	}

	public String getVmName() {
		return vmName;
	}

	public String getContainerName() {
		return containerName;
	}

	public String getContainerDataFile() {
		return containerDataFile;
	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			GetContainerMetaDataStep.class, "Get Container Metadata");

}
