package org.jenkinsci.plugins.skytap;

import java.io.BufferedWriter;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.URLEncoder;
import java.util.Iterator;

import hudson.Extension;
import hudson.model.AbstractBuild;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.BasicHttpEntity;
import org.jenkinsci.plugins.skytap.CreatePublishURLStep.RequirePasswordBlock;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapAction;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapActionDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

public class CreateContainerStep extends SkytapAction {

	private final String configurationID;
	private final String configurationFile;

	private final String vmID;
	private final String vmName;

	private final String containerRegistryName;
	private final String repositoryName;

	private final String containerName;
	private final String containerCommand;
	private final Boolean exposeAllPorts;

	private final String containerSaveFilename;

	// these will be initialized when the step is run
	@XStreamOmitField
	private SkytapGlobalVariables globalVars;

	@XStreamOmitField
	private String runtimeConfigurationID;

	@XStreamOmitField
	private String runtimeContainerRegistryId;

	@XStreamOmitField
	private String runtimeVMID;

	@XStreamOmitField
	private String authCredentials;

	@DataBoundConstructor
	public CreateContainerStep(String configurationID,
			String configurationFile, String vmID, String vmName,
			String containerRegistryName, String repositoryName,
			String containerName, String containerCommand, Boolean exposeAllPorts,
			String containerSaveFilename) {


		super("Create Container Step");

		this.configurationID = configurationID;
		this.configurationFile = configurationFile;
		this.vmID = vmID;
		this.vmName = vmName;
		this.repositoryName = repositoryName;
		this.containerRegistryName = containerRegistryName;
		this.containerCommand = containerCommand;
		this.exposeAllPorts = exposeAllPorts;
		this.containerName = containerName;
		this.containerSaveFilename = containerSaveFilename;

	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger
				.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Create Container");
		JenkinsLogger
				.defaultLogMessage("----------------------------------------");

		if (preFlightSanityChecks() == false) {
			return false;
		}

		this.globalVars = globalVars;
		this.authCredentials = SkytapUtils.getAuthCredentials(build);

		// reset step parameters with env vars resolved at runtime
		String expConfigurationFile = SkytapUtils.expandEnvVars(build,
				configurationFile);

		// if no path was provided (just filename), convert to jenkins workspace
		// path
		if (!expConfigurationFile.isEmpty()) {
			expConfigurationFile = SkytapUtils.convertFileNameToFullPath(build,
					expConfigurationFile);
		}

		// get runtime environment id
		try {
			runtimeConfigurationID = SkytapUtils.getRuntimeId(configurationID,
					expConfigurationFile);
		} catch (FileNotFoundException e) {
			JenkinsLogger.error("Error retrieving environment id: "
					+ e.getMessage());
			return false;
		}

		// get runtime VM id
		try {

			if (!vmName.isEmpty()) {
				runtimeVMID = SkytapUtils.getVMIDFromName(
						runtimeConfigurationID, vmName, authCredentials);
			}else {
				runtimeVMID = this.vmID;
			}

		} catch (SkytapException e1) {
			JenkinsLogger.error(e1.getMessage());
			return false;
		}

		// get runtime Container Registry id
		try {

			runtimeContainerRegistryId = SkytapUtils.getContainerRegistryIdFromName(
					containerRegistryName, authCredentials);

		} catch (SkytapException e1) {
			JenkinsLogger.error(e1.getMessage());
			return false;
		}

		// add content to request - Create Container
		BasicHttpEntity he = new BasicHttpEntity();
		he.setContentEncoding("gzip");
		he.setContentType("application/json");

		// json string for container registry id

		StringBuilder bodyString = new StringBuilder("{\"container_registry_id\":" + runtimeContainerRegistryId + ",\n");
		bodyString.append("\"repository\":\"" + repositoryName + "\"");
		if (!containerName.isEmpty()) {
			bodyString.append(",\"name\":\"" + containerName + "\"");
		} 
		bodyString.append(",");
		bodyString.append("\"operation\": {");
		if (exposeAllPorts) {
			bodyString.append("\"expose_all_ports\":true");
		}  else {
			bodyString.append("\"expose_all_ports\":false");
		}
		if (!containerCommand.isEmpty()) {
			bodyString.append(",\"command\":\"" + containerCommand + "\"");
		} 
		
		bodyString.append("}");
		bodyString.append("}");

		String jsonString = bodyString.toString();
//		JenkinsLogger.log("DEBUG BodyString = " + jsonString);
		

		InputStream stream;
		try {
			stream = new ByteArrayInputStream(jsonString.getBytes("UTF-8"));
			Integer len = jsonString.getBytes("UTF-8").length;
			long llen = len.longValue();

			he.setContent(stream);
			he.setContentLength(llen);

		} catch (UnsupportedEncodingException e) {
			JenkinsLogger.error("Error encoding json string for vpn id: "
					+ e.getMessage());

		}


		// build post request url
		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append(runtimeConfigurationID);

		sb.append("/vms/");
		sb.append(runtimeVMID);

		sb.append("/containers");

		String createContainerURL = sb.toString();

		// build request
		HttpPost hp = SkytapUtils.buildHttpPostRequest(createContainerURL,
				authCredentials);

		hp.setEntity(he);
		String postResponse = "";

		JenkinsLogger.log("Creating new container created with name \"" + containerName
				+ "\" ");

		// execute request
		try {
			postResponse = SkytapUtils.executeHttpRequest(hp);
		} catch (SkytapException e) {
			JenkinsLogger.error(e.getMessage());
			return false;
		}

		JenkinsLogger.log(postResponse);

		try {
			SkytapUtils.checkResponseForErrors(postResponse);
		} catch (SkytapException e) {
			JenkinsLogger.error(e.getMessage());
			return false;
		}

		JenkinsLogger.log("New container created with name \"" + containerName
				+ "\"");


		String expContainerSaveFile = SkytapUtils.expandEnvVars(
				build, containerSaveFilename);
		expContainerSaveFile = SkytapUtils.convertFileNameToFullPath(
				build, expContainerSaveFile);
		JenkinsLogger.log("Outputting container metadata "
				+ " to file: " + expContainerSaveFile);

		try {

			// output to the file system
			File file = new File(expContainerSaveFile);
			Writer output = null;
			output = new BufferedWriter(new FileWriter(file));
			output.write(postResponse);
			output.close();

		} catch (IOException e) {

			JenkinsLogger.error("Skytap Plugin failed to save url to file: "
					+ expContainerSaveFile);
			return false;
		}

		JenkinsLogger.log("Created new container with name \"" + containerName
				+ "\"");
		return true;
	}


	private Boolean preFlightSanityChecks() {

		// check whether user entered both values for environment id/conf file
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

		// make sure network name, port number and published service file were
		// provided
//		if (this.networkName.equals("") || this.portNumber == 0
//				|| this.publishedServiceFile.equals("")) {
//			JenkinsLogger
//					.error("One or more arguments were omitted. Please provide all of the following: network name, port number and published service save file.");
//			return false;
//		}

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

//	public String getNetworkName() {
//		return networkName;
//	}

//	public int getPortNumber() {
//		return portNumber;
//	}

//	public String getPublishedServiceFile() {
//		return publishedServiceFile;
//	}

	public String getContainerRegistryName() {
		return containerRegistryName;
	}

	public String getRepositoryName() {
		return repositoryName;
	}

	public String getContainerName() {
		return containerName;
	}

	public String getContainerCommand() {
		return containerCommand;
	}

	public Boolean getExposeAllPorts() {
		return exposeAllPorts;
	}

	public String getContainerSaveFilename() {
		return containerSaveFilename;
	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			CreateContainerStep.class, "Create Container");

}
