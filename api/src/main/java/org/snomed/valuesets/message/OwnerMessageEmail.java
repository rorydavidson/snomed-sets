package org.snomed.valuesets.message;

/**
 * The email an owner receives. Plain text only, so nothing a sender writes can become a link or
 * markup in the owner's mail program beyond what that program does with plain text anyway.
 *
 * @param replyTo the sender's verified address, so the owner can answer them directly
 */
public record OwnerMessageEmail(String to, String replyTo, String subject, String text) {

	static OwnerMessageEmail compose(String ownerEmail, String senderName, String senderEmail, String valueSetName,
			String valueSetUrl, String profileUrl, String message) {
		String setName = singleLine(valueSetName);
		String sender = singleLine(senderName);
		String text = """
				%s sent you a message about your value set "%s".

				%s

				Reply to this email to answer %s. Your reply will show them your email address.

				The value set: %s

				You get these messages because you let signed-in people message you about your value sets.
				To stop them, switch this off in your profile: %s
				""".formatted(sender, setName, message.strip(), sender, valueSetUrl, profileUrl);
		return new OwnerMessageEmail(ownerEmail, senderEmail, "Message about your value set \"" + setName + "\"", text);
	}

	/** Line breaks are removed from anything that goes into the subject or a sentence of its own. */
	private static String singleLine(String text) {
		return text.replaceAll("[\\r\\n\\t]+", " ").strip();
	}
}
