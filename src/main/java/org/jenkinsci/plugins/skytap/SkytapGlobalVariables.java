package org.jenkinsci.plugins.skytap;

public class SkytapGlobalVariables {

	private final Boolean loggingEnabled;
	
	public SkytapGlobalVariables(Boolean loggingEnabled) {
		
		// default setting is that logging IS enabled.
		// if the user hasn't saved the global settings page, it will be null,
		// so if it is null set to default to true.
		// if it is false, it should be false
		
		if(loggingEnabled == null){
			this.loggingEnabled = true;
		}else{
			this.loggingEnabled = loggingEnabled;
		}
		
	}

	public Boolean isLoggingEnabled() {
		return loggingEnabled;
	}
	
}
