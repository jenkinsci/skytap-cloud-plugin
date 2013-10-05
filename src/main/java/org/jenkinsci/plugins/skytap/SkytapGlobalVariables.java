package org.jenkinsci.plugins.skytap;

public class SkytapGlobalVariables {

	private final String encodedCredentials;
	private final Boolean loggingEnabled;
	
	public SkytapGlobalVariables(String encodedCredentials, Boolean loggingEnabled) {
		this.encodedCredentials = encodedCredentials;
		this.loggingEnabled = loggingEnabled;
	}

	public String getEncodedCredentials() {
		return encodedCredentials;
	}

	public Boolean isLoggingEnabled() {
		return loggingEnabled;
	}
	
}
