package org.jenkinsci.plugins.skytap;

import hudson.Extension;
import hudson.model.AbstractBuild;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
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

public class ListVMPublishedServiceStep extends SkytapAction {

	private final String configurationID;
	private final String configurationFile;

	private final String vmID;
	private final String vmName;

	private final String networkName;
	private final int portNumber;

	private final String publishedServiceFile;

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
	public ListVMPublishedServiceStep(String configurationID,
			String configurationFile, String vmID, String vmName,
			String networkName, String portNumber, String publishedServiceFile) {

		super("List VM Published Service Step");

		this.configurationID = configurationID;
		this.configurationFile = configurationFile;
		this.vmID = vmID;
		this.vmName = vmName;
		this.networkName = networkName;
		this.portNumber = Integer.parseInt(portNumber);
		this.publishedServiceFile = publishedServiceFile;

	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger
				.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("List VM Published Service");
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
		
		// build url to get interfaces associated with environment/vm.
		String requestURL = buildGetInterfacesURL(runtimeConfigurationID, runtimeVMID);

		// build request
		HttpGet hg = SkytapUtils.buildHttpGetRequest(requestURL,
				authCredentials);

		// execute request
		String httpRespBody = "";

		try {
			httpRespBody = SkytapUtils.executeHttpRequest(hg);
		} catch (SkytapException e) {
			JenkinsLogger.error(e.getMessage());
			return false;
		}

		try {
			SkytapUtils.checkResponseForErrors(httpRespBody);
		} catch (SkytapException ex) {
			JenkinsLogger.error("Request returned an error: " + ex.getError());
			JenkinsLogger.error("Failing build step.");
			return false;
		}

		String interfaceId = "";

		try {
			interfaceId = getInterfaceId(httpRespBody, networkName);
		} catch (SkytapException e) {
			JenkinsLogger.error("Could not retrieve interface id: "
					+ e.getMessage());
			return false;
		}

		// build get request url
		String listServiceURL = buildListPublishedServiceURL(runtimeVMID, interfaceId,
				portNumber);

		// build request
		HttpGet hget = SkytapUtils.buildHttpGetRequest(listServiceURL,
				authCredentials);

		String getResponse = "";

		// execute request
		try {
			getResponse = SkytapUtils.executeHttpRequest(hget);
		} catch (SkytapException e) {
			JenkinsLogger.error(e.getMessage());
			return false;
		}

		try {
			SkytapUtils.checkResponseForErrors(getResponse);
		} catch (SkytapException ex) {
			JenkinsLogger
					.error("Request returned an error: " + ex.getMessage());
			JenkinsLogger.error("Failing build step.");
			return false;
		}

		JenkinsLogger.log(getResponse);

		// get ip and port from JSON response
		String externalIp = SkytapUtils.getValueFromJsonResponseBody(
				getResponse, "external_ip");
		String externalPort = SkytapUtils.getValueFromJsonResponseBody(
				getResponse, "external_port");

		String serviceOutputString = externalIp + ":" + externalPort;

		String expPublishedServiceFile = SkytapUtils.expandEnvVars(
				build, publishedServiceFile);
		expPublishedServiceFile = SkytapUtils.convertFileNameToFullPath(
				build, expPublishedServiceFile);
		JenkinsLogger.log("Outputting service output string: "
				+ serviceOutputString + " to file: " + expPublishedServiceFile);

		try {

			// output to the file system
			File file = new File(expPublishedServiceFile);
			Writer output = null;
			output = new BufferedWriter(new FileWriter(file));
			output.write(serviceOutputString);
			output.close();

		} catch (IOException e) {

			JenkinsLogger.error("Skytap Plugin failed to save url to file: "
					+ expPublishedServiceFile);
			return false;
		}

		return true;
	}

	private String buildListPublishedServiceURL(String vid, String intId, int port) {

		JenkinsLogger.log("Building request url ...");

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append(runtimeConfigurationID);

		sb.append("/vms/");
		sb.append(vid);

		sb.append("/interfaces/");
		sb.append(intId);
		sb.append("/services/");
		sb.append(port);

		return sb.toString();

	}

	/**
	 * Takes the json array returned and searches for the interface element
	 * matching the network name provided by the user.
	 * 
	 * Returns interface id matching the network name
	 * 
	 * @param httpResponse
	 * @return
	 * @throws SkytapException
	 */
	private String getInterfaceId(String httpResponse, String netName)
			throws SkytapException {

		// parse the response, first get the array of interfaces
		JsonParser parser = new JsonParser();
		JsonElement je = parser.parse(httpResponse);
		JsonObject vmObject = je.getAsJsonObject();
		JsonArray interfaceArray = (JsonArray) vmObject.get("interfaces");

			JenkinsLogger
					.log("Searching environment's interfaces for interface with network name: "
							+ netName);

			Iterator itr = interfaceArray.iterator();

			while (itr.hasNext()) {
				JsonElement interfaceElement = (JsonElement) itr.next();

				String interfaceNetworkName = "";

				if (interfaceElement.getAsJsonObject().get("network_name") != null) {
					interfaceNetworkName = interfaceElement.getAsJsonObject()
							.get("network_name").getAsString();

					JenkinsLogger.log("Network Name: " + interfaceNetworkName);

					if (interfaceNetworkName.equals(netName)) {
						String interfaceId = interfaceElement.getAsJsonObject()
								.get("id").getAsString();

						JenkinsLogger.log("Network Name Matched.");
						JenkinsLogger.log("Interface ID: " + interfaceId);
						return interfaceId;
					}

				}
			}

		// if no interface id was returned, throw an exception that will fail
		// the build step
		throw new SkytapException(
				"No interface was found matching network name: " + netName);

	}

	private String buildGetInterfacesURL(String cid, String vid) {

		JenkinsLogger.log("Building request url ...");

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append(cid);

			sb.append("/vms/");
			sb.append(vid);

		JenkinsLogger.log("Request URL: " + sb.toString());
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

		// make sure network name, port number and published service file were
		// provided
		if (this.networkName.equals("") || this.portNumber == 0
				|| this.publishedServiceFile.equals("")) {
			JenkinsLogger
					.error("One or more arguments were omitted. Please provide all of the following: network name, port number and published service save file.");
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

	public String getNetworkName() {
		return networkName;
	}

	public int getPortNumber() {
		return portNumber;
	}

	public String getPublishedServiceFile() {
		return publishedServiceFile;
	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			ListVMPublishedServiceStep.class, "List VM Published Service");

}
