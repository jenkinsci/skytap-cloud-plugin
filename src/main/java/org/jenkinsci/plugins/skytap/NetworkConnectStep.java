package org.jenkinsci.plugins.skytap;

import java.io.FileNotFoundException;

import hudson.Extension;
import hudson.model.AbstractBuild;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapAction;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapActionDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import com.thoughtworks.xstream.annotations.XStreamOmitField;

public class NetworkConnectStep extends SkytapAction {

	private final String sourceNetworkConfigurationID;
	private final String targetNetworkConfigurationID;

	private final String sourceNetworkConfigurationFile;
	private final String targetNetworkConfigurationFile;

	private final String sourceNetworkName;
	private final String targetNetworkName;

	// these vars will be initialized when the step is run
	@XStreamOmitField
	private SkytapGlobalVariables globalVars;

	// runtime source network config ID
	@XStreamOmitField
	private String runtimeSourceNetworkConfigurationID;

	// runtime target network config ID
	@XStreamOmitField
	private String runtimeTargetNetworkConfigurationID;

	@DataBoundConstructor
	public NetworkConnectStep(String sourceNetworkConfigurationID,
			String targetNetworkConfigurationID,
			String sourceNetworkConfigurationFile,
			String targetNetworkConfigurationFile, String sourceNetworkName,
			String targetNetworkName) {
		super("Connect to Network in another Configuration (ICNR)");

		this.sourceNetworkConfigurationID = sourceNetworkConfigurationID;
		this.targetNetworkConfigurationID = targetNetworkConfigurationID;
		this.sourceNetworkConfigurationFile = sourceNetworkConfigurationFile;
		this.targetNetworkConfigurationFile = targetNetworkConfigurationFile;
		this.sourceNetworkName = sourceNetworkName;
		this.targetNetworkName = targetNetworkName;

	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Connecting to Network in another Configuration");
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		
		if(preFlightSanityChecks()==false){
			return false;
		}
		
		this.globalVars = globalVars;

		// reset step parameters with env vars resolved at runtime
		String expSourceNetworkConfigurationFile = SkytapUtils.expandEnvVars(
				build, sourceNetworkConfigurationFile);

		String expTargetNetworkConfigurationFile = SkytapUtils.expandEnvVars(
				build, targetNetworkConfigurationFile);

		// get runtime config ids for source and target network configurations
		try {

			runtimeSourceNetworkConfigurationID = SkytapUtils.getRuntimeId(
					sourceNetworkConfigurationID,
					expSourceNetworkConfigurationFile);
			runtimeTargetNetworkConfigurationID = SkytapUtils.getRuntimeId(
					targetNetworkConfigurationID,
					expTargetNetworkConfigurationFile);

		} catch (FileNotFoundException e) {
			JenkinsLogger
					.error("Error obtaining runtime id: " + e.getMessage());
			return false;
		}

		// get network ids for source and target network names
		String runtimeSourceNetworkID = "";
		String runtimeTargetNetworkID = "";
		
		try {
			runtimeSourceNetworkID = SkytapUtils.getNetworkIdFromName(
					runtimeSourceNetworkConfigurationID, sourceNetworkName,
					this.globalVars.getEncodedCredentials());
		} catch (SkytapException e1) {
			JenkinsLogger.error(e1.getError());
			return false;
		}
		
		try {
			runtimeTargetNetworkID = SkytapUtils.getNetworkIdFromName(
					runtimeTargetNetworkConfigurationID, targetNetworkName,
					this.globalVars.getEncodedCredentials());
		} catch (SkytapException e1) {
			JenkinsLogger.error(e1.getError());
			return false;
		}

		// if one or both network ids could not be obtained, error out and fail build
		if(runtimeSourceNetworkID.equals("") || runtimeTargetNetworkID.equals("")){
			JenkinsLogger.error("Unable to obtain network identifiers.");
			return false;
		}
		
		// connect the two networks
		try {
			sendNetConnectRequest(runtimeSourceNetworkID, runtimeTargetNetworkID);
		} catch (SkytapException e) {
			JenkinsLogger.error("Skytap Error: " + e.getMessage());
			return false;
		}

		JenkinsLogger.defaultLogMessage("Networks " + this.sourceNetworkName + " and " + this.targetNetworkName +
				" have been connected successfully.");
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		return true;
	}

