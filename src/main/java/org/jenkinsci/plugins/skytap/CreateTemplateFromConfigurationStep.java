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
import java.util.Iterator;

import hudson.Extension;
import hudson.model.AbstractBuild;

import org.apache.commons.io.FilenameUtils;
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

public class CreateTemplateFromConfigurationStep extends SkytapAction {

	private final String configurationID;
	private final String configurationFile;
	private final String templateName;
	private final String templateDescription;
	private final String templateSaveFilename;
	
	// these vars will be initialized when the step is run
	@XStreamOmitField
	private SkytapGlobalVariables globalVars;
	
	@XStreamOmitField
	private String authCredentials;
	
	// the runtime config id will be set one of two ways:
	// either the user has provided just a config id, so we use it,
	// or the user provided a file, in which case we read the file and extract
	// the id from the json element
	@XStreamOmitField
	private String runtimeConfigurationID;
	
	@XStreamOmitField
	private String runtimeTemplateID;
	
	@DataBoundConstructor
	public CreateTemplateFromConfigurationStep(String configurationID, String configurationFile,
			String templateName, String templateDescription,
			String templateSaveFilename) {

		super("Create Template from Configuration");
		this.configurationFile = configurationFile;
		this.configurationID = configurationID;
		this.templateDescription = templateDescription;
		this.templateName = templateName;
		this.templateSaveFilename = templateSaveFilename;
		
	}
	
	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Creating Template from Configuration");
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		
		if(preFlightSanityChecks()==false){
			return false;
		}
		
		this.globalVars = globalVars;
		this.authCredentials = SkytapUtils.getAuthCredentials(build);
		
		// reset step parameters with env vars resolved at runtime
		String expConfigurationFile = SkytapUtils.expandEnvVars(build,
				configurationFile);
		
		// if user has provided just a filename with no path, default to
		// place it in their Jenkins workspace

		if (!expConfigurationFile.equals("")) {
			expConfigurationFile = SkytapUtils.convertFileNameToFullPath(build,
					expConfigurationFile);
		}
		String expTemplateFile = SkytapUtils.expandEnvVars(build, templateSaveFilename);
		String expTemplateName = SkytapUtils.expandEnvVars(build, templateName);
		String expTemplateDescription = SkytapUtils.expandEnvVars(build, templateDescription);
		
		try {
			this.runtimeConfigurationID = SkytapUtils.getRuntimeId(this.configurationID, expConfigurationFile);
		} catch (FileNotFoundException e2) {
			JenkinsLogger.error("Error obtaining configuration id: " + e2.getMessage());
			return false;
		}
		
		JenkinsLogger.log("Runtime Config ID: " + this.runtimeConfigurationID);

		try {
			// create the template
			sendTemplateCreationRequest(runtimeConfigurationID);
		} catch (SkytapException e1) {
			JenkinsLogger.error("Skytap Exception: " + e1.getMessage());
			return false;
		}

		// make sure we have the template id, if we don't something went wrong
		if(runtimeTemplateID.equals("") || runtimeTemplateID == null){
			JenkinsLogger.error("Template creation failed.");
			return false;
		}
		
		// update the template with name and description
		String httpRespBody;
		try {
			httpRespBody = sendTemplateUpdateRequest(this.runtimeTemplateID, expTemplateName, expTemplateDescription);
		} catch (SkytapException e1) {
			JenkinsLogger.error("Skytap Exception: " + e1.getMessage());
			return false;
		}
	
		// save to template file path
		JsonParser parser = new JsonParser();
		JsonElement je = parser.parse(httpRespBody);
		JsonObject jo = je.getAsJsonObject();
		jo = je.getAsJsonObject();

		Writer output = null;
		
		// if user has provided just a filename with no path, default to
		// place it in their Jenkins workspace
		expTemplateFile = SkytapUtils.convertFileNameToFullPath(build, expTemplateFile);
		
		// output to the file system
		File file = new File(expTemplateFile);
		
		try {

			output = new BufferedWriter(new FileWriter(file));
			output.write(httpRespBody);
			output.close();
		
		} catch (IOException e) {

			JenkinsLogger
					.error("Skytap Plugin failed to save template to file: "
							+ expTemplateFile);
			return false;
		}
		
		JenkinsLogger
				.defaultLogMessage("Template " + expTemplateName + " successfully created and saved to file: "
						+ expTemplateFile);
		
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		return true;
		
	}
	
	private void sendTemplateCreationRequest(String configId) throws SkytapException {
		
		JenkinsLogger.log("Sending Template Creation Request for Configuration " + configId);
		
		// build request for initial template creation
		String templateCreateRequestUrl = buildRequestCreationURL(configId);
		
		// create request for Skytap API
		HttpPost hp = SkytapUtils.buildHttpPostRequest(templateCreateRequestUrl,
				this.authCredentials);

		// execute request
		String httpRespBody = SkytapUtils.executeHttpRequest(hp);
		
		SkytapUtils.checkResponseForErrors(httpRespBody);
		
		// get id and name of newly created template
		JsonParser parser = new JsonParser();
		JsonElement je = parser.parse(httpRespBody);
		this.runtimeTemplateID = je.getAsJsonObject().get("id").getAsString();
		String newName = je.getAsJsonObject().get("name").getAsString();

		JenkinsLogger.log("New Template created with id: " + this.runtimeTemplateID + " - name: " + newName);
						
	}
	
    private String sendTemplateUpdateRequest(String templateId, String name, String desc) throws SkytapException {
    	
    	JenkinsLogger.log("Sending Template Update Request for Template " + templateId);
    	JenkinsLogger.log("Updating Template with name: " + name + " and description: " + desc);
    		
    	String reqUrl = buildRequestUpdateURL(templateId, name, desc);
    	
    	HttpPut hp = SkytapUtils.buildHttpPutRequest(reqUrl, this.authCredentials);
    	
    	String httpResponse = SkytapUtils.executeHttpRequest(hp);

    	return httpResponse;
    }
    
    @SuppressWarnings("deprecation")
	private String buildRequestUpdateURL(String templateId, String name, String desc){
		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		
		//TODO: sanitize url parameters (spaces, special characters etc.)
		sb.append("templates/");
		sb.append(templateId);
		sb.append("?name=");
		sb.append(URLEncoder.encode(name));
		sb.append("&description=");
		sb.append(URLEncoder.encode(desc));
		
		return sb.toString();

		//https://cloud.skytap.com/templates/298117?name=sometesttemplate&description=whateverdude
    }
    
	private String buildRequestCreationURL(String configId){
		
		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("templates?configuration_id=");
		sb.append(configId);

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
		
		if(this.templateName.equals("") || this.templateSaveFilename.equals("")){
			JenkinsLogger.error("Please provide a template name and a valid filename to save to.");
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

	public String getTemplateName() {
		return templateName;
	}

	public String getTemplateDescription() {
		return templateDescription;
	}

	public String getTemplateSaveFilename() {
		return templateSaveFilename;
	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			CreateTemplateFromConfigurationStep.class, "Create Template from Configuration");
	
}
