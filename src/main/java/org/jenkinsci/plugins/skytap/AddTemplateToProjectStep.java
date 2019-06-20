package org.jenkinsci.plugins.skytap;

import java.io.FileNotFoundException;

import hudson.Extension;
import hudson.model.AbstractBuild;

import org.apache.http.client.methods.HttpPost;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapAction;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapActionDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import com.thoughtworks.xstream.annotations.XStreamOmitField;

public class AddTemplateToProjectStep extends SkytapAction {

	private final String templateID;
	private final String templateFile;

	private final String projectID;
	private final String projectName;

	// these vars will be initialized when the step is run

	@XStreamOmitField
	private SkytapGlobalVariables globalVars;

	// the runtime template id will be set one of two ways:
	// either the user has provided just a template id, so we use it,
	// or the user provided a file, in which case we read the file and extract
	// the id from the json element
	
	@XStreamOmitField
	private String runtimeTemplateID;
	
	@XStreamOmitField
	private String runtimeProjectID;
	
	@XStreamOmitField
	private String authCredentials;
	
	@DataBoundConstructor
	public AddTemplateToProjectStep(String templateID, String templateFile, 
			String projectID, String projectName) {
		super("Add Template to Project");
		
		this.templateID = templateID;
		this.templateFile = templateFile;
		this.projectID = projectID;
		this.projectName = projectName;
		
	}
	
	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {
		
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Adding Template to Project Step");
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		
		if(preFlightSanityChecks()==false){
			return false;
		}
		
		this.globalVars = globalVars;
		authCredentials = SkytapUtils.getAuthCredentials(build);
		
	
		// reset step parameters with env vars resolved at runtime
		String expTemplateFile = SkytapUtils.expandEnvVars(build, templateFile);
		
		// if no path was provided (just filename), convert to jenkins workspace path
		if (!expTemplateFile.isEmpty()) {
			expTemplateFile = SkytapUtils.convertFileNameToFullPath(build,
					expTemplateFile);
		}
		
		// get runtime template id
		try {
			runtimeTemplateID = SkytapUtils.getRuntimeId(build, templateID, expTemplateFile);
		}catch (FileNotFoundException e){
			JenkinsLogger.error("Error obtaining runtime id: " + e.getMessage());
			return false;
		}
		
		// was a project id provided? then just use it
		if(!getProjectID().equals("")){ 
			runtimeProjectID = getProjectID(); 
		}
			
		// otherwise retrieve the id from name provided
		if(!getProjectName().equals("")){
			runtimeProjectID = SkytapUtils.getProjectID(projectName, authCredentials);
		}
		
		if(runtimeProjectID.equals("")){
			JenkinsLogger.error("Please provide a valid project name or ID.");
			return false;
		}
		
		JenkinsLogger.log("Template ID: " + runtimeTemplateID);
		JenkinsLogger.log("Template File: " + templateFile);
		JenkinsLogger.log("Project ID: " + runtimeProjectID);
		JenkinsLogger.log("Project Name: " + projectName);
		
		// build request URL
		String reqUrl = buildAddTemplateRequestURL(runtimeProjectID, runtimeTemplateID);
		
		// create request
		HttpPost hp = SkytapUtils.buildHttpPostRequest(reqUrl, authCredentials);
		
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
		
		JenkinsLogger.log("Template " + runtimeTemplateID + " was successfully added to project " + runtimeProjectID);
		return true;
	}
	
	private String buildAddTemplateRequestURL(String projId, String templateId){
		
		JenkinsLogger.log("Building request url ...");

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com");
		
		sb.append("/projects/");
		sb.append(projId);
		sb.append("/templates/");
		sb.append(templateId);

		JenkinsLogger.log("Request URL: " + sb.toString());
		return sb.toString();
		
		//GET https://.../projects/<id>/configuration
		//POST <project-ref>/templates/<id>
	}
	
	
	private Boolean preFlightSanityChecks(){
	
		// check whether user entered both values for template id/template file
		if (!this.templateID.equals("") && !this.templateFile.equals("")){
			JenkinsLogger.error("Values were provided for both template id and file. Please provide just one or the other.");
			return false;
		}
		
		// check whether user provided neither template id nor file
		if (!this.templateID.equals("") && !this.templateFile.equals("")){
			JenkinsLogger.error("No value was provided for template ID or file. Please provide either a valid Skytap template ID, or a valid template file.");
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
			AddTemplateToProjectStep.class, "Add Template to Project");
	
	public String getTemplateID() {
		return templateID;
	}

	public String getTemplateFile() {
		return templateFile;
	}

	public String getProjectID() {
		return projectID;
	}

	public String getProjectName() {
		return projectName;
	}

	public String getRuntimeTemplateID() {
		return runtimeTemplateID;
	}

	public void setRuntimeTemplateID(String runtimeTemplateID) {
		this.runtimeTemplateID = runtimeTemplateID;
	}
	


}