	private void sendNetConnectRequest(String sourceNetId, String targetNetId) throws SkytapException {

		JenkinsLogger.log("Sending network connection request for source: "
				+ sourceNetId + " to target: " + targetNetId);

		// build put request url
		String requestURL = buildRequestURL(sourceNetId, targetNetId);

		// create request for Skytap API
		HttpPost hp = SkytapUtils.buildHttpPostRequest(requestURL,
				globalVars.getEncodedCredentials());

		// execute request
		String httpRespBody = SkytapUtils.executeHttpRequest(hp);

		// check for empty response
		if (httpRespBody.equals("")) {
			throw new SkytapException("Request Failed. No HTTP response was returned.");
		}

		// check response for skytap errors
		try {
			SkytapUtils.checkResponseForErrors(httpRespBody);
		} catch (SkytapException e) {
			throw e;
		}

	}

	private String buildRequestURL(String sid, String tid) {

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("tunnels?");
		sb.append("source_network_id=");
		sb.append(sid);
		sb.append("&target_network_id=");
		sb.append(tid);

		return sb.toString();
	}
	
	/**
	 * This method is a final check to ensure that user inputs are legitimate.
	 * Any situation where the user has entered both inputs in an either/or scenario 
	 * will fail the build. If the user has left both blank where we need one, it will
	 * also fail.
	 * 
	 * @return Boolean sanityCheckPassed
	 */
	private Boolean preFlightSanityChecks(){

		// check whether user entered both values for conf id/conf file
		if(!this.sourceNetworkConfigurationFile.equals("") && !this.sourceNetworkConfigurationID.equals("")){
			JenkinsLogger.error("Values were provided for both source configuration ID and file. Please provide just one or the other.");
			return false;
		}
		
		if(!this.targetNetworkConfigurationFile.equals("") && !this.targetNetworkConfigurationID.equals("")){
			JenkinsLogger.error("Values were provided for both target configuration ID and file. Please provide just one or the other.");
			return false;
		}
		
		// check whether values missing
		if(this.sourceNetworkConfigurationFile.equals("") && this.sourceNetworkConfigurationID.equals("")){
			JenkinsLogger.error("No value was provided for configuration ID or file. Please provide either a valid Skytap configuration ID, or a valid configuration file.");
			return false;
		}
		
		if(this.targetNetworkConfigurationFile.equals("") && this.targetNetworkConfigurationID.equals("")){
			JenkinsLogger.error("No value was provided for configuration ID or file. Please provide either a valid Skytap configuration ID, or a valid configuration file.");
			return false;
		}
		
		if(this.targetNetworkName.equals("")){
			JenkinsLogger.error("No value was provided for target network name. Please provide a valid target network name.");
			return false;
		}
		
		if(this.sourceNetworkName.equals("")){
			JenkinsLogger.error("No value was provided for source network name. Please provide a valid source network name.");
			return false;
		}
		
		return true;
	}

	public SkytapGlobalVariables getGlobalVars() {
		return globalVars;
	}

	public String getSourceNetworkConfigurationID() {
		return sourceNetworkConfigurationID;
	}

	public String getTargetNetworkConfigurationID() {
		return targetNetworkConfigurationID;
	}

	public String getSourceNetworkConfigurationFile() {
		return sourceNetworkConfigurationFile;
	}

	public String getTargetNetworkConfigurationFile() {
		return targetNetworkConfigurationFile;
	}

	public String getSourceNetworkName() {
		return sourceNetworkName;
	}

	public String getTargetNetworkName() {
		return targetNetworkName;
	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			NetworkConnectStep.class,
			"Connect to Network in another Configuration (ICNR)");

}
