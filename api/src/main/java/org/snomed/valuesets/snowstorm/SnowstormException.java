package org.snomed.valuesets.snowstorm;

public class SnowstormException extends RuntimeException {

	private final boolean causedByRequest;

	public SnowstormException(String message, boolean causedByRequest, Throwable cause) {
		super(message, cause);
		this.causedByRequest = causedByRequest;
	}

	/** True when Snowstorm rejected what was asked (for example invalid ECL), false when Snowstorm itself failed. */
	public boolean isCausedByRequest() {
		return causedByRequest;
	}
}
