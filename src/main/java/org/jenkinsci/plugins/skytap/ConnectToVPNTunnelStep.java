package org.jenkinsci.plugins.skytap;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;

import hudson.Extension;
import hudson.model.AbstractBuild;

import org.apache.commons.httpclient.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.BasicHttpEntity;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapAction;
import org.jenkinsci.plugins.skytap.SkytapBuilder.SkytapActionDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

public class ConnectToVPNTunnelStep extends SkytapAction {

	private final String configurationID;
	private final String configurationFile;
	private final String configurationNetworkName;
	private final String vpnID;
	
	// these vars will be initialized when the step is run

	@XStreamOmitField
	private SkytapGlobalVariables globalVars;
	
	@XStreamOmitField
	private String authCredentials;

	// the runtime config id will be set one of two ways:
	// either the user has provided just a config id, so we use it,
	// or the user provided a file, in which case we read the file and extract
	// the
	// id from the json element
	@XStreamOmitField
	private String runtimeConfigurationID;

	@DataBoundConstructor
	public ConnectToVPNTunnelStep(String configurationID,
			String configurationFile, String configurationNetworkName, String vpnID) {
		super("Connect to VPN Tunnel");

		this.configurationID = configurationID;
		this.configurationFile = configurationFile;
		this.configurationNetworkName = configurationNetworkName;
		this.vpnID = vpnID;

	}

	public Boolean executeStep(AbstractBuild build,
			SkytapGlobalVariables globalVars) {

		JenkinsLogger.defaultLogMessage("----------------------------------------");
		JenkinsLogger.defaultLogMessage("Connecting to VPN Tunnel");
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		
		if(preFlightSanityChecks()==false){
			return false;
		}
		
		this.globalVars = globalVars;
		this.authCredentials = SkytapUtils.getAuthCredentials(build);

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
		
		// get the network id from the provided network name
		JenkinsLogger.log("Getting the ID for network: " + configurationNetworkName);
		String runtimeNetworkID = "";
		
		try {
			runtimeNetworkID = SkytapUtils.getNetworkIdFromName(runtimeConfigurationID, configurationNetworkName, this.authCredentials);
		} catch (SkytapException e1) {
			JenkinsLogger.error(e1.getError());
			return false;
		}
		
		if(runtimeNetworkID.equals("")){ 
			JenkinsLogger.error("Failed to obtain network ID.");
			return false; 
		}
		
		JenkinsLogger.log("Configuration ID: " + runtimeConfigurationID);
		JenkinsLogger.log("Configuration File: " + expConfigurationFile);
		JenkinsLogger.log("Network ID: " + runtimeNetworkID);
		JenkinsLogger.log("Network Name: " + this.configurationNetworkName);
		JenkinsLogger.log("VPN ID: " + this.vpnID);

		// attach the VPN to the configuration
		JenkinsLogger.log("Attaching VPN to Configuration ...");
		String attachResponse = this.attachVPNToConfiguration(runtimeConfigurationID, runtimeNetworkID, vpnID);

		JenkinsLogger.log("Attach Response: " + attachResponse);
		
		if(attachResponse.equals("") || attachResponse == null){
			JenkinsLogger.error("Response was null or empty.");
			return false;
		}
		
		// check response for errors
		try {
			SkytapUtils.checkResponseForErrors(attachResponse);
		} catch (SkytapException e) {
			JenkinsLogger.error("Skytap Error: " + e.getError());
			return false;
		}
		
		JenkinsLogger.log("VPN with ID " + vpnID + " successfully attached to Network with ID " + runtimeNetworkID + ".");
		
		// connect the configuration to the VPN
		JenkinsLogger.log("Connecting VPN to Configuration ...");
		String connectResponse = this.connectVPNToConfiguration(runtimeConfigurationID, runtimeNetworkID, vpnID);
		
		JenkinsLogger.log("Connect Response: " + connectResponse);
		
		if(connectResponse.equals("") || connectResponse == null){
			JenkinsLogger.error("Response was null or empty.");
			return false;
		}
		
		// check response for errors
		try {
			SkytapUtils.checkResponseForErrors(connectResponse);
		} catch (SkytapException e) {
			JenkinsLogger.error("Skytap Error: " + e.getError());
			return false;
		}
		
		JenkinsLogger.defaultLogMessage("VPN with ID " + vpnID + " successfully connected to Network with ID " + runtimeNetworkID + ".");
		
		JenkinsLogger.defaultLogMessage("----------------------------------------");
		return true;
	}
	
