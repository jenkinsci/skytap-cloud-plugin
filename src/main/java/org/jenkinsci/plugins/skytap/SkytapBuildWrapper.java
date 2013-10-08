package org.jenkinsci.plugins.skytap;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;

import java.io.IOException;
import java.util.Map;

import org.kohsuke.stapler.DataBoundConstructor;

public class SkytapBuildWrapper extends BuildWrapper {

	@Extension
	public static class DescriptorImpl extends BuildWrapperDescriptor {
	
		public DescriptorImpl() {
			load();
		}
		
		@Override
		public String getDisplayName() {
			return "Skytap Cloud Authentication Credentials";
		}

		@Override
		public boolean isApplicable(final AbstractProject<?, ?> item) {
			return true;
		}

	}

	private final String userId;
	private final String authKey;

	@DataBoundConstructor
	public SkytapBuildWrapper(final String userId, final String authKey) {
		super();
		this.userId = userId;
		this.authKey = authKey;
	}
	
	public String getUserId() {
		return userId;
	}

	public String getAuthKey() {
		return authKey;
	}
	
	@Override
	  public BuildWrapper.Environment setUp(
	      @SuppressWarnings("rawtypes") final AbstractBuild build,
	      final Launcher launcher, final BuildListener listener)
	      throws IOException, InterruptedException
	  {
	   
	       EnvVars env = build.getEnvironment(listener);
	       env.put("userId", userId);
	       env.put("authKey", authKey);
	       
	    return new Environment()
	    {
	      /* empty implementation */
	    };
	  }
	
	@Override
	public void makeBuildVariables(AbstractBuild build,
			Map<String, String> variables) {

		variables.put("userId", userId);
		variables.put("authKey", authKey);

	}
	
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

}
