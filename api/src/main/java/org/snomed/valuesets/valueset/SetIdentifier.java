package org.snomed.valuesets.valueset;

/** A reference set concept checked as fit to identify a value set. */
public record SetIdentifier(String conceptId, String fullySpecifiedName, String preferredTerm) {
}
