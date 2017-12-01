package eu.europa.ec.cipa.etrustex.services.exception;

import eu.europa.ec.cipa.etrustex.types.ErrorResponseCode;

public class MessageUpdateException extends EncodedBusinessException {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public MessageUpdateException(String message,
			ErrorResponseCode responseCode) {
		super(message, responseCode);
	}

	public MessageUpdateException(Throwable cause,
			ErrorResponseCode responseCode) {
		super(cause, responseCode);
	}

	public MessageUpdateException(Throwable cause, String message,
			ErrorResponseCode responseCode) {
		super(cause, message, responseCode);
	}

}