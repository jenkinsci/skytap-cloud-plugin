package org.jenkinsci.plugins.skytap;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Iterator;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.servlet.ServletException;

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.util.FormValidation;

import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapAction;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapActionDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

public class AddConfigurationToProjectStep extends SkytapAction {

	private final String configurationID;
	private final String configurationFile;

	private final String projectID;
	private final String projectName;

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
	public AddConfigurationToProjectStep(String configurationID,
			String configurationFile, String projectID, String projectName) {
		super("Add Configuration to Project");
		this.configurationID = configurationID;
		this.configurationFile = configurationFile;
		this.projectID = projectID;
		this.projectName = projectName;
	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {
		
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Adding Configuration to Project Step");
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
			runtimeConfigurationID = SkytapUtils.getRuntimeId(configurationID, expConfigurationFile);
		} catch (FileNotFoundException e) {
			JenkinsLogger.error("Error obtaining runtime id: " + e.getMessage());
			return false;
		}
		
		// get runtime project ID
		String runtimeProjectID = "";
		
		// was a project id provided? then just use it
		if(!getProjectID().equals("")){ 
			runtimeProjectID = getProjectID(); 
		}
			
		// otherwise retrieve the id from name provided
		if(!getProjectName().equals("")){
			runtimeProjectID = getProjectID(projectName);
		}
		
		if(runtimeProjectID.equals("")){
			JenkinsLogger.error("Please provide a valid project name or ID.");
			return false;
		}

		JenkinsLogger.log("Configuration ID: " + runtimeConfigurationID);
		JenkinsLogger.log("Configuration File: " + this.configurationFile);
		JenkinsLogger.log("Project ID: " + runtimeProjectID);
		JenkinsLogger.log("Project Name: " + this.projectName);

		// build request url
		String requestUrl = buildRequestURL(runtimeProjectID, runtimeConfigurationID);

		// create request for skytap API
		HttpPost hp = SkytapUtils.buildHttpPostRequest(requestUrl,
				globalVars.getEncodedCredentials());

		// execute request
		String httpRespBody;
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
		
		JenkinsLogger.log("");
		JenkinsLogger.log(httpRespBody);
		JenkinsLogger.log("");
		
		JenkinsLogger.defaultLogMessage("Configuration " + runtimeConfigurationID + " was successfully added to project " + runtimeProjectID);
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		
		return true;
	}

	/**
	 * Makes call to skytap to retrieve the id
	 * of a named project.
	 * 
	 * @param projName
	 * @return projId
	 */
	private String getProjectID(String projName) {
		
		// build url
		StringBuilder sb = new StringBuilder("https://cloud.skytap.com");
		sb.append("/projects");
		
		// create http get
		HttpGet hg = SkytapUtils.buildHttpGetRequest(sb.toString(), globalVars.getEncodedCredentials());
		
		// execute request
		String response;
		try {
			response = SkytapUtils.executeHttpRequest(hg);
		} catch (SkytapException e) {
			JenkinsLogger.error("Skytap Exception: " + e.getMessage());
			return "";
		}
		
		// response string will be a json array of all projects		
		JsonParser parser = new JsonParser();
		JsonElement je = parser.parse(response);
		JsonArray ja = je.getAsJsonArray();

	     Iterator itr = ja.iterator();
	      while(itr.hasNext()) {
	         JsonElement projElement = (JsonElement) itr.next();
	         String projElementName = projElement.getAsJsonObject().get("name").getAsString();
	         
	         if(projElementName.equals(projName)){
	        	 String projElementId = projElement.getAsJsonObject().get("id").getAsString();
	        	 return projElementId;
	         }

	      }
		
	    JenkinsLogger.error("No project matching name \"" + projName + "\"" + " was found.");
		return "";
	}
	
	private String buildRequestURL(String projId, String configId) {

		JenkinsLogger.log("Building request url ...");

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com");
		
		sb.append("/projects/");
		sb.append(projId);
		sb.append("/configurations/");
		sb.append(configId);

		JenkinsLogger.log("Request URL: " + sb.toString());
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
		
		// check whether user entered both values for project id/project name
		if(!this.projectID.equals("") && !this.projectName.equals("")){
			JenkinsLogger.error("Values were provided for both project ID and file. Please provide just one or the other.");
			return false;
		}
		
		// check whether we have neither project id nor name
		if(this.projectID.equals("") && this.projectName.equals("")){
			JenkinsLogger.error("No value was provided for project ID or name. Please provide either the name or ID of a valid Skytap project.");
			return false;
		}
		
		return true;
	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			AddConfigurationToProjectStep.class, "Add Configuration to Project");
	
	public String getConfigurationID() {
		return configurationID;
	}

	public String getConfigurationFile() {
		return configurationFile;
	}

	public String getProjectID() {
		return projectID;
	}

	public String getProjectName() {
		return projectName;
	}

}
