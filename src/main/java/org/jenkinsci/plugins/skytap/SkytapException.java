package org.jenkinsci.plugins.skytap;

/**
 * Custom exception class to handle errors/exceptions 
 * returned from Skytap
 * 
 * @author ptoma
 *
 */
public class SkytapException extends Exception {

	  private String skytapError;

	  public SkytapException()
	  {
	    super();             // call superclass constructor
	    skytapError = "Unknown Error";
	  }

	  public SkytapException(String err)
	  {
	    super(err);     // call super class constructor
	    skytapError = err;  // save message
	  }

	  public String getError()
	  {
	    return skytapError;
	  }

}