	private String connectVPNToConfiguration(String confId, String networkId, String vpnId){
		
		// build url
		String reqUrl = this.buildConnectRequestURL(confId, networkId, vpnId);
		
		// create request
		HttpPut hp = SkytapUtils.buildHttpPutRequest(reqUrl, this.authCredentials);
		
		// add content to request - vpn identifier
		BasicHttpEntity he = new BasicHttpEntity();
		he.setContentEncoding("gzip");
		he.setContentType("application/json");
		
		// json string for connected attribute
		String jsonString = "{\"connected\" :true}";
						
		InputStream stream;
		try {
			stream = new ByteArrayInputStream(jsonString.getBytes("UTF-8"));
			Integer len = jsonString.getBytes("UTF-8").length;
			long llen = len.longValue();
			
			he.setContent(stream);
			he.setContentLength(llen);
			
		} catch (UnsupportedEncodingException e) {
			JenkinsLogger.error("Error encoding json string for connected attribute: " + e.getMessage());

		}
		
		hp.setEntity(he);
		String response = "";
		
			try {
				response = SkytapUtils.executeHttpRequest(hp);
			} catch (SkytapException e) {
				JenkinsLogger.error("Skytap Exception: " + e.getMessage());
			}
		
			return response;

	}
	
	private String buildConnectRequestURL(String confId, String networkId, String vpnId){
	
		String req = this.buildRequestURL(confId, networkId);
		StringBuilder sb = new StringBuilder(req);
		sb.append("/");
		sb.append(vpnId);
		sb.append("/");
		
		JenkinsLogger.log(sb.toString());
		return sb.toString();
		
	}
	
	private String buildRequestURL(String confId, String networkId){

		StringBuilder sb = new StringBuilder("https://cloud.skytap.com/");
		sb.append("configurations/");
		sb.append(confId);
		sb.append("/networks/");
		sb.append(networkId);
		sb.append("/vpns");
		
		String requestUrl = sb.toString();
		return requestUrl;
	}
	
	private String attachVPNToConfiguration(String confId, String networkId, String vpnId) {
		
		// build url
		String requestUrl = this.buildRequestURL(confId, networkId);
		
		// create request
		HttpPost hp = SkytapUtils.buildHttpPostRequest(requestUrl, this.authCredentials);
        	
		// add content to request - vpn identifier
		BasicHttpEntity he = new BasicHttpEntity();
		he.setContentEncoding("gzip");
		he.setContentType("application/json");
		
		// json string for vpn id
		String jsonString = "{\"id\":\"" + vpnId + "\"}";
		
		InputStream stream;
		try {
			stream = new ByteArrayInputStream(jsonString.getBytes("UTF-8"));
			Integer len = jsonString.getBytes("UTF-8").length;
			long llen = len.longValue();
			
			he.setContent(stream);
			he.setContentLength(llen);
			
		} catch (UnsupportedEncodingException e) {
			JenkinsLogger.error("Error encoding json string for vpn id: " + e.getMessage());

		}

		hp.setEntity(he);
		
		JenkinsLogger.log("HTTP POST request: " + hp.toString());
		
		// execute request
		String httpRespBody = "";
		
		try {
			httpRespBody = SkytapUtils.executeHttpRequest(hp);
		} catch (SkytapException e) {
			JenkinsLogger.error("Skytap Exception: " + e.getMessage());
		}

		// return response
		return httpRespBody;
		
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
		
		// if either VPN ID or network name are blank, fail the build
		if(this.vpnID.equals("") ){
			JenkinsLogger.error("No value was provided for the VPN ID. Please provide a valid Skytap VPN ID.");
			return false;
		}
		
		if(this.configurationNetworkName.equals("")){
			JenkinsLogger.error("No value was provided for the network name. Please provide a valid Skytap Network name.");
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

	public String getConfigurationNetworkName() {
		return configurationNetworkName;
	}

	public String getVpnID() {
		return vpnID;
	}

	@Extension
	public static final SkytapActionDescriptor D = new SkytapActionDescriptor(
			ConnectToVPNTunnelStep.class, "Connect to VPN Tunnel");
	
}
