package org.jenkinsci.plugins.skytap;

import java.io.FileNotFoundException;

import hudson.Extension;
import hudson.model.AbstractBuild;

import org.apache.http.client.methods.HttpDelete;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapAction;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapActionDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.gson.JsonObject;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

public class DeleteConfigurationStep extends SkytapAction {

	private final String configurationID;
	private final String configurationFile;
	
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
	
	@DataBoundConstructor
	public DeleteConfigurationStep(String configurationID,
			String configurationFile) {
		super("Delete Configuration");

		this.configurationID = configurationID;
		this.configurationFile = configurationFile;
	}
	
	public Boolean executeStep(AbstractBuild build, SkytapGlobalVariables globalVars) {

		JenkinsLogger.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Delete Configuration");
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		
		if(preFlightSanityChecks()==false){
			return false;
		}
		
		this.globalVars = globalVars;

		// reset step parameters with env vars resolved at runtime
		String expConfigurationFile = SkytapUtils.expandEnvVars(build,
				configurationFile);

		// get runtime config id
		try {
			this.runtimeConfigurationID = SkytapUtils.getRuntimeId(configurationID, expConfigurationFile);
		} catch (FileNotFoundException e) {
			JenkinsLogger.error("Error retrieving configuration id: " + e.getMessage());
			return false;
		}
		
		JenkinsLogger.log("Sending delete request for configuration id "
				+ this.runtimeConfigurationID);
		
		// build delete config url
		String requestURL = buildRequestURL(this.runtimeConfigurationID);
		
		// create request for Skytap API
		HttpDelete hd = SkytapUtils.buildHttpDeleteRequest(requestURL,
				globalVars.getEncodedCredentials());

		// execute request
		String httpRespBody;
		try {
			httpRespBody = SkytapUtils.executeHttpRequest(hd);
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
		} catch (IllegalStateException ex){
			// if there are no errors in the response body this exception will result
		}
		
		JenkinsLogger.log("");
		JenkinsLogger.log(httpRespBody);
		JenkinsLogger.log("");
		
		JenkinsLogger.defaultLogMessage("Configuration " + runtimeConfigurationID + " was successfully deleted.");
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		return true;

	}
	
	public String buildRequestURL(String configId) {

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append(configId);

//https://cloud.skytap.com/configurations/1154948
		
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
		if(!this.configurationID.equals("") && !this.configurationFile.equals("")){
			JenkinsLogger.error("Values were provided for both configuration ID and file. Please provide just one or the other.");
			return false;
		}
		
		// check whether we have neither conf id or file
		if(this.configurationFile.equals("") && this.configurationID.equals("")){
			JenkinsLogger.error("No value was provided for configuration ID or file. Please provide either a valid Skytap configuration ID, or a valid configuration file.");
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
	
	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			DeleteConfigurationStep.class, "Delete Configuration");

}
